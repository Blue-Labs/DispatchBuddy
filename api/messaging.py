
'''
Base class for message broadcasting
'''

import datetime
import uuid
import logging
import os
import time
import api.formatters
import api.gateways

logging.basicConfig(level=logging.DEBUG)

class Messaging(api.gateways.Gateway):
    def __init__(self, db, config, gateway, mediatype, id, evdict):
        self.db        = db
        self.config    = config
        self.gateway   = gateway
        self.mediatype = mediatype
        self.id        = id
        self.evdict    = evdict
        self.xmd       = {} # dictionary of messages to be transmitted
        self.logger    = logging.getLogger('Messaging')


    def _run(self):
        rx = self.select_recipient_list()
        if not rx:
            self.logger.info('no recipients for {}/{}'.format(self.gateway, self.mediatype))
            return

        msg = self.format_message(self.evdict)

        #self.logger.debug('formatted msg for {}/{} is: {!r}'.format(self.gateway,
        #                  self.mediatype, msg))

        self.deliver(self.id, rx, msg, self._status_recorder)

        rxd=[]
        for r in rx:
            ra = r.address
            if r.mediatype.lower() in ('mms','sms') and not r.address[0:1]=='+1':
                ra = '+1'+r.address
            rxd.append((r.gateway, r.mediatype, ra))

        return rxd


    def _status_recorder(self, *args, **kwargs):
        # event_uuid,recipient,gateway,mediatype,delivery_id,delivery_ts,delivery_status
        # args: to, id, ts, status
        self.db.store_transmitted_message_status(
            event_uuid=self.id, gateway=self.gateway, mediatype=self.mediatype,
            **kwargs
        )


    def select_recipient_list(self):
        # load all recipients that match my gateway and mediatype from database
        # then of recipients valid for this time of day
        # who are also set to receive messages for today
        now = datetime.datetime.now()
        dow = now.strftime('%a').lower()
        now = now.time().replace(second=0, microsecond=0)

        # this should be thread locked
        while not self.db.recipient_list:
          self.logger.info('waiting for db to get RX list')
          time.sleep(1)


        try:
            rx = [x for x in self.db.recipient_list if x.gateway   == self.gateway   \
                                                   and x.mediatype == self.mediatype \
                                                   and getattr(x, dow) == True       \
                                                   and x.dispatch == True            \
                                                   and not x.stop == True            \
                                                   and x.start_time <= now <= x.stop_time ]
        except Exception as e:
            self.logger.warning('failed to create recipient list: {}'.format(e))

        # override RX list with only 'testing=True' recipients
        if os.getenv('TESTING'):
            rx = [r for r in rx if r.testing == True]
            #self.logger.debug(rx)

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
