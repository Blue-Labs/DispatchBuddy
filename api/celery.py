"""
this is the application head. as celery loads and runs it, it will create
a worker queue submissive to app() which is created by instantiating
Celery('tasks'). each function decorated as @app.task is visible to the
task process. only simple objects can be shared from app() to a Task.
complex variables such as network connections must never be shared.
"""


import base64
import sys
import time
import hashlib
import datetime
import traceback
import threading
import subprocess
import psycopg2
import os
import configparser
import requests
import logging
import pyrebase

from celery import Celery, Task, group, chord, chain
from celery.result import GroupResult
#from celery.contrib.methods import task_method
from celery.signals import worker_shutdown, worker_process_shutdown, worker_process_init, worker_init
from celery.signals import eventlet_pool_preshutdown
from celery.utils.log import get_task_logger

from api.database import Database
from api.messaging import Messaging
from memory_profiler import profile
from parsers.pjl_lexmark import PCLParser as Parser


# i don't have time to resolve relative imports, fuck it
sys.path.append('/var/bluelabs/DispatchBuddy')
app = Celery('tasks')

celery_config = dict(
    CELERYD_HIJACK_ROOT_LOGGER         = False,
    CELERY_TASK_SERIALIZER             = 'json',
    CELERY_ACCEPT_CONTENT              = ['json'],  # Ignore other content
    CELERY_TIMEZONE                    = 'America/New_York',
    CELERY_ENABLE_UTC                  = True,
    CELERY_RESULT_BACKEND              = 'file:///var/db/DispatchBuddy/celery.results',  # was amqp
    CELERY_RESULT_PERSISTENT           = True,
    CELERY_RESULT_SERIALIZER           = 'json',
    CELERY_IGNORE_RESULT               = False,
    CELERY_TRACK_STARTED               = True,
    CELERY_EAGER_PROPAGATES_EXCEPTIONS = True,
    #CELERY_DEFAULT_QUEUE              = 'db',
    #CELERY_QUEUES                     = (Queue('db', Exchange('db'), routing_key='db'),),

    # broken for py3
    CELERYD_STATE_DB                   = '/var/db/DispatchBuddy/celeryd.state',

    CELERYD_TIMER_PRECISION            = 0.1,
    #BROKER_URL                         = 'amqp://127.0.0.1',
    BROKER_CONNECTION_TIMEOUT          = 0.5, # doesn't seem to help at all
    #BROKER_HEARTBEAT                  = 30,
    #BROKER_HEARTBEAT_CHECKRATE        = 1,
    BROKER_POOL_LIMIT                  = 4,
)

logger = get_task_logger(__name__)


# these instructions are ONLY performed in MainProcess, child forks must init their own DB instance in @worker_process_init
configfile   = '/etc/dispatchbuddy/DispatchBuddy.conf'
config       = configparser.ConfigParser()

if not config.read(configfile):
    logger.warning ('Error reading required configuration file: {}'.format(configfile))

logger.setLevel(getattr(logging, config.get('Logging', 'log level').upper(), 'WARNING'))

celery_config['BROKER_URL'] = config.get('Celery', 'broker url')

if os.getenv('TESTING') is not None:
    logger.warning('TESTING is set')

def flushall():
    sys.stdout.flush()

app.conf.update(celery_config)
app.loader.on_worker_shutdown = flushall

appDB = Database(config, app=True)

#print(dir(app.loader))
#sys.stdout.flush()

# DB should be hooked under celery init. this is to signal the database instances to exit
# it's damned convoluted. celery forks workers on startup and will create a DB context then.
# but, the @task calls stem from the MailProcess, so we need a DB instance there TOO
@worker_init.connect()
def start_worker_init_db(**kwargs):
    # logging will show up in journalctl until MainProcess gets going
    logger.info('\x1b[1;35mworker_init({}) {!r}\x1b[0m'.format(os.getpid(), kwargs))


@worker_process_init.connect()
def start_process_init_db(*args, **kwargs):
    # this is global to the @app.task, NOT celery.py:app(). each task needs its own
    # DB instance so we don't have conflicting network connections. each task is a
    # separate Linux process. simple variables can be shared via app(), those such as
    # network connections, must never be shared
    global DB, firebase_user, firebase_db, firebase_user_authtime
    DB = Database(config)
    logger.info('starting worker process, DB is {}, DB.conn is {}'.format(DB, DB.conn))
    logger.info('\x1b[1;34mprocess_init({}, {}) {!r}\x1b[0m'.format(os.getpid(), args, kwargs))

    firebase = pyrebase.initialize_app(config['Firebase'])
    auth = firebase.auth()
    try:
        firebase_user = auth.sign_in_with_email_and_password(config['Firebase']['username'], config['Firebase']['password'])
        firebase_user_authtime = datetime.datetime.utcnow()
    except:
        logger.error('Failed to login to Firebase: {}'.format(e))
    firebase_db = firebase.database()


# shutdown the MainProcess DB (note; you won't see logging information from DB here.)
@worker_shutdown.connect()
def main_worker_shutdown(**kwargs):
    logger.info('main_worker_shutdown()')
    """
    try:
        for name, task in app.tasks.items():
            if hasattr(task, 'DB'):
                print('[main] need to shut down DB on {}'.format(name))
                sys.stdout.flush()
                task.DB.shutdown();
                task.DB.th.join()
                print('TH joined, we are done')
                sys.stdout.flush()
    except Exception as e:
        sys.stdout.flush()
    """


# shutdown each worker DB instance
@worker_process_shutdown.connect()
def process_worker_shutdown(**kwargs):
    logger.info('process_worker_shutdown()')

    DB.shutdown()
    for t in DB.th:
        t.join()

    #for name, task in app.tasks.items():
    #    if hasattr(task, 'DB'):
    #        task.DB.shutdown()
    #        task.DB.th.join()


@eventlet_pool_preshutdown.connect()
def preshutdown(*args, **kwargs):
    print('preshutdown connect')

#class DispatchBuddyTask(Task):
#    ignore_return = False

@app.task(bind=True)
def dispatch_job(self, id, payload):
    ''' Decode the PCL data and extract textual words, store in database, and if
        unique, broadcast it to all of our configured gateways

          payload: string
    '''

    logger.debug('DB is: {}'.format(DB))
    logger.debug('DB.conn is: {}'.format(DB.conn))
    logger.debug('DB.rxl is: {}'.format(DB.recipient_list))

    # determine what type of payload it is -- right now we only have tcp/9100 lexmark PCL data
    try:
        logger.debug('{}'.format(self.request))
    except Exception as e:
        logger.debug('could not print self.request: {}'.format(e))

    logger.debug('got {} raw bytes in payload for {}'.format(len(payload),id))
    payload = base64.b64decode(payload.encode())
    logger.debug('decoding {} bytes in payload for {}'.format(len(payload),id))
    logger.debug('first 200 bytes: {!r}'.format(payload[:200]))

    if not (payload.startswith(b'\0\x1b%-12345X@PJL') and b'Lexmark' in payload[:200]):
        logger.debug('incorrect prelude, ignoring')
        return

    # if no parser has been imported, an exception will happen here

    parser = Parser(logger, id)
    parser.load(data=payload)
    ev = parser.parse()


    if os.getenv('TESTING'):
        ev = ev._replace(**{'notes':'** TESTING ** '+ev.notes})
        ev = ev._replace(**{'units':'** TESTING ** '+ev.units})
        ev = ev._replace(**{'nature':'** TESTING ** '+ev.nature})

    for k,v in sorted(ev._asdict().items()):
        logger.debug('  {:<12} {}'.format(k,v))

    store_event(id, payload, ev._asdict())

    if unique_message(ev) is False:
        logger.info('skipping as this appears to be a duplicate')
        return

    res = (db_broadcast(id, ev._asdict()) | delivery_report.s(id) ) ()
    logger.info('res: {}'.format(res))

    #self.db_print_remote(id)


def store_event(id, payload, ev):
    fname = '/var/db/DispatchBuddy/evdata/{}.pcl'.format(id)
    try:
        with open(fname, 'wb') as f:
            f.write(payload)
        logger.debug('{}.pcl stored on disk'.format(id))

    except Exception as e:
        logger.warning('failed to store event data on disk: {}'.format(e))
        logger.warning('uid:{}, euid:{}, gid:{}, egid:{}'.format(os.getuid(), os.geteuid(), os.getgid(), os.getegid()))

    args = {'incident_number'  :ev['incident_number'],
            'report_number'    :ev['report_number'],
            'dispatch_station' :'',
            'response_station' :'',
            'event_ts'         :ev['isotimestamp'],
            'insert_ts'        :'now()',
            'ev_type'          :ev['msgtype'],
            'priority'         :'',
            'address'          :ev['address'],
            'description'      :ev['notes'],
            'units'            :ev['units'],
            'caller'           :'',
            'name'             :ev['business'],
            'ev_uuid'          :id,
            'nature'           :ev['nature'],
            'cross_streets'    :ev['cross'],
            'city'             :ev['city'],
           }

    try:
        DB.store_event(args)
    except Exception as e:
        logger.warning('failed to store event in BlueLabs DB: {}'.format(e))

    if (datetime.datetime.utcnow() - firebase_user_authtime).total_seconds() > 3600:
        auth = firebase.auth()
        try:
            firebase_user = auth.sign_in_with_email_and_password(config['Firebase']['username'], config['Firebase']['password'])
            firebase_user_authtime = datetime.datetime.utcnow()
        except:
            logger.error('Failed to login to Firebase: {}'.format(e))

    try:
        firebase_db.child('dispatches').push(dict(ev), token=firebase_user['idToken'])
    except Exception as e:
        logger.warning('failed to store event in Firebase: {}'.format(e))


def unique_message(ev):
    hashdata = ''
    for p in ('nature','notes','cross','address','units'):
        hashdata += getattr(ev, p, '')

    logger.debug('hash on {!r}'.format(hashdata))

    hashdata = bytes(hashdata, encoding='utf-8')
    hash     = hashlib.md5(hashdata).hexdigest()
    now      = datetime.datetime.now()

    DB.expire_event_hashes()

    try:
        DB.add_event_hash(hash,now)

    except psycopg2.IntegrityError as e:
        if hasattr(e, 'pgcode') and e.pgcode == '23505': # duplicate key error
            logger.warning('Duplicate event within last 5 minutes discarded')
            return False
        logger.error('unexpected psycopg2 error: {} {}'.format(e.pgcode, e))

    except Exception as e:
        # any other error such as DB not online and so on, will be ignored as it is
        # more important to resend a duplicate than to fail to send a dispatch
        logger.error('Failed to check DB for duplicates: {}'.format(e))


@app.task
def Maleman(gateway, mediatype, id, evdict):
    m = Messaging(DB, config, gateway, mediatype, id, evdict)
    return m._run()


@app.task
def db_broadcast(id, evdict):
    # parent broadcaster that initiates tasks to send to each type of media
    # get a sorted list of tuples for each gateway:media combo
    try:
        gateway_medias = sorted(set([ (x.gateway,x.mediatype) for x in DB.recipient_list ]))
        logger.debug('{}'.format(gateway_medias))
    except Exception as e:
        logger.error('db_broadcast() error: {}'.format(e))

    tasks = []

    for g,m in gateway_medias:
        # having multiple threads writing to the DB connection isn't safe, this needs to change
        # not a problem now, each worker has its own DB connection

        # overlay sync bug means add None to the end of these args or the Task() class
        # will think we're missing an arg
        t = Maleman.s(g, m, id, evdict)
        tasks.append(t)

    return group(tasks)


@app.task
def delivery_report(res, id):
    # REST call to generate a delivery report
    rxlist = [r[0]+'/'+r[1]+'/'+r[2] for resi in res if resi for r in resi]

    logger.debug('>>>>>>>>>>>>>> do delivery report for: {} {}'.format(rxlist,id))
    r = requests.post('https://smvfd.info/dispatchbuddy/event-delivery-report', data={'id':id, 'rxlist':rxlist}, timeout=30)
    logger.debug('report request: {}'.format(r))


@app.task
def db_print_remote(res, id):
    ''' celery chain() will feed us the results of a previous call (arg0) when chaining, just ignore it
    '''
    try:
        cp = subprocess.run(['lpr','-H','10.69.0.69','-P','9840CDW','/var/db/DispatchBuddy/evdata/{}.pcl'.format(id)])
        logger.info('remote print exit code: {}'.format(cp.returncode))
    except Exception as e:
        logger.warning('lpr fault: {}'.format(e))


if __name__ == '__main__':
    # not run by celery
    logger.error('starting as __main__')
    app.start()
    DB.shutdown()
