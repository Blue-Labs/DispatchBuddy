
import logging, threading, random, time
import psycopg2, psycopg2.extras, psycopg2.extensions

class Database():
    def __init__(self, config):
        self.logger         = logging.getLogger()
        self.online         = None
        self._shutdown      = threading.Event()
        self.reconnect      = threading.Event()
        self.conn           = None
        self.recipient_list = None
        self.reload_trigger = False
        self.config         = config
        
        cm = threading.Thread(target=self._connection_monitor, name='Database')
        cm.start()


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
            c.execute(_notify_dispatchbuddy_proc)
            
            for table in {'msgingprefs2015',}:
                for op,when in {'insert':'BEFORE',
                                'update':'AFTER',
                                'delete':'BEFORE'}.items():
                    c.execute(_trig.format(op=op, when=when, table=table))
        

    def _connection_monitor(self):
        self.reconnect.set()
        
        while True:
            self.reconnect.wait(10)

            if self._shutdown.is_set():
              self._disconnect()
              break

            #self.logger.debug('DB connection check')

            # test to see if we're online by sending an SQL "ping"
            try:
              with self.conn.cursor() as c:
                c.execute('SELECT 1=1')
                c.fetchone()
            except:
              self.online = False
            
            if not self.online:
              self.logger.info('reconnecting to DB')
              self._connect()
              if self.online:
                self.logger.info('connected to DB, fetching data')
                self.reconnect.clear()
                self.reload_recipients()
                self.reload_event_hashes()
                # don't do this in parallel or we get DB errors about concurrent updates
                while True:
                    time.sleep(random.random()*5)
                    try:
                        self._setup_notifications()
                        break
                    except:
                        pass
                with self.conn.cursor() as c:
                    c.execute('LISTEN dispatchbuddy')
            
            if self.online:
                with self.conn.cursor() as c:
                    while self.conn.notifies:
                        notify = self.conn.notifies.pop(0)
                        self.logger.debug('Got NOTIFY: {}'.format(notify.payload))
                        self.reload_trigger = True
                if self.reload_trigger:
                    self.reload_trigger = False
                    self.reload_recipients()
                    self.reload_event_hashes()


    def _connect(self):
        if self.online:
            self.online = None
            try:
              self.conn.close()
            except:
              pass
        
        dburi = self.config.get('Database', 'db uri')

        self.conn = psycopg2.connect(dburi)
        if self.conn:
            self.online = True
            self.conn.set_isolation_level(psycopg2.extensions.ISOLATION_LEVEL_AUTOCOMMIT)


    def _disconnect(self):
        self.conn.close()
        self.online = None
        self.conn = None


    def shutdown(self):
        self.logger.info('shutting down DB')
        self._shutdown.set()
        self.reconnect.set()


    def reload_recipients(self):
        with self.conn.cursor(cursor_factory=psycopg2.extras.NamedTupleCursor) as c:
            c.execute('SELECT * from msgingprefs2015')
            self.recipient_list = c.fetchall()
            self.logger.debug('RX is {} entries'.format(len(self.recipient_list)))

    def reload_event_hashes(self):
        with self.conn.cursor() as c:
            c.execute('SELECT * from event_hashes')
            self.event_hashes = c.fetchall()
    
    def store_event(self, ev):
        with self.conn.cursor() as c:
            keys     = sorted(ev.keys())
            t_fields = ','.join(keys)
            t_values = ','.join(['%%(%s)s' % x for x in keys])
            qstr     = 'INSERT INTO incidents ({}) VALUES ({})'.format(t_fields, t_values)
            print(qstr)
            
            try:
                c.execute(qstr, ev)
                self.conn.commit()
            except:
                self.conn.rollback()
                raise


    def add_event_hash(self, hash, ts):
        with self.conn.cursor() as c:
            try:
                c.execute('INSERT INTO event_hashes (hash,ts) VALUES (%s,%s)', (hash,ts))
                self.conn.commit()
                self.event_hashes.append((hash,ts))
            except Exception as e:
                self.conn.rollback()
                raise


    def expire_event_hashes(self):
        if not self.conn:
            self.logger.warning('DB instance is not online')
            self.online = None
            self.reconnect.set()
            return
        
        with self.conn.cursor() as c:
            c.execute("DELETE FROM event_hashes WHERE ts < now() - interval '5 minutes'")
            if c.rowcount:
                self.conn.commit()
                self.reload_event_hashes()
