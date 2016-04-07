#!/usr/bin/env python3

'''
This is bpf-watchdog.py, a very simple watchdog that resets whenever packets matching the
BPF are seen
'''

import sys, pcap, threading, datetime, logging, time
from . import packet_dissector

def startup(config, eventmanager):
    # establish our configuration and start running. our parent is pretty clueless about us,
    # we need to do all the knob twisting here and report back to our parent
    __name = __name__[8:]

    logger = logging.getLogger(__name)

    threads = []
    
    if not __name in config:
        logger.info('no configuration section for {}'.format(__name))
        return None

    section = config[__name]
    # get watchdogs
    if not 'watchdogs' in section:
        logger.info('no watchdogs in configuration section for {}'.format(__name))
        return None

    wdlist = [x.strip() for x in section.get('watchdogs').split('#',1)[0].strip(' ').split(',') if x]
    if not wdlist:
        logger.warn('no handlers defined for {}'.format(__name))
        return

    for __w in wdlist:
        cf = __name+'.'+__w
        try:
            _wconfig = config[cf]
        except:
            logger.warn('config section for {!r} needs to be named {!r}'.format(__w,cf))

        interface = _wconfig.get('interface')
        bpf       = _wconfig.get('bpf')
        warning   = _wconfig.get('warning').split('#')[0].strip()
        critical  = _wconfig.get('critical').split('#')[0].strip()

        p = Watchdog(logger, interface, bpf, warning, critical)
        th = threading.Thread(target=p.run, name='Watchdog.{}'.format(__w))
        th.start()
        threads.append({th:p})
        logger.info('{} started'.format(__w))

    return threads



class Watchdog(object):
    def __init__(self, logger, interface=None, bpf=None, warning=None, critical=None):
        self.logger    = logger
        self.interface = interface
        self.bpf       = bpf
        self.lastevent = None       # updated everytime a packet is seen matching the filter
        self.online    = False      # set True when bpf program is attached and running

        self.handle    = pcap.pcapObject()
        self._shutdown = threading.Event()

        self.lastevent = datetime.datetime.utcnow()
        self.warning   = datetime.timedelta(seconds=int(warning, 10))
        self.critical  = datetime.timedelta(seconds=int(critical, 10))

        self.status    = 'OK'
        self.status_ts = self.lastevent
        
        self.period = self.warning / 1.1 # sleep for 90% of warning period. this way we should always get at
                                         # least one packet read attempt before $warning

    def start_handle(self):
        logger = self.logger
        handle = self.handle
        self.online = False

        try:
            if handle:
                handle.close()
        except:
            pass

        try:
            handle.open_live(self.interface, 100, 1, 5)   # 5ms timeout, we can't dally here or we can lose packets
            handle.setfilter(self.bpf, 0, 0)              # in the bpf-payload handler
            self.online = True
            logger.info('bpf program set on {}'.format(self.interface))
        except:
            t,v,tb = sys.exc_info()
            logger.warn('unable to set bpf program on {}: {}'.format(self.interface, v))
            time.sleep(1)


    def shutdown(self):
        self._shutdown.set()


    def run(self):
        handle = self.handle
        logger = self.logger

        while not self._shutdown.is_set():
            #logger.debug('still running')
            if not self.online:
                self.start_handle()
                continue

            """
             1  the packet was read without problems
             0  packets are being read from a live capture, and the timeout expired
            -1  an error occurred while reading the packet (we get an exception instead of -1)
            -2  packets are being read from a savefile, and there are no more packets to read from the savefile
            """

            # we don't care at all what the packet is, we just want to see a packet
            # neither do we care if we catch all of them. this means we can sleep between
            # packets all we want.
            try:
                (rval, hlen, caplen, timestamp, packet) = handle.next_ex()
            except:
                t,v,tb = sys.exc_info()
                self.warn('some bad foo, restarting watchdog: {}'.format(v))
                self.online = False
            
            now = datetime.datetime.utcnow()

            # timeout expired
            if rval == 0:
                if self.lastevent + self.critical < now:
                    # set state to critical
                    if not self.status == 'CRITICAL':
                        logger.warn('watchdog hit critical age')
                        self.status    = 'CRITICAL'
                        self.status_ts = now

                elif self.lastevent + self.warning < now:
                    # set state to warning
                    if not self.status == 'WARNING':
                        logger.warn('watchdog hit warning age')
                        self.status    = 'WARNING'
                        self.status_ts = now
                
                else:
                    # timeout expired without reading a packet
                    pass

            elif rval == 1:
                # purge all current packets
                if not self.status == 'OK':
                    logger.info('watchdog reset on value {}, last status was {} for {}'.format(rval, self.status,
                        now - self.status_ts))
                    P = packet_dissector.Packet(caplen, packet)
                    logger.debug('{}'.format(P))
                    self.status    = 'OK'
                    self.status_ts = now
                while True:
                    x = handle.next_ex()
                    if x and not x[0]:
                        break
                self.lastevent = now
            
            else:
                logger.warn('unexpected value: {}'.format(rval))
            
            # yield
            waketime = now + self.period
            #logger.debug('sleeping about {} seconds'.format(int((waketime-now).total_seconds())))
            while datetime.datetime.utcnow() < waketime:
                if self._shutdown.is_set():
                    break
                time.sleep(1)
