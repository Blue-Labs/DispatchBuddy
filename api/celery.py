import base64, logging, sys, time, hashlib, datetime, traceback
import psycopg2, os

from celery import Celery, Task, group, chord
from celery.result import GroupResult
from celery.contrib.methods import task_method
from celery.signals import worker_shutdown, worker_process_shutdown, worker_process_init, worker_init

from api.database import Database
from api.messaging import Messaging

# i don't have time to resolve relative imports, fuck it
sys.path.append('/var/bluelabs/DispatchBuddy')

capp = Celery('tasks')

capp.conf.update(
    CELERY_TASK_SERIALIZER       = 'json',
    CELERY_ACCEPT_CONTENT        = ['json'],  # Ignore other content
    CELERY_RESULT_SERIALIZER     = 'json',
    CELERY_TIMEZONE              = 'America/New_York',
    CELERY_ENABLE_UTC            = True,
    BROKER_URL                   = 'amqp://127.0.0.1',
    CELERY_RESULT_BACKEND        = 'amqp://127.0.0.1',
    BROKER_CONNECTION_TIMEOUT    = 0.5, # doesn't seem to help at all
    CELERY_TRACK_STARTED         = True,
    CELERYD_TIMER_PRECISION      = 0.1,
    BROKER_HEARTBEAT             = 2,
    BROKER_HEARTBEAT_CHECKRATE   = 2,
    
)

DB=None

# DB should be hooked under celery init. this is to signal the database instances to exit
# it's damned convoluted. celery forks workers on startup and will create a DB context then.
# but, the @task calls stem from the MailProcess, so we need a DB instance there TOO
@worker_init.connect()
def start_db(**kwargs):
    global DB
    DB = Database()

@worker_process_init.connect()
def start_db(**kwargs):
    global DB
    DB = Database()


# shutdown the MainProcess DB (note; you won't see logging information from DB here.)
@worker_shutdown.connect()
def take_a_shit(**kwargs):
    DB.shutdown()

# shutdown each worker DB instance
@worker_process_shutdown.connect()
def take_a_shit(**kwargs):
    DB.shutdown()


@capp.task
def decode_payload_data(id, payload):
    # determine what type of payload it is -- right now we only have tcp/9100 lexmark PCL data
    logger = logging.getLogger()
    logger.debug('got {} raw bytes in payload for {}'.format(len(payload),id))
    payload = base64.b64decode(payload.encode())
    logger.debug('decoding {} bytes in payload for {}'.format(len(payload),id))
    logger.debug('first 200 bytes: {!r}'.format(payload[:200]))
    
    if payload.startswith(b'\0\x1b%-12345X@PJL') and b'Lexmark' in payload[:200]:
        from parsers.pjl_lexmark import PCLParser as Parser
    
    # if no parser has been imported, an exception will happen here

    parser = Parser(logger, id)
    parser.load(data=payload)
    ev = parser.parse()
    
    if os.getenv('TESTING'):
        ev = ev._replace(**{'notes':'**TESTING** '+ev.notes})
        ev = ev._replace(**{'units':'**TESTING** '+ev.units})

    for k,v in sorted(ev._asdict().items()):
        logger.debug('  {:<12} {}'.format(k,v))
    
    # :( celery can't handle named tuples
    store_event.delay(id, base64.b64encode(payload).decode(), ev._asdict())
    
    if not unique_message(ev):
        return

    results = broadcast(id, ev._asdict())
    results.apply_async()


def unique_message(ev):
    logger = logging.getLogger()

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
            return
        print('oh shit papahoozie! {}'.format(e.pgcode))
        raise
    
    except Exception as e:
        # any other error such as DB not online and so on, will be ignored as it is
        # more important to resend a duplicate than to fail to send a dispatch
        print('Failed to check DB for duplicates: {}'.format(e))

    return True


@capp.task
def store_event(id, payload, ev):
    logger = logging.getLogger()
    payload = base64.b64decode(payload.encode())
    fname = '/var/db/DispatchBuddy/evdata/{}.pcl'.format(id)
    try:
        with open(fname, 'wb') as f:
            f.write(payload)
        logger.debug('{}.pcl stored on disk'.format(id))

    except Exception as e:
        logger.warning('failed to store event data on disk: {}'.format(e))
        logger.warning('uid:{}, euid:{}, gid:{}, egid:{}'.format(os.getuid(), os.geteuid(), os.getgid(), os.getegid()))
    
    args = {'case_number'      :ev['case_number'],
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
        logging.getLogger().warning('failed to store event in DB')


class Maleman(Messaging, Task):
    def __init__(self):
        Messaging.__init__(self)
    
    def run(self, gateway, mediatype, id, evdict, *args, **kwargs):
        self.set_db(DB)
        self._run(gateway, mediatype, id, evdict)


def broadcast(id, evdict):
    # parent broadcaster that initiates tasks to send to each type of media
    # get a sorted list of tuples for each gateway:media combo
    gateway_medias = sorted(set([ (x.gateway,x.mediatype) for x in DB.recipient_list ]))
    print(gateway_medias)
    
    tasks = []
    
    for g,m in gateway_medias:
        maleman = Maleman()
        # overlay sync bug means add None to the end of these args or the Task() class
        # will think we're missing an arg
        t = maleman.s(g,m, id, evdict, None)
        tasks.append(t)
    
    return group(tasks)
    

@capp.task
def send_to_gateway():
    pass

