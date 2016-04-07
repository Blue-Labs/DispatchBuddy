#!/usr/bin/env python

__version__  = '4.0'
__author__   = 'David Ford <david.ford@southmeriden-vfd.org>'
__date__     = '2015-Jul-19'
__title__    = 'DispatchBuddy'
__tfname__   = __title__

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


import logging, logging.handlers, configparser, prctl

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
    
    # make sure i'm in the cpu:/ cgroup
    with open('/sys/fs/cgroup/cpu/tasks', 'w') as f:
        f.write('{}\n'.format(os.getpid()))

    try:
        db = DispatchBuddy(logger, config)
        logger.warn('eh?')
    except Exception as e:
        logger.error('Failed to start: {}'.format(e))
        traceback.print_exc()
    prctl.set_name('DB v{}'.format(__version__))
    prctl.set_proctitle('DispatchBuddy v{}'.format(__version__))
    db.run()
    logger.warn('shit muh drawers, did i?')
    

if __name__ == '__main__':
    main()
