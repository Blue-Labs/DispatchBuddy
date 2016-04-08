#!/usr/bin/env python3

import datetime, uuid, threading, base64
from api.celery import decode_payload_data
from api.event_tcp_packet_sort import event_tcp_packet_sort

# keep in mind that this will be refactored to handle any type of event
# such as the gamewell system and will be moved into its own module
event_types = {'packet'}

import sys, linecache
def traceit(frame, event, arg):
    if event == "line":
        lineno = frame.f_lineno
        filename = '__file__' in frame.f_globals and frame.f_globals["__file__"] or '---'
        if (filename.endswith(".pyc") or
            filename.endswith(".pyo")):
            filename = filename[:-1]
        name = frame.f_globals["__name__"]
        line = linecache.getline(filename, lineno)
        print ("%s:%s: %s" % (name, lineno, line.rstrip()))
    return traceit


class EventManager:
    threads     = []
    eventgroups = {}


    def __init__(self, logger, config):
        self.logger = logger
        self.config = config
    
        # set up a thread event button
        self.pending   = threading.Event()
        self._shutdown = threading.Event()
        
        em = threading.Thread(target=self.scan_evg_queue, name="Event Manager")
        em.start()
        
        self.threads.append(em)


    def shutdown(self):
        self._shutdown.set()
        self.pending.set()
        
    
    def new(self, ev_type):
        if not ev_type in self.eventgroups:
            eg = EventGroup(ev_type)
            self.eventgroups[ev_type] = eg
        else:
            eg = self.eventgroups[ev_type]
        return eg

    
    def scan_evg_queue(self):
        holdoff = 1
        config = self.config
        
        # this should get its own config section?
        section       = config['module.bpf-payload-event.dispatches']
        intra_stall   = int(section.get('intra-stall timeout').split('#')[0].strip())
        overall_stall = int(section.get('overall-stall timeout').split('#')[0].strip())
        abort         = int(section.get('abort timeout').split('#')[0].strip())
        
        while True:
            self.pending.wait(holdoff)
            self.pending.clear()
            
            if self._shutdown.is_set(): # leave unfinished tasks in the queue? eep. we will lose them :)
                return
            
            #self.logger.debug('scan queue for readied events')
            
            for evgtype in self.eventgroups:
                if not len(self.eventgroups[evgtype]):
                    continue

                self.eventgroups[evgtype].locked.acquire()
                for evid in self.eventgroups[evgtype].events:
                    event = self.eventgroups[evgtype].events[evid]

                    if event.full == True and not event.queueid and not event.stall_death:
                        # this is NOT the place to do event specific decoding, what we
                        # are doing here is converting it to base64
                        self.logger.debug('event {} payload has {} bytes'.format(event.uuid, len(event.payload)))
                        payload       = base64.b64encode(event.payload).decode()
                        self.logger.debug('event {} payload encoded to {} bytes'.format(event.uuid, len(payload)))
                        event.queueid = decode_payload_data.delay(event.uuid, payload)

                        if event.stall_forced:
                            event.stall_death  = now + datetime.timedelta(minutes=30)

                        continue

                    if not event.queueid and not event.stall_death:
                        now             = datetime.datetime.utcnow()

                        intra_stall_t   = event.lastevent_ts + datetime.timedelta(seconds=intra_stall)
                        overall_stall_t = event.origin_ts    + datetime.timedelta(seconds=overall_stall)
                        abort_t         = event.origin_ts    + datetime.timedelta(seconds=overall_stall+abort)

                        if abort_t < now:  # we deal with slow packets but have a hard deadline from start of
                            c = len(event.collection)# the conversation
                            self.logger.warning('forcing dispatch of slow or stalled event with {} packets, {}'.format(c, event.uuid))
                        
                            # try to decode it anyway, our next pass into the queue scanner will pop its cherry
                            event.full = True
                            event.stall_forced = True
                            
                            # this should really call a callback like event.sort_payload() which does this
                            # EVQ should be agnostic about event data types
                            #event_tcp_packet_sort(event)
                            event.sort()

                        elif overall_stall_t < now:    # if our original flow ts is really old...
                            if intra_stall_t < now: # if we haven't gotten another packet in a while...
                                self.logger.warning('stalled flow {}'.format(event.uuid))
                        
                self.eventgroups[evgtype].locked.release()
                        
            
            # check for items that can be removed from the queue
            dels = []
            pending = 0
            for evgtype in self.eventgroups:
                if not len(self.eventgroups[evgtype]):
                    #self.logger.debug('skipping EVG {}'.format(evgtype))
                    continue

                self.eventgroups[evgtype].locked.acquire()
                for evid in self.eventgroups[evgtype].events:
                    pending += 1
                    event = self.eventgroups[evgtype].events[evid]
                    #self.logger.debug('found event {} with {} in collection'.format(evid, len(event.collection)))
                    if event.queueid:
                        self.logger.debug('{} is in state {}'.format(event.uuid, event.queueid.state))
                    if event.queueid and event.queueid.ready():
                        if not event.stall_forced:
                            self.logger.debug('{} is done, can be purged from queue'.format(event.uuid))
                            dels.append(evid)
                        else:
                            if event.stall_death > now:
                                self.logger.warning('keeping {} around in case more packets come'.format(event.uuid))
                                event.queueid = None
                            else:
                                self.logger.warning('{} dying of old age'.format(event.uuid))
                                dels.append(evid)
                for evid in dels:
                    pending -= 1
                    del self.eventgroups[evgtype].events[evid]
                self.eventgroups[evgtype].locked.release()

            # if any events remain in our queue, set our rescan holdoff to 1 second, otherwise 15minutes
            holdoff = pending and 1 or None


class EventGroup(object):
    class event(object):
        def __init__(self):
            self.origin_ts       = datetime.datetime.utcnow()
            self.lastevent_ts    = self.origin_ts
            self.raw_collection  = []       # all packets, including damaged or dupes. we collect all packets, even dups, in case
                                            # we intend to replay for analysis
            self.collection      = []       # list of packets or other objects to be organized according to event type, aka for
                                            # packets it would be their sequence number
            self.damaged         = []       # units that are damaged go here instead of self.collection
            self.dupes           = []       # duplicate units go here

            self.payload         = None     # proper order packet payload
            self.valid_startup   = None     # True if a valid tcp syn/ack conversation created this event
            self.tcp_phase       = None     # {SYN,SYN_ACK,EST,FIN,RST}
            self.type            = None     # chosen from event_types
            self.lhs             = None     # when needing to identify a bidirectional component, set this to the ID of one side

            self.finishing       = False    # set when FIN or RST is seen
            self.full            = False    # set to True when the particular <packet> collection is finished and this event
                                            # can be pushed to the queue/API

            self.src_initial_id  = None     # in the case of TCP packets, set the initially relative sequence pair (sender & receiver)
            self.dst_initial_id  = None     # in the case of TCP packets, set the initially relative sequence pair (sender & receiver)
            self.identifier      = None     # method of associating a new <packet> with a known event object, in the case of
                                            # packets, a tuple of ((srcip,sport),(dstip,dport))
            self.uuid            = str(uuid.uuid4()) # UUID to pass around amqp tasks and maintain track
            self.queueid         = False
            self.stall_forced    = False    # if prematurely dispatched, this will tell us to keep it around for a while in
            self.stall_death     = None     # case more data eventually comes and we get a better dispatch message

        def add(self, unit_id, element):
            #print('adding collection element: {}'.format(id))
            #print('{}'.format(element.ip))
            self.collection.append( (unit_id, element) )

        def __iter__(self):
            #srcseq = [e.tcp.sequence_number for tid,e in self.collection if e.ip.src == self.lhs ]
            #dstseq = [e.tcp.sequence_number for tid,e in self.collection if not e.ip.src == self.lhs ]
            #print('srcseq: {}'.format(srcseq))
            #print('dstseq: {}'.format(dstseq))
            for c in self.collection:
                yield c

        def items(self):
            # always return the packets in sorted order (these are sorted by sequence number)
            #srcseq = [e.tcp.sequence_number for tid,e in self.collection if e.ip.src == self.lhs ]
            #dstseq = [e.tcp.sequence_number for tid,e in self.collection if not e.ip.src == self.lhs ]
            #print('srcseq: {}'.format(srcseq))
            #print('dstseq: {}'.format(dstseq))
            #for c in sorted(self.collection.items(), key=itertools.itemgetter('tcp.sequence_number')):
            #    yield c
            pass
        
        def sort(self):
            # sort our payload
            event_tcp_packet_sort(self)

    def __init__(self, event_group_type):
        self.events     = {}                        # events are identified by a uuid:[]
                                                    # events are expected to be sortable by their identifier tuple
        self.event_group_type = event_group_type    # event collections will be grouped by type
        self.locked     = threading.Lock()


    def __len__(self):
        return len(self.events)


    def __contains__(self, id):
        #for tuple_id,_event in self.events:
        #    print('<Event({},{})>'.format(tuple_id,_event))

        if [ tuple_id for tuple_id,_event in self.events.items() if _event.identifier == id]:
            return True

        #print('making a new event series for {}'.format(id))

        # hardwired for packets right now :(
        if self.event_group_type == 'packet':
            # flip src,dst and check again
            if isinstance(id, tuple):
                id = id[::-1] # reverse the src,dst tuple
                if [ tuple_id for tuple_id,_event in self.events.items() if _event.identifier == id]:
                    return True
        return False

    def __iter__(self):
        for e in self.events:
            yield e

    def items(self):
        for e in self.events.items():
            yield e

    def get(self, id):
        if not id in self.events:
            id = id[::-1] # why are we doing this? it reverses the src,dst tuple
        return self.events[id]

    def new(self, id, lhs):
        self.locked.acquire()
        ev              = self.event()
        ev.lhs          = lhs
        ev.type         = self.event_group_type
        ev.identifier   = id
        self.events[id] = ev
        self.locked.release()
        return ev

