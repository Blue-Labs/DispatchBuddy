#!/usr/bin/env python3

'''
TCP/UDP packet dissector, input is expected from a pcap function, length of packet and packet.

spits out a Packet() object, fully dissected.  (well, as far as tcp and udp go)

'''

__version__     = '1.0'
__author__      = 'david@blue-labs.org'
__license__     = 'BSD'
__site__        = 'https://blue-labs.org/'
__categories__  = {'Development Status':'4 - Beta', 'Intended Audience':'Developers', 'License':'OSI Approved :: BSD License',
                   'Operating System':'POSIX', 'Programming Language':'Python 3'}

import struct, socket, types, logging
from weakref import ref
from binascii import hexlify

# MPTCP Subtype options
mptcp_subtypes = {
   0x0: 'MP_CAPABLE',
   0x1: 'MP_JOIN',
   0x2: 'DSS',
   0x3: 'ADD_ADDR',
   0x4: 'REMOVE_ADDR',
   0x5: 'MP_PRIO',
   0x6: 'MP_FAIL',
   0x7: 'MP_FASTCLOSE'
   # 0x8-0xe unassigned
   # 0xf     reserved for private use
}

# TCP Options - http://www.iana.org/assignments/tcp-parameters
tcp_options = {
   0 :{'name':'EOL',        'f':None },                                                  # end of option list
   1 :{'name':'NOP',        'f':None },                                                  # no operation
   2 :{'name':'MSS',        'f':lambda x: int.from_bytes(x, byteorder='big') },          # maximum segment size
   3 :{'name':'WSCALE',     'f':lambda x: int.from_bytes(x, byteorder='big') },          # window scale factor, RFC 1072
   4 :{'name':'SACKOK',     'f':lambda x: True },                                        # SACK permitted, RFC 2018
   5 :{'name':'SACK',       'f':lambda x: [dict([x for x in zip(['L','R'], struct.unpack('>II', x[(8*y):(8*y)+8]))])
                                                for y in range(1+int(len(x)/16))] },     # SACK, RFC 2018
   6 :{'name':'ECHO',       'f':lambda x: int.from_bytes(x, byteorder='big') },          # echo (obsolete), RFC 1072
   7 :{'name':'ECHOREPLY',  'f':lambda x: int.from_bytes(x, byteorder='big') },          # echo reply (obsolete), RFC 1072
   8 :{'name':'TIMESTAMP',  'f':lambda x: dict([x for x in zip(['TSval','TSecr'], struct.unpack('>II', x))]) }, # timestamp, RFC 1323
   9 :{'name':'POCONN',     'f':lambda x: True },                                        # partial order conn, RFC 1693
   10:{'name':'POSVC',      'f':lambda x: lambda x: {'Start':x&0x80 and True or False,
                                                     'Stop':x&0x40 and True or False} }, # partial order service, RFC 1693
   11:{'name':'CC',         'f':lambda x: int.from_bytes(x, byteorder='big') },          # connection count, RFC 1644
   12:{'name':'CCNEW',      'f':lambda x: int.from_bytes(x, byteorder='big') },          # CC.NEW, RFC 1644
   13:{'name':'CCECHO',     'f':lambda x: int.from_bytes(x, byteorder='big') },          # CC.ECHO, RFC 1644
   14:{'name':'ALTSUM',     'f':lambda x: int.from_bytes(x, byteorder='big') },          # alt checksum request, RFC 1146
   15:{'name':'ALTSUMDATA', 'f':lambda x: x },                                           # alt checksum data, RFC 1146
   16:{'name':'SKEETER',    'f':lambda x: x },                                           # Skeeter (deprecated DH, MITM-vulnerable)
   17:{'name':'BUBBA',      'f':lambda x: x },                                           # Bubba (deprecated DH, MITM-vulnerable)
   18:{'name':'TRAILSUM',   'f':lambda x: x[0] },                                        # trailer checksum
   19:{'name':'MD5',        'f':lambda x: x },                                           # MD5 signature, RFC 2385
   20:{'name':'SCPS',       'f':lambda x: {'BETS':x[0]&0x80 and True or False,
                                           'Sn1':x[0]&0x40 and True or False,
                                           'Sn2':x[0]&0x20 and True or False,
                                           'Com':x[0]&0x10 and True or False,
                                           'NL Ts':x[0]&0x08 and True or False,
                                           'CID':x[1],'Ext':x[2:]} },                    # SCPS capabilities
   21:{'name':'SNACK',      'f':lambda x:  {'Hole1 Offset':x[0]<<8+x[1], 'Hole1 Size':x[2]<<8+x[3],
                                           'BV':x[3:]}}, # selective negative acks, additional math needed.
                                           # Hole1 offset = (offset sequence number - ACK number) /1 MSS in bytes
                                           # Hole1 Size   = Size of Hole1 (in octets) / 1 MSS in bytes
   22:{'name':'REC',        'f':lambda x: True },                                        # record boundaries
   23:{'name':'CORRUPT',    'f':lambda x: True },                                        # corruption experienced
   24:{'name':'SNAP',       'f':lambda x: None },                                        # SNAP (No public documentation available)
  # 25 is unassigned
   26:{'name':'TCPCOMP',    'f':lambda x: None },                                        # TCP compression filter (No public documentation available)
   27:{'name':'QUICKSTART', 'f':lambda x: x },                                           # RFC 4782 (complex value, just return bytes)
   28:{'name':'USERTIMEOUT','f':lambda x: {'Granularity':['sec','min'][(x&0x8000)>>15],'Timeout':x&0x7fff} },    # RFC 5482
   29:{'name':'TCP-AO',     'f':lambda x: {'KeyID':x[0], 'RNextKeyID':x[1], 'MAC':x[2:]} }, # RFC 5925 (replaces option #19)
   30:{'name':'MPTCP',      'f':lambda x: {'Subtype':mptcp_subtypes.get([(x[0]&0xf0)>>4],'??'),
                                           'Data':b''.join([chr(x[0]&0x0f).encode()] + [x[1:]])} }, # RFC 6824
  # 31-252 are reserved
  253:{'name':'RFC3692#1',  'f':lambda x: x },                                           # RFC 4727]
  254:{'name':'RFC3692#2',  'f':lambda x: x },                                           # RFC 4727]
  }


class _VLAN():
    def __init__(self, priority=None, cfi=None, id=None):
        self.priority = priority
        self.cfi      = cfi
        self.id       = id

    def __call__(self, *args, **kwargs):
        if len(args):
            for i,k in enumerate(['priority','cfi','id']):
                if i == len(args):
                    break
                v = args[i]
                setattr(self, k, v)

        if len(kwargs):
            for k, v in kwargs.items():
                if not hasattr(self, k):
                    raise Exception('{}() does not have attribute {!r}'.format(self.__class__.__name__, k))
                setattr(self, k, v)

    def __repr__(self):
        _d = { x:getattr(self, x) for x in dir(self) if not x[0] == x[1] == '_' }
        return str(_d)


class _Ethernet():
    def __init__(self, dst=None, src=None, type=None):
        #print('<< _Ethernet.__init__')
        self.dst  = dst
        self.src  = src
        self.type = type
        #print('<< _Ethernet.__init__')

    def __setstate__(self, state):
        #print('_Ethernet.__setstate__')
        self.__dict__.update(state)

    def __call__(self, *args, **kwargs):
        if len(args):
            for i,k in enumerate(['dst','src','type']):
                if i == len(args):
                    break
                v = args[i]
                setattr(self, k, v)

        if len(kwargs):
            for k, v in kwargs.items():
                if not hasattr(self, k):
                    raise Exception('{}() does not have attribute {!r}'.format(self.__class__.__name__, k))
                setattr(self, k, v)

    def __repr__(self):
        _d = { x:getattr(self, x) for x in dir(self) if not x[0] == x[1] == '_' }
        return str(_d)


class _Flags():
    def __init__(self, reserved=None, dont_fragment=None, more_fragments=None):
        self.reserved       = reserved
        self.dont_fragment  = dont_fragment
        self.more_fragments = more_fragments

    def __call__(self, *args, **kwargs):
        if len(args):
            for i,k in enumerate(['reserved','dont_fragment','more_fragments']):
                if i == len(args):
                    break
                v = args[i]
                setattr(self, k, v)

        if len(kwargs):
            for k, v in kwargs.items():
                if not hasattr(self, k):
                    raise Exception('{}() does not have attribute {!r}'.format(self.__class__.__name__, k))
                setattr(self, k, v)

    def __repr__(self):
        _d = { x:getattr(self, x) for x in dir(self) if not x[0] == x[1] == '_' }
        return str(_d)


class _DS():
    def __init__(self, dscp=None, ecn_ct=None, ecn_ce=None):
        self.dscp   = dscp
        self.ecn_ct = ecn_ct
        self.ecn_ce = ecn_ce

    def __call__(self, *args, **kwargs):
        if len(args):
            for i,k in enumerate(['dscp','ecn_ct','ecn_ce']):
                if i == len(args):
                    break
                v = args[i]
                setattr(self, k, v)

        if len(kwargs):
            for k, v in kwargs.items():
                if not hasattr(self, k):
                    raise Exception('{}() does not have attribute {!r}'.format(self.__class__.__name__, k))
                setattr(self, k, v)

    def __repr__(self):
        _d = { x:getattr(self, x) for x in dir(self) if not x[0] == x[1] == '_' }
        return str(_d)


class _IP(object):
    def __init__(self, version=None, header_length=None, ds=None, total_length=None, id=None, flags=None, fragment_offset=None, ttl=None, protocol=None, checksum=None, src=None, dst=None):
        self.version         = version
        self.header_length   = header_length
        self.ds              = ds and ds or _DS()
        self.total_length    = total_length
        self.id              = id
        self.flags           = flags and flags or _Flags()
        self.fragment_offset = fragment_offset
        self.ttl             = ttl
        self.protocol        = protocol
        self.checksum        = checksum
        self.src             = src
        self.dst             = dst

    def __getattribute__(self, *k):
        v = object.__getattribute__(self, *k)
        if v is None:
            return v

        k=k[0]
        if k == 'protocol':
            v = {0:'ip', 1:'icmp', 6:'tcp', 17:'udp'}.get(v, v)
        elif k == 'checksum':
            v = '{:#06x}'.format(v)
        elif k in {'src','dst'}:
            v = socket.inet_ntoa(v)
        #print('returning type: {}'.format(type(v)))
        return v

    def __call__(self, *args, **kwargs):
        #print('>> _IP.__call__({}, {})'.format(args, kwargs))
        if len(args):
            for i,k in enumerate(['version','header_length','ds','total_length','id','flags','fragment_offset','ttl','protocol','checksum','src','dst']):
                if i == len(args):
                    break
                v = args[i]
                a = getattr(self, k)
                if callable(a):
                    if isinstance(v, dict):
                        a(**v)
                    else:
                        a(v)
                else:
                    setattr(self, k, v)
                if i == len(args):
                    break

        if self.protocol == 'udp':
            setattr(self._parent(), 'udp', _UDP())
        elif self.protocol == 'tcp':
            setattr(self._parent(), 'tcp', _TCP())

        if len(kwargs):
            for k, v in kwargs.items():
                if not hasattr(self, k):
                    raise Exception('{}() does not have attribute {!r}'.format(self.__class__.__name__, k))
                setattr(self, k, v)

    def __repr__(self):
        _d = [ '{}:{}'.format(x,getattr(self, x)) for x in dir(self) if not x[0] == x[1] == '_' ]
        _d = '{'+', '.join(_d)+'}'
        return _d


class _UDP(object):
    def __init__(self, sport=None, dport=None, length=None, checksum=None):
        self.sport           = sport
        self.dport           = dport
        self.length          = length
        self.checksum        = checksum

    def __call__(self, *args, **kwargs):
        if len(args):
            for i,k in enumerate(['sport','dport','length','checksum']):
                if i == len(args):
                    break
                v = args[i]
                setattr(self, k, v)

        if len(kwargs):
            for k, v in kwargs.items():
                if not hasattr(self, k):
                    raise Exception('{}() does not have attribute {!r}'.format(self.__class__.__name__, k))
                setattr(self, k, v)

    def __getattribute__(self, *k):
        v = object.__getattribute__(self, *k)
        if v is None:
            return v

        k=k[0]
        if k == 'checksum':
            v = '{:#06x}'.format(v)
        return v

    def __repr__(self):
        _d = [ '{}:{}'.format(x,getattr(self, x)) for x in dir(self) if not x[0] == x[1] == '_' ]
        _d = '{'+', '.join(_d)+'}'
        return _d


class _tcpflags(object):
    def __new__(cls):
        instance = object.__new__(_tcpflags)
        instance.nonce     = False
        instance.CWR       = False
        instance.ECN_ECHO  = False
        instance.URG       = False
        instance.ACK       = False
        instance.PUSH      = False
        instance.RST       = False
        instance.SYN       = False
        instance.FIN       = False
        instance._intvalue = 0
        return instance

    def __init__(self, flags=0):
        self.nonce    = (flags & 0x100)>0
        self.CWR      = (flags & 0x80) >0
        self.ECN_ECHO = (flags & 0x40) >0
        self.URG      = (flags & 0x20) >0
        self.ACK      = (flags & 0x10) >0
        self.PUSH     = (flags & 0x8)  >0
        self.RST      = (flags & 0x4)  >0
        self.SYN      = (flags & 0x2)  >0
        self.FIN      = (flags & 0x1)  >0
        self._intvalue = flags

    def __call__(self, *args, **kwargs):
        if args:
            flags = args[0]
            self.nonce    = (flags & 0x100)>0
            self.CWR      = (flags & 0x80) >0
            self.ECN_ECHO = (flags & 0x40) >0
            self.URG      = (flags & 0x20) >0
            self.ACK      = (flags & 0x10) >0
            self.PUSH     = (flags & 0x8)  >0
            self.RST      = (flags & 0x4)  >0
            self.SYN      = (flags & 0x2)  >0
            self.FIN      = (flags & 0x1)  >0

    def __setstate__(self, state):
        #print('_tcpflags[{}] setstate'.format(id(self)))
        self.__dict__.update(state)
        #print('_tcpflags dict: {}'.format(self.__dict__))

    def __getattribute__(self, *k):
        #print('[id={}]{} __getattribute__({})'.format(id(self),__class__,k))

        #prettybool={False:'\x1b[1;30mFalse\x1b[0m', True:'\x1b[1;34mTrue\x1b[0m'}
        prettybool={False:'◻', True:'\x1b[1;32m◼\x1b[0m'}
        if k and k[0] == '__dict__':
            #return object.__getattribute__(self, '__dict__')

            #print('d>> {}'.format(object.__getattribute__(self, '__dict__').items()))
            rv = { _k:prettybool[_v] for _k,_v in object.__getattribute__(self,*k).items() if not _k.startswith('_')}
            return rv
        else:
            rv = object.__getattribute__(self,*k)
            #print('ND "{}={}"'.format(*k,rv))
            return rv

    def __eq__(self, b):
        #print('_TCP.__eq__: {}'.format(b))
        _tv = self._intvalue == b._intvalue
        #print('self.flags == b.flags: {}'.format(_tv))
        #print('{}  {}'.format(self._intvalue, b._intvalue))
        return _tv

    def __iter__(self):
        _d = { x:getattr(self, x) for x in dir(self) if not x[0] == x[1] == '_' }
        for k,v in _d.items():
            yield k,v

    def __str__(self):
        #print('[id={}] __str__ {}'.format(id(self), self.__dict__))
        return '{{nonce:{nonce}, CWR:{CWR}, ECN_ECHO:{ECN_ECHO}, URG:{URG}, ACK:{ACK}, PUSH:{PUSH}, RST:{RST}, SYN:{SYN}, FIN:{FIN}}}'.format(**self.__dict__)

class _TCP(object):

    _tcpflags = _tcpflags()

    def __init__(self, sport=None, dport=None, sequence_number=None, acknowledgement_number=None, header_length=None, flags=None, window_size=None, checksum=None, urgent_pointer=None, options=None):
        self._tcpflags = _tcpflags()
        self.sport                  = sport
        self.dport                  = dport
        self.sequence_number        = sequence_number
        self.acknowledgement_number = acknowledgement_number
        self.header_length          = header_length
        self.flags                  = flags and _tcpflags(flags) or self._tcpflags
        self.window_size            = window_size
        self.checksum               = checksum
        self.urgent_pointer         = urgent_pointer
        self.options                = options
        #print('_TCP.flags is {}'.format(self.flags))

    def __call__(self, *args, **kwargs):
        if len(args):
            for i,k in enumerate(['sport','dport','sequence_number','acknowledgement_number','header_length','flags','window_size','checksum','urgent_pointer','options']):
                if i == len(args):
                    break
                v = args[i]
                a = getattr(self, k)
                if callable(a):
                    if isinstance(v, dict):
                        a(**v)
                    else:
                        a(v)
                else:
                    setattr(self, k, v)
                #if k == 'flags':
                #    print('_TCP.flags/k is {}'.format(v))

        if len(kwargs):
            for k, v in kwargs.items():
                if not hasattr(self, k):
                    raise Exception('{}() does not have attribute {!r}'.format(self.__class__.__name__, k))
                setattr(self, k, v)

    def __iter__(self):
        _d = { x:getattr(self, x) for x in dir(self) if not x[0] == x[1] == '_' }
        for k,v in _d:
            yield k,v

    def __getattribute__(self, *k):
        #print('_TCP __getattribute__ {}'.format(k))
        v = object.__getattribute__(self, *k)
        if v is None:
            return v

        k=k[0]
        if k == 'checksum':
            v = '{:#06x}'.format(v)
        return v

    def __str__(self):
        _d = [ '{}:{}'.format(x,getattr(self, x)) for x in dir(self) if not x[0] == x[1] == '_' ]
        _d = 'TCP<{  '+',\n  '.join(_d)+'}>'
        return _d

    def __repr__(self):
        _d = [ '{}:{}'.format(x,getattr(self, x)) for x in dir(self) if not x[0] == x[1] == '_' ]
        _d = '    {'+',\n    '.join(_d)+'}'
        return _d


class _Packet(object):
    '''Try to resemble record format seen when using Wireshark
    '''

    def __init__(self):
        #print('>> _Packet.__init__')
        self.ethernet = _Ethernet()
        self.vlans     = list()
        self.ip        = _IP()
        self.ds        = _DS()
        self.payload   = b''
        #print('<< _Packet.__init__')

    def __setstate__(self, state):
        #print('_Packet.__setstate__')
        self.__dict__.update(state)

    # do some magic here so we can programatically add the tcp or udp class without
    # manually doing so
    def __setattr__(self, key, value):
        #if isinstance(getattr(self, key), 'type')

        object.__setattr__(self, key, value)

        #self.__dict__[key] = value
        #print('_Packet.__setattr__:k={} v={}, vcm:{}'.format(key,value,value.__class__.__module__))

        if value.__class__.__module__ == self.__module__:
            try:
                value._parent = ref(self)
            except AttributeError:
                raise TypeError('MyClass cannot have children of type ' +type(value).__name__)

    def __delattr__(self, key):
        v = self.__dict__[key]
        del self.__dict__[key]
        try:
            v._parent = None
        except AttributeError:
            raise TypeError('Child of MyClass is mysteriously missing its parent')

    def __call__(self, **kwargs):
        print('_Packet called with: {}'.format(kwargs))

    def __iter__(self):
        _d = { x:getattr(self, x) for x in dir(self) if not x[0] == x[1] == '_' }
        for k,v in _d:
            yield k,v

    def __str__(self):
        _d = [ '{}:{}'.format(x,getattr(self, x)) for x in dir(self) if not x[0] == x[1] == '_' ]
        _d = 'Packet<{'+', '.join(_d)+'}>'
        return _d

    def __repr__(self):
        _d = [ '{}:{}'.format(x,getattr(self, x)) for x in dir(self) if not x[0] == x[1] == '_' ]
        _d = '{'+', '.join(_d)+'}'
        return _d


class Packet(object):
    '''Take a packet from libpcap (passed to us in init) and break it down into
    all the standard fields
    input:
        pktlen     length of packet as reported by libpcap
        packet     full body of packet, as given to us by libpcap
    returns:
        Packet object
    '''

    def __init__(self, pktlen=0, packet=None):
        #print('>> Packet.__init__')
        self.P = _Packet()
        self._pktlen = pktlen
        self._packet = packet
        self._parse_headers()
        #print('<< Packet.__init__')

    def machex(sefl, v):
        h = ':'.join(['{0:0>2x}'.format(b) for b in v])
        return h

    def items(self, highlight=[]):
        P = self.P
        outdict = {'src':self.machex(P.ethernet.src), 'dst':self.machex(P.ethernet.dst),
                           'type':'{:#06x}'.format(P.ethernet.type)}

        for e in outdict:
            if 'Ethernet.'+e in highlight:
                outdict[e] = '\\x1b[1;31m'+str(outdict[e])+ '\\x1b[0m'

        yield 'Ethernet', outdict

        outdict = {'version':P.ip.version, 'header_length':P.ip.header_length, 'DS':P.ip.ds,
                     'total_length':P.ip.total_length, 'id':P.ip.id, 'flags':P.ip.flags,
                     'ttl':P.ip.ttl, 'fragment_offset':P.ip.fragment_offset, 'protocol':P.ip.protocol,
                     'checksum':P.ip.checksum, 'src':P.ip.src, 'dst':P.ip.dst}

        for e in outdict:
            if 'IP.'+e in highlight:
                outdict[e] = '\x1b[1;31m'+str(outdict[e])+'\x1b[0m'

        yield ' IP', outdict

        if P.ip.protocol == 'udp':
            outdict = {'sport':P.udp.sport, 'dport':P.udp.dport, 'length':P.udp.length, 'checksum':P.udp.checksum}
            for e in outdict:
                if 'UDP.'+e in highlight:
                    outdict[e] = '\x1b[1;31m'+str(outdict[e])+'\x1b[0m'
            yield '  UDP', outdict

        elif P.ip.protocol == 'tcp':
            outdict = {'sport':P.tcp.sport, 'dport':P.tcp.dport, 'SEQ#':P.tcp.sequence_number,
                          'ACK#':P.tcp.acknowledgement_number, 'HLen':P.tcp.header_length, 'Flags':P.tcp.flags,
                          'WSize':P.tcp.window_size, 'checksum':P.tcp.checksum, 'Urgent':P.tcp.urgent_pointer,
                          'Options':P.tcp.options}
            for e in outdict:
                if 'TCP.'+e in highlight:
                    outdict[e] = '\x1b[1;31m'+str(outdict[e])+'\x1b[0m'
            yield '  TCP', outdict

        yield '   Payload', P.payload


    def __setstate__(self, state):
        #print('setstate: {!r}'.format(state))
        self.__dict__.update(state)


    def __getattr__(self, attr):
        #print('trying to find attr: {}'.format(attr))
        if attr in ('ip','tcp','udp','payload'):
            return object.__getattribute__(self.P, attr)
        else:
            return object.__getattribute__(self, attr)


    def __setattr__(self, k, v):
        self.__dict__[k]=v


    def __iter__(self):
        _d = { x:getattr(self, x) for x in dir(self.P) if not x[0] == x[1] == '_' }
        for k,v in _d:
            yield k,v


    def __str__(self):
        try:
            return str(self.P)
        except Exception as e:
            return "<Packet(Exception:{})>".format(e)


    def __repr__(self):
        return repr(self.P)


    def _parse_headers(self):
        pktlen = self._pktlen
        packet = self._packet

        P = self.P
        P.pktlen = pktlen
        P.packet = packet

        if not packet:
            # injected packet to accomodate gaps, set (almost) all null
            # need to be a bit more smart about things and have someone prefill this stuff?
            P.ethernet('00:00:00:00:00:00','00:00:00:00:00:00',0)
            P.ip(0,20,{'dscp':False, 'ecn_ct':False, 'ecn_ce':False},
                 54,0,{'reserved':0,'dont_fragment':False, 'more_fragments':False },
                 0,0,'tcp',0,'\0\0\0\0','\0\0\0\0')
            P.tcp(0,0,0,0,20,0,0,0,0,[])
            return

        # some (older?) versions of libpcap pad packets at the end. to ensure we get the right
        # count of things, we'll attempt to identify and discard those bytes
        P.accumulated_length = 0

        # note, i have NOT made an effort to handle parsing exceptions due to
        # short packets
        if len(packet) < pktlen:
            log(4, 'short packet! {0} {1}'.format(len(packet), repr(packet)))

        # 14 bytes, extract the ethernet header
        macdst, macsrc,_type = struct.unpack('>6s6sH', packet[:14])
        P.ethernet(macdst,macsrc,_type)
        packet = packet[12:]

        # test for VLAN, repeat this as many times as a vlan tag is found
        while packet[:2] == b'\x81\x00':
            # if it is 802.1Q (VLAN), then the previous two and next two bytes
            # are shifted right. the following two bytes are the priority, cfi,
            # and id.
            _ = struct.unpack('!h', packet[2:4])[0]

            priority = (_ & 0xe000) >> 29     # 000. ....
            cfi      = (_ & 0x1000) >> 28     # ...0 ....
            id       = (_ & 0x0fff) >> 16     # .... 0000 0000 0000
            vlan = _VLAN(priority,cfi,id)

            # multiple vlans appear in reverse order
            P.vlans.insert(0, vlan)

            packet = packet[4:]

        # we ONLY decode IP packets
        frame_type = struct.unpack('!H', packet[:2])[0] & 0xffff
        if not frame_type == 0x0800:
            return
            #raise Exception('Unable to decode packet type {:x}, not IPv4'.format(frame_type))

        # backtrack and reassign ethernet frame type to overwrite vlan info
        P.ethernet.type = frame_type

        packet=packet[2:]

        ihl,tos,total_length,id,fragment_offset,ttl,protocol,checksum,saddr,daddr = struct.unpack('>1s1sHHH1s1sH4s4s', packet[:20])
        version         = (ord(ihl) >> 4) & 0xf
        header_length   = (ord(ihl) & 0xf) *4
        tos             = ord(tos)
        ds              = {'dscp':tos & 0xfc >> 2, 'ecn_ct':tos & 0x02 >> 1 == 1, 'ecn_ce':tos & 0x01 == 1 }

        flags           = {'reserved':fragment_offset>>15,'dont_fragment':(fragment_offset & 0x4000) >> 14 == 1, 'more_fragments':(fragment_offset & 0x2000) >> 13 == 1 }

        fragment_offset = fragment_offset & 0x0fff
        ttl             = ord(ttl)
        protocol        = ord(protocol)

        # there could actually be more here if additional options were set

        P.ip(version,header_length,ds,total_length,id,flags,fragment_offset,ttl,protocol,checksum,saddr,daddr)

        packet = packet[P.ip.header_length:]

        if P.ip.protocol == 'udp':
            sport,dport,length,checksum = struct.unpack('>HHHH', packet[:8])
            P.udp(sport,dport,length,checksum)
            packet = packet[8:]
        elif P.ip.protocol == 'tcp':
            sport,dport,sequence_number,acknowledgement_number,hlen,window_size,checksum,urgent_pointer = struct.unpack('>HHIIHHHH', packet[:20])
            header_length = (hlen & 0xf000) >> 10
            flags  = hlen & 0x0fff
            packet = packet[20:]

            # minimum TCP header size is 20, if hlen is > 20, the rest of it is options
            optionbuf = packet[:header_length-20]
            packet    = packet[header_length-20:]

            options = []
            breaktries=50
            if optionbuf:
                # option processing, order is: type,length,..., continue reading until NOP
                while optionbuf:
                    option_type = optionbuf[0]
                    optionbuf  = optionbuf[1:]
                    if option_type == 1:
                        options.append((tcp_options[option_type]['name'],tcp_options[option_type]['f']))
                        continue

                    option_len = optionbuf[0]-2

                    optionbuf  = optionbuf[1:]
                    option_val = struct.unpack('>{}s'.format(option_len), optionbuf[:option_len])[0]
                    optionbuf  = optionbuf[option_len:]
                    breaktries -= 1
                    if not breaktries:
                        break
                    options.append((tcp_options[option_type]['name'],tcp_options[option_type]['f'](option_val)))

            P.tcp(sport,dport,sequence_number,acknowledgement_number,header_length,flags,window_size,checksum,urgent_pointer,options)

        # some dumbass routers (sonicwall, i'm looking at you) add padding bytes
        try:
            if hasattr(P, 'tcp'):
                header_length = P.tcp.header_length
            elif hasattr(P, 'tcp'):
                header_length = P.udp.header_length

            if P.ip.header_length + header_length < P.ip.total_length:
                P.payload = packet
            else:
                P.payload = b''
        except Exception as e:
            logging.getLogger('bluelabs.dispatchbuddy.sensors.module.packet_dissector').warn('failed to fully dissect packet: {}'.format(e))


if __name__ == '__main__':
    # system
    import os, sys

    # blu3
    sys.path.insert(0, '/usr/src/pylibpcap/build/lib.linux-x86_64-3.3/')
    import pcap

    handle = pcap.pcapObject()

    if not len(sys.argv) >1:
        print('when run stand-alone, append a pcap filename to read on commandline')
        sys.exit()

    print(sys.argv[1])
    if handle.open_offline(sys.argv[1]):
        print('failed to open file: {}'.format(sys.argv[1]))
        sys.exit()

    while True:
        (rval, pktlen, caplen, timestamp, packet) = handle.next_ex()
        print('pktlen: {}, caplen: {}, len {}'.format(pktlen,caplen,len(packet)))

        P = Packet(pktlen, packet)
        if P.ethernet.type == 0x0800 and P.ip.protocol == 'tcp':
            for k,v in P.items():
                print(k,v)
            # break after first packet, debugging
            sys.exit()
