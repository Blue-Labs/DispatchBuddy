
'''
Base class for message broadcasting
'''

import datetime, uuid, logging, os
import api.formatters
import api.gateways

class Messaging(api.gateways.Gateway):
    def __init__(self, gateway=None, mediatype=None):
        self.gateway   = gateway
        self.mediatype = mediatype
        self.db        = None
        self.xmd       = {} # dictionary of messages to be transmitted
        self.logger    = logging.getLogger()
    

    def set_db(self, db):
        self.db = db


    def _run(self, gateway, mediatype, id, evdict):
        self.gateway   = gateway
        self.mediatype = mediatype
        self.evdict    = evdict

        rx = self.select_recipient_list()
        if not rx:
            print('no recipients for {}/{}'.format(gateway, mediatype))
            return

        msg = self.format_message(evdict)

        #self.logger.debug('formatted msg for {}/{} is: {!r}'.format(gateway, mediatype, msg))

        self.deliver(id, rx, msg)


    def select_recipient_list(self):
        # load all recipients that match my gateway and mediatype from database
        # then of recipients valid for this time of day
        # who are also set to receive messages for today
        now = datetime.datetime.now()
        dow = now.strftime('%a').lower()
        now = now.time().replace(second=0, microsecond=0)
        
        if not self.db:
          print('omgwtf no db')
        
        while not self.db.recipient_list:
          print('waiting for db to get RX list')
          time.sleep(1)
        
        
        rx = [x for x in self.db.recipient_list if x.gateway   == self.gateway   \
                                               and x.mediatype == self.mediatype \
                                               and getattr(x, dow) == True       \
                                               and x.dispatch == True            \
                                               and not x.stop == True            \
                                               and x.start_time <= now <= x.stop_time ]

        # override RX list with only 'testing=True' recipients
        if os.getenv('TESTING'):
            rx = [r for r in rx if r.testing == True]
            print(rx)

        return rx


    def format_message(self, evdict):
        # apply a formatter to this message
        # just hardcode formatter selection so i can get this out the door

        fm = getattr(api.formatters, self.mediatype.upper(), None)
        
        if not fm:
            self.logger.warning('formatter for {} not found'.format(self.mediatype))
            return
        
        fm = fm(self.mediatype)
        return fm.format(evdict)
