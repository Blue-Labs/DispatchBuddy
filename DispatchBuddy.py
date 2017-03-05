#!/usr/bin/env python

__version__  = '4.7'
__author__   = 'David Ford <david.ford@southmeriden-vfd.org>'
__date__     = '2017-Mar-5'
__title__    = 'DispatchBuddy'
__license__  = 'Apache 2'

# monkey patch threading so .name sets the thread process name
import ctypes, ctypes.util, threading, traceback, os
libpthread_path = ctypes.util.find_library("pthread")
if libpthread_path:
    libpthread = ctypes.CDLL(libpthread_path)
    if hasattr(libpthread, "pthread_setname_np"):
        pthread_setname_np = libpthread.pthread_setname_np
        pthread_setname_np.argtypes = [ctypes.c_void_p, ctypes.c_char_p]
        pthread_setname_np.restype = ctypes.c_int
        orig_start = threading.Thread.start
        def new_start(self):
            orig_start(self)
            try:
                name = self.name
                if not name or name.startswith('Thread-'):
                    name = self.__class__.__name__
                    if name == 'Thread':
                        name = self.name
                if name:
                    name = name.encode()
                    ident = getattr(self, "ident", None)
                    if ident is not None:
                        pthread_setname_np(ident, name[:15])
            except Exception as e:
                print('omgwtf: {}'.format(e))
                traceback.print_exc()
        threading.Thread.start = new_start


import logging
import logging.handlers
import configparser
import prctl
import sys

from api.dispatchbuddy import DispatchBuddy


def main():
    logger   = logging.getLogger()
    configfile   = '/etc/dispatchbuddy/DispatchBuddy.conf'
    config       = configparser.ConfigParser()

    if not config.read(configfile):
        logger.warning ('Error reading required configuration file: {}'.format(configfile))

    if not 'main' in config.sections():
        config.add_section('main')
    if not 'Logging' in config.sections():
        config.add_section('main')

    if not 'log file' in config['Logging']:
        config.set('main','log file','/var/log/dispatchbuddy')
    if not 'log level' in config['Logging']:
        config.set('main','log level','info')
    if not 'log console' in config['Logging']:
        config.set('main','log console','yes')

    logfile       = config.get('Logging', 'log file')
    loglevel      = config.get('Logging', 'log level')
    numeric_level = getattr(logging, loglevel.upper(), None)

    if not isinstance(numeric_level, int):
        print ('Invalid log level: {}'.format(loglevel))
        logger.setLevel(loglevel)

    logger.setLevel(numeric_level)

    try:
        handler   = logging.handlers.TimedRotatingFileHandler(logfile, when='midnight', backupCount=14, encoding='utf-8')
        formatter = logging.Formatter(fmt='%(asctime)s %(levelname)-.1s %(name)s ⨹ %(message)s', datefmt='%Y-%m-%d %H:%M:%S')
        handler.setFormatter(formatter)
        logger.addHandler(handler)
    except:
        logger.warn('failed to open logfile, output to console only')
        config.set('Logging','log console','True')

    if config.get('Logging','log console'):
        ch = logging.StreamHandler()
        ch.setFormatter(formatter)
        logger.addHandler(ch)

    # for k,v in config, remove comments: asdf = 234 # comment
    # k=asdf
    # v=234 # comment


    logger.info('DispatchBuddy v{}'.format(__version__))
    
    # make sure there's a dispatchbuddy cgroup
    # and make sure our pid is slotted into it
    try:
        os.stat('/sys/fs/cgroup/cpu,cpuacct/dispatchbuddy')
    except FileNotFoundError:
        mkdir('/sys/fs/cgroup/cpu,cpuacct/dispatchbuddy')

    # writing a 0 registers my own pid in the file
    try:
        with open('/sys/fs/cgroup/cpu,cpuacct/dispatchbuddy/cgroup.procs', 'w') as f:
            f.write('0\n')
    except Exception as e:
        logger.warning('failed to write "0" to cgroup.procs file: {}'.format(e))

    # set our cpu shares to 50% of cpus
    try:
        with open('/sys/fs/cgroup/cpu,cpuacct/cpu.shares') as f:
            total_shares = int(f.read())
        with open('/sys/fs/cgroup/cpu,cpuacct/dispatchbuddy/cpu.shares', 'w') as f:
            f.write('{}\n'.format(total_shares//2))
    except Exception as e:
        logger.warning('failed to write "{}" to cgroup.shares file: {}'.format(total_shares/2, e))
    
    logger.info('stdin is a tty: {}'.format(sys.stdin.isatty()))
    
    try:
        db = DispatchBuddy(logger, config)
    except Exception as e:
        logger.error('Failed to start: {}'.format(e))
        traceback.print_exc()
    prctl.set_name('DB v{}'.format(__version__))
    prctl.set_proctitle('DispatchBuddy v{}'.format(__version__))

    db.run()

    logger.warn('main() shutdown')
    

if __name__ == '__main__':
    main()
