#!/usr/bin/env python

__version__  = '4.8'
__author__   = 'David Ford <david.ford@southmeriden-vfd.org>'
__date__     = '2017-Jul-21'
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


import datetime
import logging
import logging.handlers

logging.captureWarnings(True)

class MsFormatter(logging.Formatter):
    converter=datetime.datetime.fromtimestamp
    def formatTime(self, record, datefmt=None):
        ct = self.converter(record.created)
        if datefmt:
            s = ct.strftime(datefmt)
        else:
            t = ct.strftime('%Y-%m-%d %H:%M:%S')
            s = '{}.{:03.0f}'.format(t, record.msecs)
        return s
    def format(self, record):
        if record.levelno == logging.INFO:
            record.msg = '\033[90m%s\033[0m' % record.msg
        elif record.levelno == logging.WARNING:
            record.msg = '\033[93m%s\033[0m' % record.msg
        elif record.levelno == logging.ERROR:
            record.msg = '\033[91m%s\033[0m' % record.msg
        return logging.Formatter.format(self, record)

fmt       = '[\x1b[1;30m%(asctime)s %(levelname)-.1s %(name)s\x1b[0m] ⨹ %(message)s'
#datefmt   = '%Y-%m-%d %H:%M:%S.%f'
#logging.basicConfig(format=fmt, datefmt=datefmt)
logger    = logging.getLogger()
formatter = MsFormatter(fmt=fmt)

console = logging.StreamHandler()
console.setFormatter(formatter)
logger.addHandler(console)

import configparser
import prctl
import sys
import pwd
import grp

from api.dispatchbuddy import DispatchBuddy
from imported_modules_tree import get_tree_representation_of_loaded_python_modules

with open('/tmp/s.html', 'w') as f:
    f.write(get_tree_representation_of_loaded_python_modules())

def main():

    configfile   = '/etc/dispatchbuddy/DispatchBuddy.conf'
    config       = configparser.ConfigParser()

    if not config.read(configfile):
        print ('\x1b[1;31mError reading required configuration file\x1b[0m: {}'.format(configfile))

    if not 'main' in config.sections():
        config.add_section('main')
    if not 'Logging' in config.sections():
        config.add_section('main')

    if not 'log file' in config['Logging']:
        config.set('Logging','log file','/var/log/dispatchbuddy')
    if not 'log level' in config['Logging']:
        config.set('Logging','log level','info')

    logfile       = config.get('Logging', 'log file')
    loglevel      = config.get('Logging', 'log level')

    # set the root logger level
    logger    = logging.getLogger()
    logger.setLevel(loglevel.upper())

    # switch to a named logger
    logger    = logging.getLogger('DispatchBuddy')

    try:
        handler   = logging.handlers.TimedRotatingFileHandler(logfile, when='midnight', backupCount=14, encoding='utf-8')
        handler.setFormatter(formatter)
        logger.addHandler(handler)
    except:
        logger.warn('failed to open logfile, output to console only')

    try:
        loglevel = config.get('Logging','console log level')
    except:
        loglevel = 'WARNING'
    console.setLevel(loglevel.upper())

    # for k,v in config, remove comments: asdf = 234 # comment
    # k=asdf
    # v=234 # comment

    logger.info('DispatchBuddy v{}'.format(__version__))

    if not 'run as user' in config['main']:
        logger.warning('no "run as user" setting in [main]')
        for id in (pwd.getpwuid(os.getuid()).pw_name, 'root','dispatchbuddy'):
            try:
                run_as_user = pwd.getpwnam(id).pw_name
            except:
                pass
        config.set('main','run as user',run_as_user)

    if not 'run as group' in config['main']:
        logger.warning('no "run as group" setting in [main]')
        for id in (grp.getgrgid(os.getgid()).gr_name, 'root','dispatchbuddy'):
            try:
                run_as_group = grp.getgrnam(id).gr_name
            except:
                pass
        config.set('main','run as group',run_as_group)

    # make sure there's a dispatchbuddy cgroup
    # and make sure our pid is slotted into it
    try:
        os.stat('/sys/fs/cgroup/cpu,cpuacct/dispatchbuddy')
    except FileNotFoundError:
        os.mkdir('/sys/fs/cgroup/cpu,cpuacct/dispatchbuddy')

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

    #prctl.set_name('DB v{}'.format(__version__))
    #prctl.set_proctitle('DispatchBuddy v{}'.format(__version__))

    db.run()

    logger.warn('main() shutdown')


if __name__ == '__main__':
    main()
