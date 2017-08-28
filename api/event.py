#!/usr/bin/env python3

import datetime
import uuid
import threading
import traceback
import base64
import dill
import json
import trace
import logging
import sys
import time
from api.celery import celery_config
from api.celery import dispatch_job # DispatchBuddyTask
from api.event_tcp_packet_sort import event_tcp_packet_sort
#from sensors.module.bpf_payload_event import TCP_PHASE
#from memory_profiler import profile
#from memory_profiler import LogFile
#import sys
#sys.stdout = LogFile('event.profile')
#import objgraph

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
        __name = self.__class__.__name__
        logger = logging.getLogger(__name)

        self.logger = logger
        self.config = config

        try:
            self.worker = dispatch_job #DispatchBuddyTask()
            #'''
            #self.worker.app.conf.update(celery_config)
            #self.worker.ignore_result = False

            print('worker.app.conf')
            for k,v in sorted(self.worker.app.conf.items()):
                print('   {:<40} {}'.format(k,v))
            '''
            print('worker')
            for k in sorted([k for k in dir(self.worker) if not k.startswith('_')]):
                if k == 'from_config':
                    continue
                v = getattr(self.worker, k)
                print('   {:<40} {}'.format(k, v))
            print('worker.from_config')
            for k,v in sorted(self.worker.from_config):
                print('   {:<40} {}'.format(k,v))
            '''

        except Exception as e:
            traceback.print_exc()

        logger.info('Starting Event Manager thread')


        # set up a thread event button
        self.pending   = threading.Event()
        self._shutdown = threading.Event()

        em = threading.Thread(target=self.scan_evg_queue, name="Event Manager")
        em.start()

        self.threads.append(em)

        #self.load_evg_state()


    def load_evg_state(self):
        try:
            self.logger.debug('loading evg state')
            with open('/var/db/DispatchBuddy/tmp/dispatchbuddy_evgroups.pkl', 'rb') as f:
                self.eventgroups = dill.loads(f.read())
            self.logger.debug('evg state loaded')
        except EOFError:
            pass # ignore empty files
        except Exception as e:
            traceback.print_exc()


    def save_evg_state(self):
        try:
            self.logger.debug('saving evg state')
            with open('/var/db/DispatchBuddy/tmp/dispatchbuddy_evgroups.pkl', 'wb') as f:
                f.write(dill.dumps(self.eventgroups))
            self.logger.debug('evg state saved')
        except Exception as e:
            self.logger.critical('Failed to save evg state: {}'.format(e))
            self.logger.critical('{}'.format(traceback.format_exc()))


    def shutdown(self):
        self._shutdown.set()
        self.pending.set()

        # ensure all locks are released
        for evgtype in self.eventgroups:
            try:
                self.eventgroups[evgtype].locked.release()
            except RuntimeError:
                self.logger.warning('event in {} is still locked, ignoring'.format(evgtype))


    def new(self, ev_type):
        if not ev_type in self.eventgroups:
            eg = EventGroup(ev_type)
            self.eventgroups[ev_type] = eg
        else:
            eg = self.eventgroups[ev_type]
        return eg


    #@profile
    def scan_evg_queue(self):
        holdoff = 10.0
        expected = 0
        config = self.config

        # this should get its own config section?
        section       = config['module.bpf_payload_event.dispatches']
        intra_stall   = int(section.get('intra-stall timeout').split('#')[0].strip())
        overall_stall = int(section.get('overall-stall timeout').split('#')[0].strip())
        abort         = int(section.get('abort timeout').split('#')[0].strip())

        while True:
            #objgraph.show_refs([self.eventgroups], filename='/tmp/eventgroups.png')

            try:
                self.pending.wait(timeout=holdoff)
                if self.pending.is_set():
                    self.pending.clear()
            except Exception as e:
                self.logger.critical('pending error: {}'.format(e))

            if self._shutdown.is_set(): # leave unfinished tasks in the queue? eep. we will lose them :)
                # uh oh, let's serialize this and pick up where we left off?
                self.logger.debug('shutdown seen, saving evg state')
                save_evg = False
                for evgtype in self.eventgroups:
                    if self.eventgroups[evgtype].events:
                        save_evg = True
                        self.logger.warning('{} objects left in event queue [{}] at shutdown'
                            .format(len(self.eventgroups[evgtype].events), evgtype))

                # always save, even empties
                return

            now             = datetime.datetime.utcnow()

            for evgtype in self.eventgroups:
                if not len(self.eventgroups[evgtype]):
                    continue

                for evid in self.eventgroups[evgtype].events:
                    event = self.eventgroups[evgtype].events[evid]
                    self.logger.debug('checking {}'.format(event.uuid))

                    if event.full == True and not event.celery_task and not event.stall_death:
                        # store a copy of it inside here in case celery task breaks
                        with open('/var/db/DispatchBuddy/evdata/event-{}.evdata'.format(event.uuid), 'wb') as f:
                            f.write(event.payload)

                        # this is NOT the place to do event specific decoding, what we
                        # are doing here is converting it to base64
                        payload       = base64.b64encode(event.payload).decode()
                        self.logger.info('{} payload raw {:,}b, encoded to b64 {:,}b'.format(event.uuid, len(event.payload), len(payload)))

                        # this will deadlock on .locked() easily, we need a better way of reading all
                        # items in the queue without chance of deadlocking
                        #self.save_evg_state()

                        event.celery_task = self.worker.apply_async((event.uuid, payload), task_id=event.uuid)
                        #print('task: {}'.format(dir(event.celery_task)))
                        #for k in sorted([k for k in dir(event.celery_task) if not k.startswith('_')]):
                        #        try:
                        #            v = getattr(event.celery_task, k)
                        #            print('   {:<40} {}'.format(k, v))
                        #        except:
                        #            pass

                        self.logger.info('{} queue status: {}'.format(event.uuid, event.celery_task.state))

                        if event.stall_forced:
                            event.stall_death  = now + datetime.timedelta(minutes=30)

                        expected += 1

                    if not event.celery_task and not event.stall_death:
                        self.logger.info('checking event stall')

                        intra_stall_t   = event.lastevent_ts + datetime.timedelta(seconds=intra_stall)
                        overall_stall_t = event.origin_ts    + datetime.timedelta(seconds=overall_stall)
                        abort_t         = event.origin_ts    + datetime.timedelta(seconds=overall_stall+abort)

                        if abort_t < now:  # we deal with slow packets but have a hard deadline from start of
                            c = len(event.collection)# the conversation
                            self.logger.warning('{} forcing dispatch of slow or stalled event with {} packets'.format(event.uuid, c))

                            # try to decode it anyway, our next pass into the queue scanner will pop its cherry
                            event.full = True
                            event.stall_forced = True

                            # this should really call a callback like event.sort_payload() which does this
                            # EVQ should be agnostic about event data types
                            event.sort()

                        elif overall_stall_t < now:    # if our original flow ts is really old...
                            if intra_stall_t < now: # if we haven't gotten another packet in a while...
                                self.logger.warning('{} is stalled'.format(event.uuid))

                    #if event.celery_task:
                    #    self.logger.info('celery status: {}'.format(event.celery_task.state))

            # check for items that can be removed from the queue
            dels = []
            pending = 0

            for evgtype in self.eventgroups:
                if not len(self.eventgroups[evgtype]):
                    self.logger.debug('skipping EVG {}'.format(evgtype))
                    continue

                self.eventgroups[evgtype].locked.acquire()
                for evid in self.eventgroups[evgtype].events:
                    pending += 1
                    event = self.eventgroups[evgtype].events[evid]
                    self.logger.debug('found event {} with {} in collection'.format(evid, len(event.collection)))
                    if event.celery_task:
                        self.logger.info('{} is {}'.format(event.uuid, event.celery_task.state))
                        #self.logger.info('{} last_ts is {}'.format(event.uuid, event.lastevent_ts))

                        if event.celery_task.ready():
                            if not event.stall_forced: # we'll keep stalled sessions around a bit
                                # is the handler completely done with this?
                                if event.purgeable or now - event.lastevent_ts > datetime.timedelta(seconds=30):
                                    expected -= 1
                                    dels.append(evid)
                            else:
                                if event.stall_death > now:
                                    self.logger.warning('keeping {} around in case more packets come'.format(event.uuid))
                                else:
                                    self.logger.warning('expiring stalled event {}'.format(event.uuid))
                                    expected -= 1
                                    dels.append(evid)

                for evid in dels:
                    pending -= 1
                    self.logger.info('purging {}'.format(self.eventgroups[evgtype].events[evid].uuid))
                    del self.eventgroups[evgtype].events[evid]

                self.eventgroups[evgtype].locked.release()

            # if any events remain in our queue, set our rescan holdoff to 10 seconds, otherwise None
            holdoff = (pending or expected) and 10.0 or None
            self.logger.info('holdoff set to {}'.format(holdoff))


class _Event(object):
    def __new__(cls):
        instance = super(_Event, cls).__new__(_Event)
        return instance

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

        self.purgeable       = False    # let EVM know the handler will never touch this again and it can be purged
        self.finishing       = False    # set when FIN or RST is seen
        self.full            = False    # set to True when the particular <packet> collection is finished and this event
                                        # can be pushed to the queue/API

        self.src_initial_id  = None     # in the case of TCP packets, set the initially relative sequence pair (sender & receiver)
        self.dst_initial_id  = None     # in the case of TCP packets, set the initially relative sequence pair (sender & receiver)
        self.identifier      = None     # method of associating a new <packet> with a known event object, in the case of
                                        # packets, a tuple of ((srcip,sport),(dstip,dport))
        self.uuid            = str(uuid.uuid4()) # UUID to pass around amqp tasks and maintain track
        self.queueid         = False
        self.celery_task     = None
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

    def __str__(self):
        _str = '''    Origin TS: {origin_ts}
Last event TS: {lastevent_ts}
         UUID: {uuid}
  Celery Task: {celery_task}
       Source: {src_initial_id}
  Destination: {dst_initial_id}

'''.format(**self.__dict__)

        for k in ('identifier','tcp_phase','type','stall_forced','stall_death','raw_collection','damaged','dupes',):
            if k.startswith('_'): continue
            if k in ('raw_collection','dupes','damaged'):
                _str += '  {:20} {}\n'.format(k, len(getattr(self, k)))
            else:
                _str += '  {:20} {}\n'.format(k, getattr(self, k))


        return _str

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



class EventGroup(object):
    event = _Event()

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

    def __str__(self):
        _str = 'EventGroup: {}, event items:{}\n'.format(self.event_group_type, len(self.events))

        return _str

    def __getitem__(self, k):
        return self.events[k]


    def items(self):
        #print('do items')
        for e in self.events.items():
            yield e

    def get(self, id):
        if not id in self.events:
            id = id[::-1] # why are we doing this? it reverses the src,dst tuple
        return self.events[id]

    def new(self, id, lhs):
        self.locked.acquire()
        ev              = _Event()
        ev.lhs          = lhs
        ev.type         = self.event_group_type
        ev.identifier   = id
        self.events[id] = ev
        self.locked.release()
        return ev

