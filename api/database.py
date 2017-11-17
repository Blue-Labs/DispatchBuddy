#!/usr/bin/python

import configparser
import datetime
import functools
import logging
import psycopg2
import psycopg2.extensions
import psycopg2.extras
import queue
import threading
import time
import random

from celery.utils.log import get_task_logger
logger = get_task_logger(__name__)

class Database:
    def __init__(self, config, app=False):
        self.logger               = logger

        self.config               = config
        self.app                  = app

        self._shutdown_queue      = threading.Event()
        self._shutdown_exit_wait  = threading.Event()
        self._shutdown_connection = threading.Event()
        self.reconnect            = threading.Event()
        self.child_tids           = {}
        self.th                   = []

        # database connection
        self.conn                 = None

        # pending DB transaction event and queue
        self.pending              = threading.Event()
        self.pending_xact         = queue.Queue()

        # thread event barrier stop network actions
        self.network              = threading.Event()

        t = threading.Thread(target=self._connection_monitor, name='Database Connection Monitor')
        t.start()
        self.logger.info('start: {}'.format(t.name))
        self.child_tids[t.ident]=t.name

        t = threading.Thread(target=self._push_queue, name='Database Queue Handler')
        t.start()
        self.logger.info('start: {}'.format(t.name))
        self.th.append(t)
        self.child_tids[t.ident]=t.name

        if not self.conn:
            self.network.clear() # setup roadblock
            self.reconnect.set()


    """ public methods """
    def store_event(self, ev):
        keys     = sorted(ev.keys())
        t_fields = ','.join(keys)
        t_values = ','.join(['%%(%s)s' % x for x in keys])
        qstr     = 'INSERT INTO incidents ({}) VALUES ({})'.format(t_fields, t_values)
        self.logger.debug(qstr)
        self._do_statement(qstr, ev)

    def add_event_hash(self, hash, ts):
        self._do_statement('INSERT INTO event_hashes (hash,ts) VALUES (%s,%s)', (hash,ts))
        self.event_hashes.append((hash,ts))

    def expire_event_hashes(self):
        self._do_statement("DELETE FROM event_hashes WHERE ts < now() - interval '5 minutes'")
        self._reload_event_hashes()

    def store_transmitted_message_status(self, **kwargs):
        event_uuid      = kwargs.get('event_uuid')
        if not event_uuid:
            raise KeyError('event_uuid is required')

        recipient       = kwargs.get('recipient')
        if not recipient:
            raise KeyError('recipient is required')

        gateway         = kwargs.get('gateway')
        if not gateway:
            raise KeyError('gateway is required')

        mediatype       = kwargs.get('mediatype')
        if not mediatype:
            raise KeyError('mediatype is required')

        delivery_id     = kwargs.get('delivery_id')
        delivery_ts     = kwargs.get('delivery_ts')
        if not delivery_ts:
            delivery_ts = datetime.datetime.utcnow()

        delivery_status = kwargs.get('status', '')
        completed       = kwargs.get('completed', False)
        reason          = kwargs.get('reason', '')

        r = self._do_statement('''
          INSERT INTO event_deliveries (
              event_uuid,recipient,gateway,mediatype,delivery_id,delivery_ts,delivery_status,completed,reason
              )
            VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s)
          ''', (event_uuid, recipient, gateway, mediatype, delivery_id, delivery_ts, delivery_status, completed, reason))

    def shutdown(self):
        self.logger.info('shutting down')

        # when running from commandline to test, we must allow enough time
        # for the startup process to finish
        if not self.pending_xact.empty():
            self.logger.info('queue is draining')
            self.network.wait()

            retries = 10
            while retries and not self.pending_xact.empty():
                self.logger.info('queue is still draining')
                time.sleep(3)
                retries -= 1

        if not self.pending_xact.empty():
            self.logger.warning("Queue is not empty, losing records")

        self._shutdown_queue.set()
        self._shutdown_exit_wait.clear()
        self.pending.set()
        # tell the GIL we wish to yield our thread so another gets scheduled
        # then wait until the queue empties
        time.sleep(0)
        self._shutdown_exit_wait.wait()
        self._shutdown_connection.set()
        self.reconnect.set()
        self._disconnect()
        self.logger.info('DB connection shutdown')


    """ private methods """
    def _setup_notifications(self):
        _notify_dispatchbuddy_proc = '''
            CREATE OR REPLACE FUNCTION notify_dispatchbuddy_proc() RETURNS trigger AS $$
            DECLARE
                _json    json;
                _record  record;
            BEGIN
                IF TG_OP = 'INSERT' or TG_OP = 'UPDATE' THEN
                    SELECT TG_TABLE_NAME AS table, TG_OP AS action, NEW.*
                    INTO    _record;
                ELSE
                    SELECT TG_TABLE_NAME AS table, TG_OP AS action, OLD.*
                    INTO    _record;
                END IF;

                _json = row_to_json(_record);
                PERFORM pg_notify(CAST('dispatchbuddy' AS text), CAST(_json AS text));

                IF TG_OP = 'INSERT' or TG_OP = 'UPDATE' THEN
                    RETURN NEW;
                ELSE
                    RETURN OLD;
                END IF;

            END;
            $$ LANGUAGE plpgsql;
            '''

        _trig = '''
            DO
            $$
            BEGIN
                IF NOT EXISTS (SELECT *
                    FROM  information_schema.triggers
                    WHERE event_object_table = '{table}'
                    AND   trigger_name = 'dispatchbuddy_notify_{table}_{op}'
                )
                THEN
                    CREATE TRIGGER dispatchbuddy_notify_{table}_{op}
                        {when} {op} ON {table}
                        FOR EACH ROW
                        EXECUTE PROCEDURE notify_dispatchbuddy_proc();
                END IF;
            END;
            $$
            '''

        with self.conn.cursor() as c:
            # this should only be done by the app, and only once, at startup
            if self.app:
                self.logger.info('main app setting plpgsql functions')
                c.execute(_notify_dispatchbuddy_proc)
                self.shutdown()

            # and, the app doesn't need any triggers
            else:
                for table in {'msgingprefs2015',}:
                    for op,when in {'insert':'BEFORE',
                                    'update':'AFTER',
                                    'delete':'BEFORE'}.items():
                        c.execute(_trig.format(op=op, when=when, table=table))

    def _reload_recipients(self):
        self.recipient_list = self._do_statement('SELECT * FROM msgingprefs2015',
            required=True, results=True)
        self.logger.info('RX list is {} entries'.format(len(self.recipient_list)))

    def _reload_event_hashes(self):
        self.event_hashes = self._do_statement('SELECT * from event_hashes',
            required=True, results=True)

    def _connection_monitor(self):
        self.recipient_list = None
        self.event_hashes   = []

        while True:
            if not self.conn:
                hangtime=2
            else:
                hangtime=20

            self.reconnect.wait(hangtime)

            if self._shutdown_connection.is_set():
                self.logger.info('stop: {}'.format(self.child_tids[threading.get_ident()]))
                break

            try:
                self.conn
                with self.conn.cursor() as c:
                    c.execute('SELECT 1')

            except Exception as e:
                if not self.conn is None:
                    self.logger.warning('marking offline: {}'.format(e))
                self.network.clear()

            if not self.network.is_set():
                self.logger.info('reconnecting')
                self._connect()

                if self.network.is_set():
                    self.logger.info('connected to DB')
                    self.reconnect.clear()

                    self._setup_notifications()

                    if not self.app:
                        self._reload_recipients()
                        self._reload_event_hashes()

                        with self.conn.cursor() as c:
                            c.execute('LISTEN dispatchbuddy')

                    self.logger.info('finished reconnect')

            if not self.app:
                if self.network.is_set():
                    reload_trigger = False
                    with self.conn.cursor() as c:
                        while self.conn.notifies:
                            notify = self.conn.notifies.pop(0)
                            self.logger.debug('Got NOTIFY: {}'.format(notify.payload))
                            reload_trigger = True

                    if reload_trigger:
                        self._reload_recipients()
                        self._reload_event_hashes()

                else:
                    self.logger.error('WTRF. not online?')
                    time.sleep(1) # wait at least 1 second before reconnect

        self.logger.info('exit: {}'.format(self.child_tids[threading.get_ident()]))


    def _connect(self):
        self.network.clear()
        if self.conn:
            self.conn.close()
            self.conn = None

        dburi = self.config.get('Database', 'db uri')

        try:
            self.logger.debug('_connect()')
            self.conn = psycopg2.connect(dburi)
        except Exception as e: # all errors will be fatal
            self.logger.critical('failed connect: {}'.format(e))

        if self.conn:
            self.conn.set_isolation_level(psycopg2.extensions.ISOLATION_LEVEL_AUTOCOMMIT)
            self.network.set()
            self.pending.set()


    def _disconnect(self):
        self.conn.close()
        self.conn = None


    def _do_statement(self, statement, args=None, required=False, results=False, commit=False):
        # if required, do statement immediately, otherwise just
        # put it on the queue and return promptly
        rv = None

        while required: # returns on success
            try:
                self.logger.debug('starting statement: {}'.format(statement))
                with self.conn.cursor(cursor_factory=psycopg2.extras.NamedTupleCursor) as c:
                    c.execute(statement, args)

                    if commit:
                        self.conn.commit()

                    if results:
                        return c.fetchall()

                    return

            except Exception as e:
                # need better logic to decide if to reconnect or retry
                # also, do we need to roll back?
                self.logger.warning(' _do_statement exception: {} ({})'.format(e, e.__class__.__name__))
                self.logger.warning(' _do_statement exception statement: {}'.format(statement))
                self.logger.warning(' _do_statement exception conn: {}'.format(self.conn))
                self.network.clear()
                self.reconnect.set()

                # put it on the stack and return
                self.network.wait()

        self.pending_xact.put((statement, args, required, results, commit))
        self.pending.set()


    def _push_queue(self):
        logger = logging.getLogger(__name__)
        while True:
            self.pending.wait()
            self.pending.clear()
            self.logger.debug('pending triggered')

            if self._shutdown_queue.is_set() and self.pending_xact.empty():
                self.logger.info('stop: {}'.format(self.child_tids[threading.get_ident()]))
                self.pending.set()
                break

            if self.pending_xact.empty():
                self.logger.debug('no queue, resume loop')
                continue

            self.network.wait()

            # try the statement
            with self.conn.cursor() as c:
                success = False
                try:
                    v =self.pending_xact.get()
                    statement, args, required, results, commit = v
                    c.execute(statement, args)

                    if c.rowcount:
                        self.conn.commit()
                    success = True

                except (psycopg2.OperationalError, psycopg2.InterfaceError) as e:
                    logger.warning('reconnect needed, this should NOT have occurred; {} ({})'.format(e, e.__class__.__name__))
                    # put back on the stack
                    self.pending_xact.put((statement, args, required, results, commit))
                    self.network.clear()
                    self.reconnect.set()
                    self.network.wait()

                except Exception as e:
                    logger.warning('op failed: {}, {}'.format(e, e.__class__.__name__))
                    self.conn.rollback()
                    self.pending_xact.put((statement, args, required, results, commit))
                    time.sleep(1)

                finally:
                    if not success:
                        self.pending_xact.put((statement, args, required, results, commit))

                        # let the reconnection trigger this cycle AFTER reconnect occurs
                        if not self.reconnect.is_set():
                            time.sleep(1)
                            self.pending.set()

            # we're not the only queue handler, shutdown might run us
            if self._shutdown_queue.is_set() and self.pending_xact.empty():
                self.pending.set()
                break

            # queue not empty?
            if not self.pending_xact.empty():
                self.pending.set()

        self.logger.info('queue handler exiting')
        self._shutdown_exit_wait.set()
        self.logger.info('exit: {}'.format(self.child_tids[threading.get_ident()]))
