#!/usr/bin/env python

from sensors.module.packet_dissector import Packet
from io import BytesIO

def event_tcp_packet_sort(ev):
    '''sort a list of packets by calculating each segment and placing it correctly
    
    fetch the first packet to identify the source,
    extract all packets matching that source
    organize by tcp sequence id
    
    '''
    
    org_pkt_stream = [p for _,p in ev.collection]
    
    origin = org_pkt_stream[0].ip.src
    
    pcpy = [ p for p in org_pkt_stream if p.ip.src == origin]

    # sort algorithm, as each top-bubble item is found, pop it from the pckt list and push it to the pcpy list
    # find the first packet of SYN
    # then ACK
    # now sort by tcp sequence number
    # remember that tcp seq.num can wrap around
    
    pkts = []
    
    # first the SYN packet
    try:
        _ = [ p for p in pcpy if p.tcp.flags.SYN ][0]
        pkts.append(_)
        pcpy.remove(_)
    except:
        pass

    # next the ACK packet
    try:
        _ = [ p for p in pcpy if p.tcp.flags.ACK and p.tcp.sequence_number == ev.src_initial_id+1 ][0]
        pkts.append(_)
        pcpy.remove(_)
    except:
        pass

    # this presumes we'll NEVER have more than 64K of packets. a typical print job is ~129 packets
    seqns = sorted([ p.tcp.sequence_number for p in pcpy if p.tcp.sequence_number > ev.src_initial_id ])
    seqns += sorted([ p.tcp.sequence_number for p in pcpy if p.tcp.sequence_number < ev.src_initial_id ])
    
    # now finish sorting pcpy -> pkts
    for seq in seqns:
        idx = 0
        while True:
            p = None
            if pcpy[idx].tcp.sequence_number == seq:
                pkts.append(pcpy.pop(idx))
                break
            idx += 1
    
    # now make sure there are no missing segments

    plen  = 0
    seq   = ev.src_initial_id
    bseq  = seq

    # push the initial ids to the front of the list where they belong
    seqns.insert(0, ev.src_initial_id+1)
    #seqns.insert(0, ev.src_initial_id)
    seqns.append(seqns[-1])	#

    # this should be redone to a BytesIO with seek() as fragments may overlap. then we have numerous
    # policies to choose from, which overlap takes priority?
    # also note that when we receive PSH, we should 'cook' our existing BytesIO payload. fragments
    # that come after this should go to a new BytesIO. any fragment data that attempts to overwrite
    # a cooked BytesIO must be dropped. data that is PSH'ed is sent to the application, therefore
    # future fragments can't overwrite data already forwarded to the application
    
    biol      = []	# list of BytesIO objects
    bio       = BytesIO()
    rsub      = [0]
    biolen    = 0
    phantoms  = 0

    for p in pkts:
        if p.tcp.flags.PUSH:
            view = bio.getbuffer()
            if view:
                biol.append(bio)
                bio = BytesIO()
            del(view)

        if p.tcp.sequence_number < bseq: # rollover, emulate PUSH flag
            view = bio.getbuffer()
            if view:
                biol.append(bio)
                bio = BytesIO()
            del(view)
            
            seqo = 0
        
        else:
            seqo = bseq
        
        rseq = p.tcp.sequence_number - seqo

        if p.tcp.flags.PUSH:
            rsub.append(rseq)
        
        # phantom byte t/seq increment actually occurs because of syn/ack being received but
        # we've discarded the client->server reply packets
        if p.tcp.flags.SYN: # allow for the phantom byte at SYN/ACK
            phantoms += 1

        # FIN phantom
        if p.tcp.flags.FIN: # allow for the phantom byte
            phantoms += 1

        nrseq = seqns.pop(0)
        nrseqs = seqns and '{}'.format(nrseq-seqo) or ''

        if False:
            print('0x{:04x} {} tseq={}/{} rseq={}; bio/ph/pload=({}+{}+{}={}) n/rseq {} rsub:{} {}'.format(p.ip.id, p.tcp.flags,
                p.tcp.sequence_number,seqo,
                rseq, biolen, phantoms, len(p.payload),
                biolen+ phantoms+ len(p.payload),
                nrseqs,
                rseq - rsub[-1], rsub))
        
        if len(p.payload):
            bio.seek(rseq-rsub[-1])
            bio.write(p.payload)
            biolen += len(p.payload)
        
    biol.append(bio)
    ev.payload = b''.join([bio.getbuffer() for bio in biol])
