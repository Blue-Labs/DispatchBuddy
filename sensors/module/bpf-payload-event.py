#!/usr/bin/env python3

import os, sys, pcap, threading, datetime, logging, prctl, ctypes, time, struct, subprocess
from . import packet_dissector
from api.event_tcp_packet_sort import event_tcp_packet_sort
from enum import Enum


class TCP_PHASE(Enum):
    # this should go into a helpers module
    SYN,SYN_ACK,ESTABLISHED,RST,FIN_WAIT1,FIN_WAIT2,CLOSING,TIME_WAIT,CLOSED = range(9)

    def __str__(self):
        return self.name

def tcp_phase_test(oldphase, tcpflags):
    ''' test the new tcp flags with the old phase state
    return the new phase state matching a valid expected tcpflags
    we don't deal with anything fancy yet
    '''
    if oldphase is None:
        if tcpflags.SYN and not tcpflags.ACK:
            return TCP_PHASE.SYN
    elif oldphase == TCP_PHASE.SYN:
        if tcpflags.SYN and tcpflags.ACK:
            return TCP_PHASE.SYN_ACK
    elif oldphase == TCP_PHASE.SYN_ACK:
        if not tcpflags.SYN and tcpflags.ACK:
            return TCP_PHASE.ESTABLISHED
    elif oldphase == TCP_PHASE.ESTABLISHED and tcpflags.FIN and tcpflags.ACK: # fast close
        return TCP_PHASE.TIME_WAIT
    elif oldphase == TCP_PHASE.ESTABLISHED and tcpflags.FIN:
        return TCP_PHASE.FIN_WAIT1
    elif oldphase == TCP_PHASE.FIN_WAIT1 and tcpflags.FIN:
        return TCP_PHASE.CLOSING
    elif oldphase == TCP_PHASE.FIN_WAIT1 and tcpflags.ACK:
        return TCP_PHASE.FIN_WAIT2
    elif oldphase == TCP_PHASE.FIN_WAIT2 and tcpflags.FIN:
        return TCP_PHASE.TIME_WAIT
    elif oldphase == TCP_PHASE.CLOSING and tcpflags.ACK:
        return TCP_PHASE.TIME_WAIT
    elif tcpflags.RST:
        return None
    # unexpected flag state? note, we're not handling any dupe/retransmitted packets here
    return False


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
    if not 'handlers' in section:
        logger.info('no handlers in configuration section for {}'.format(__name))
        return None

    wdlist = [x.strip() for x in section.get('handlers').split('#',1)[0].strip(' ').split(',') if x]
    if not wdlist:
        logger.warn('no handlers defined for {}'.format(__name))
        return

    for __w in wdlist:
        logger.debug('configuring handler: {}'.format(__w))
        cf = __name+'.'+__w
        try:
            _wconfig = config[cf]
        except:
            logger.warn('config section for {!r} needs to be named {!r}'.format(__w,cf))

        p = EventHandler(logger, _wconfig, eventmanager, cf)
        logger.debug('handler "{}" instantiated, starting thread'.format(__w))
        th = threading.Thread(target=p.run, name='Sensor.{}'.format(__w))
        th.start()
        threads.append({th:p})
        logger.info('{} handler started'.format(__w))

    return threads


class EventHandler(object):
    def __init__(self, logger, config, eventmanager, _name):
        self.logger        = logger
        self.lastevent     = None           # updated everytime a packet is seen matching the filter
        self.online        = False          # set True when bpf program is attached and running
        self.status        = 'OK'

        self.handle        = pcap.pcapObject()
        self.lastevent     = datetime.datetime.utcnow()
        self._shutdown     = threading.Event()
        
        self.eventmanager  = eventmanager

        # get the eventgroup for packets
        self.eventgroup    = eventmanager.new('packet') # list of event objects (stream of packets)

        for attr in ['interface','bpf','source','spin timeout','stall timeout','abort timeout']:
            try:
                v = config.get(attr).split('#',1)[0].strip(' ')
                k = attr.replace(' ','_')
                setattr(self, k, v)
            except:
                setattr(self, k, None)
                if attr in ['interface','bpf']:
                    logger.warn('config verb {} is required for {}'.format(attr,_name))


    def shutdown(self):
        self._shutdown.set()


    def set_filter(self):
        logger = self.logger
        handle = self.handle
        self.online = False
        logger.info('============== set_filter()')

        try:
            if handle:
                handle.close()
        except:
            pass

        try:
            # make sure reorder_hdr flag is set off vlan200, if not, we'll lose almost 1/2 of our packets
            # ~$ cat /proc/net/vlan/vlan200
            #  vlan200  VID: 200        REORDER_HDR: 0  dev->priv_flags: 4001
            #           total frames received        28202
            # [...]
            
            subprocess.call(['/usr/bin/vconfig','set_flag','vlan200','1','0'])
        
            if True:
                logger.info("doing open_live()")
                handle.open_live(self.interface, 9000, 1, 10)  # here we can dally plenty as our watchdog delay period is
                                                               # measured in dozens of seconds vs. microseconds
            else:
                handle.create(self.interface)
                handle.set_snaplen(9000)
                handle.set_buffer_size(2097152)
                handle.set_promisc(1)
                handle.set_timeout(5)
                handle.activate()

            handle.setfilter(self.bpf, 1, 0)

            self.online = True
            logger.info('bpf program set on {}: {}'.format(self.interface, self.bpf))
        except:
            t,v,tb = sys.exc_info()
            logger.warn('unable to set bpf program "{}" on {}: {}'.format(self.bpf, self.interface, v))
            raise
            time.sleep(10)


    def run(self):
        handle   = self.handle
        logger   = self.logger
        
        '''
        # wtf. python's built in os.sched_setscheduler() just does not do SCHED_FIFO any more. even
        # with caps set, it still returns EPERM
        
        # turns out systemd's logind puts ssh users into a cgroup that can't do RT.
        # move your shell to the default cpu group with:
        #    cgclassify -g cpu:/ $$
        # then you can do things like the following.
        
        
        # do it with C instead
        c = ctypes.cdll.LoadLibrary('libc.so.6')
        SCHED_FIFO = 1
        class _SchedParams(ctypes.Structure):
            _fields_ = [('sched_priority', ctypes.c_int)]
        schedParams = _SchedParams()
        schedParams.sched_priority = c.sched_get_priority_max(SCHED_FIFO)
        err = c.sched_setscheduler(0, SCHED_FIFO, ctypes.byref(schedParams))
        if err:
            logger.critical('oh shit stains, couldn\'t set scheduler priority: {}'.format(err))

        '''
        sp_max = os.sched_get_priority_max(os.SCHED_FIFO) # see 'man sched_setscheduler',
        sp     = os.sched_param(99)                       # range 1 (low) - 99 (high), for FIFO in linux
        try:
            os.sched_setscheduler(0, os.SCHED_FIFO, sp)   # FIFO or RR are real-time policies (linux)
        except PermissionError:
            logger.critical('prctl.capbset.sys_nice  ={}'.format(prctl.capbset.sys_nice))
            logger.critical('prctl.capbset.net_admin ={}'.format(prctl.capbset.net_admin))
            logger.critical('prctl.capbset.net_raw   ={}'.format(prctl.capbset.net_raw))
            logger.critical('[1;31mPermission denied[0m. Does the python binary have cap_net_raw,cap_sys_nice,cap_net_admin=eip? or do you need to run this? cgclassify -g cpu:/ $$')
            quit
        
        #os.setpriority(os.PRIO_PROCESS, 0, -20)
        in_process = False

        while not self._shutdown.is_set():
            if not self.online:
                self.set_filter()
                continue

            """
             1  the packet was read without problems
             0  packets are being read from a live capture, and the timeout expired
            -1  an error occurred while reading the packet (we get an exception instead of -1)
            -2  packets are being read from a savefile, and there are no more packets to read from the savefile
            """

            try:
                (rval, hlen, caplen, timestamp, packet) = handle.next_ex()
            except:
                t,v,tb = sys.exc_info()
                self.warning('some bad foo, restarting capture: {}'.format(v))
                self.online = False
            
            # timeout expired
            if rval == 0:
                # this should go in the EventManger
                # every 1 second, check the events queue for stalled or 2MSL
                # that need to be pushed to the API
                #self.events_scan()

                continue
            
            if not rval == 1:
                logger.critical('unexpected rval: {}'.format(rval))

            self.lastevent = datetime.datetime.utcnow()
            ev = self.process_packet(hlen, caplen, timestamp, packet)
            if ev:
                # TCP conversation is finished
                logger.info('event {} ready for payload processing; hand off to queue'.format(ev.uuid))
                # tell event manager to pop its cherry
                self.eventmanager.pending.set()
                logger.info('finish pcap_stats({})'.format(handle.stats()))

                # let celery run for a bit after we got our dispatch. 'tis sad that
                # python's thread model makes things synchronous due to the big GIL lock
                time.sleep(1)

    def write_pcap_file(self, fname, packet, snaplen, ts):
        try:
            os.stat(fname)
        except:
            # write the header on new file
            with open(fname, 'ab') as f:
                f.write(b'\xd4\xc3\xb2\xa1') # magic number
                f.write(b'\x02\x00\x04\x00') # major/minor version
                f.write(b'\x00\x00\x00\x00') # time zone offset, always zero
                f.write(b'\x00\x00\x00\x00') # accuracy of timestamps, always zero
                f.write(b'(#\x00\x00')       # snapshot length, always 9000 in DB
                f.write(b'\x01\x00\x00\x00') # link layer header type

        with open(fname, 'ab') as f:
            # write per-packet header
            f.write(struct.pack('<I', int(ts)))     # timestamp, seconds since unix epoch
            f.write(struct.pack('<I', int((ts - int(ts))* 10000001))) # ..and microseconds
            f.write(struct.pack('<I', snaplen))     # snaplen, length of actual captured data
            f.write(struct.pack('<I', len(packet))) # length of packet on wire
            f.write(packet)


    def process_packet(self, hlen, snaplen, timestamp, packet):
        dupe    = False
        damaged = False
        logger  = self.logger
        P       = packet_dissector.Packet(snaplen, packet)
        
        if False:
            for k,v in P.items(highlight=['IP.id','TCP.SEQ#']):
                if isinstance(v, dict):
                    vs = ', '.join(['{}={}'.format(_k,_v) for _k,_v in v.items()])
                else:
                    vs = v
                logger.debug('{:<12} {{{}}}'.format(k,vs))
            print()

        id = (P.ip.src,P.tcp.sport),(P.ip.dst,P.tcp.dport)
        if id in self.eventgroup:
            ev = self.eventgroup.get(id)
            
            # if reawakening a dead conversation, cleanse the filth
            if ev.stall_forced:
                ev.full         = False
                ev.stall_forced = False
                ev.stall_death  = None
                ev.origin_ts    = datetime.datetime.utcnow()
                ev.lastevent_ts = ev.origin_ts
            
            # verbosity
            if True:
                logger.debug('{:>3} {}:{} ─▶ {}:{}, id:0x{:04x} {}'.format(
                    len(ev.collection)+len(ev.damaged)+len(ev.dupes)+1,
                    P.ip.src,P.tcp.sport,
                    P.ip.dst,P.tcp.dport,
                    P.ip.id,
                    P.tcp.flags
                    ))

            # store dupes in ev.dupes
            # if the original of a dupe is in ev.damaged, replace the damaged pkt instead of marking this one as a dupe
            al = P.ip.header_length + P.tcp.header_length + len(P.payload)
            if al < P.ip.total_length:
                logger.warning('packet should be {} bytes, it is actually {} bytes'.format(P.ip.total_length, al))
                damaged = True
            
            # check if it's a duplicate packet that we already received using the IP id and TCP seq#/ack#
            dupes = [_p for unit_id,_p in ev.collection
                    if _p.ip.src == P.ip.src
                        and _p.ip.id == P.ip.id
                        and _p.tcp.sequence_number == P.tcp.sequence_number
                        and _p.tcp.acknowledgement_number == P.tcp.acknowledgement_number
                        and _p.tcp.flags == P.tcp.flags ]

            if dupes:
                _p = dupes[0] # just show the first instance of all dupes
                logger.debug('   └──▶ duplicate') # of IP/id:0x{:04x} T/seq:0x{:08x} T/ack:0x{:08x}'.format(len(ev.collection), _p.ip.id, _p.tcp.sequence_number, _p.tcp.acknowledgement_number))
                dupe = True
            
            # set the dst initial id?
            if ev.valid_startup is None and P.ip.dst == self.source and P.tcp.flags.SYN and P.tcp.flags.ACK:
                ev.dst_initial_id = P.tcp.sequence_number
        else:
            logger.debug('  1 {}:{} ─▶ {}:{}'.format(P.ip.src,P.tcp.sport,P.ip.dst,P.tcp.dport))
            ev = self.eventgroup.new(id, self.source)
            # we set the rhs upon receipt of syn
            ev.src_initial_id = P.tcp.sequence_number
        
        self.write_pcap_file('/var/db/DispatchBuddy/pcap/{}.pcap'.format(ev.uuid), packet, snaplen, timestamp)

        # IP sequences perfectly for retransmits.
        # use the TCP seq number which goes back
        ev.lastevent_ts = datetime.datetime.utcnow()
        ev.raw_collection.append(P)
        if damaged:
            ev.damaged.append(P)
            return
        elif dupe:
            ev.dupes.append(P)
            return
        else:
            ev.add( (P.ip.id,P.tcp.sequence_number,P.tcp.acknowledgement_number), P )

        # is this a new connection?
        if ev.valid_startup is None:
            ev.tcp_phase = tcp_phase_test(ev.tcp_phase, P.tcp.flags)
            print('ev.tcp_phase set to: {}'.format(ev.tcp_phase))
            if ev.tcp_phase is False:
                # unexpected plans, log trail
                #logger.warning(P.tcp.flags)
                logger.warning('unexpected TCP flag state, showing packets playback and dropping event')
                for unit_id, p in ev:
                    logger.warning('IP id: 0x{:04x} TCPseqn 0x{:08x} {}▶{} ⊳ {}'.format(p.ip.id,p.tcp.sequence_number,p.ip.src,p.ip.dst,p.tcp.flags))
                del ev
                return

            # ready to go
            if ev.tcp_phase == TCP_PHASE.ESTABLISHED:
                ev.valid_startup = True
                # enable event group queue scanner that we have a valid inbound connection
                logger.info('session established, triggering EVQ')
                self.eventmanager.pending.set()


        elif P.tcp.flags.FIN or P.tcp.flags.RST or ev.finishing:
            ev.finishing = True
            prev_phase = ev.tcp_phase
            ev.tcp_phase = tcp_phase_test(ev.tcp_phase, P.tcp.flags)
            if not ev.tcp_phase == prev_phase:
                logger.debug('  tcp phase: {} ──▶ {}'.format(prev_phase, ev.tcp_phase))

            if ev.tcp_phase in (TCP_PHASE.CLOSING,TCP_PHASE.TIME_WAIT,TCP_PHASE.CLOSED):
                # bear in mind that this is an active close. we may
                # encounter a passive close after first FIN. we can
                # then consider the connection closed after 2*MSL
                # MSL, maximum segment lifetime, is two minutes. 
                # therefore, 4 minutes.
                
                # wrt. DispatchBuddy, we don't at all care if the local socket remains 1/2 dead for 2MSL
                # we'll trigger EVQ as soon as we enter a closing phase
                
                logger.info('packet collection finished, reconstructing')
                event_tcp_packet_sort(ev) # sorts packets and extracts payload
                ev.full = True
                logger.debug('sorted {} bytes in payload'.format(len(ev.payload)))
                return ev


    # this is sort of deprecated, almost handled in the event manager but not with 2MSL
    def events_scan(self):
        logger = self.logger
        for tid,ev in self.eventgroup.items():
            # if any events are 2MSL, mark them as full
            if not ev.full and ev.lastevent_ts +datetime.timedelta(seconds=240) < datetime.datetime.utcnow():
                logger.info('setting event {}:{} to finished & full state, 2MSL reached'.format(tid,ev.origin_ts))
                ev.full = True

            # if any events are in full state, move them to the api push queue
            #if ev.full:
            #    logger.info('moving event {}:{} to API push queue'.format(tid,ev.origin_ts))

            # if any events are in corrupted/RST state, store on disk for analysis and send david an alert
            if ev.tcp_phase == TCP_PHASE.RST:
                logger.info('event {}:{} appears to be aborted'.format(tid,ev.origin_ts))


if __name__ == '__main__':
    raise Exception('do not run as individual unit')
