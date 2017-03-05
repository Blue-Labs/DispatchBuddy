
import threading
import sys
import time
import traceback
import os

from bottle import Bottle, default_app, run as bottle_run, route, static_file

from sensors.sensor_manager import SensorManager
from api.event import EventManager

'''
bottle part of this needs to accomplish the following:

  /status.txt
     provides an Icinga/Nagios value to indicate program health
  /index.html
     front page, *requires login
        views current status of DB, current dispatches, and 10 most recent calls
        shows google map of district and MFD, shows hydrants in our district

     reachable:
        drill down into any call
          show known call information
          show on google map
          show statistics for calls at this location
          show known data about this location
          show floorplan?

        db recipient management
        edit templates?
        research dispatches that match query language
        statistics
        
'''


class DispatchBuddyWebUI(Bottle):
    def __init__(self, logger, config):
        super().__init__(catchall=False)
        self.catchall  = False
        self.logger    = logger
        self.config    = config
        self._shutdown = threading.Event()

        self.route('/', callback=self.web_index)
        self.route('/css/<filename>', callback=self.web_css)
        self.route('/js/<filename>', callback=self.web_js)
        self.route('/images/<filename>', callback=self.web_images)
        
    def web_index(self):
        return static_file('index.html', root='/var/bluelabs/DispatchBuddy/htdocs')
    def web_css(self, filename):
        return static_file(filename, root='/var/bluelabs/DispatchBuddy/htdocs/css')
    def web_js(self, filename):
        return static_file(filename, root='/var/bluelabs/DispatchBuddy/htdocs/js')
    def web_images(self, filename):
        return static_file(filename, root='/var/bluelabs/DispatchBuddy/htdocs/images')


    def shutdown(self):
        self._shutdown.set()


    def run_app(self):
        try:
            #self.logger.info(dir(self))
            #self.logger.info(dir(self.run))
            self.run(host='0.0.0.0', port=80)

        except KeyboardInterrupt:
            print('\r', end='')
            self._shutdown.set()
        
        except Exception as e:
            self.logger.critical(e)
            self.logger.critical(traceback.format_exc)
        
        finally:
            self.logger.info('shutting down web UI')
        




class DispatchBuddy():#Bottle):
    threads = []

    def __init__(self, logger, config):
        self.logger    = logger
        self.config    = config
        self._shutdown = threading.Event()
        config.event_manager = EventManager(logger, config)
        sm = SensorManager(logger, config)
        
        sm.start()
        
        self.threads.append(sm)
        
        #dbweb = DispatchBuddyWebUI(logger, config)
        #th = threading.Thread(target=dbweb.run_app, name='DB WebUI')
        #th.start()
        
        #self.threads.append((th,dbweb))
        
        for p in ('/var/db/DispatchBuddy',
                  '/var/db/DispatchBuddy/evdata',
                  '/var/db/DispatchBuddy/pcap',
                  '/var/db/DispatchBuddy/tmp'):
            try:
                os.stat(p)
            except FileNotFoundError:
                os.mkdir(p, 0o700)
            except:
                traceback.print_exc()


    def shutdown(self):
        self._shutdown.set()


    def run(self):
        try:
            while True:
                #sys.stderr.write('api/dispatchbuddy.py:wait()\n')
                self._shutdown.wait(timeout=1)
                #sys.stderr.write('write threadstack\n')
                code = []
                for threadId, stack in sys._current_frames().items():
                    code.append("\n# ThreadID: {}".format(threadId))
                    for filename, lineno, name, line in traceback.extract_stack(stack):
                        code.append('"{}", line {}, in {}'.format(filename, lineno, name))
                        if line:
                            code.append("  {}".format(line.strip()))

                
                with open('/tmp/thread-stack.txt','w') as f:
                    f.write('{}\n'.format('\n'.join(code)))


        except KeyboardInterrupt:
            print('\r', end='')
        
        except Exception as e:
            sys.stderr.write('======= {}\n'.format(e))
            self.logger.critical(traceback.format_exc())
        
        finally:
            self.config.event_manager.shutdown()
            for t in self.threads:
                if isinstance(t,tuple):
                    t,obj = t
                    t.shutdown = obj.shutdown
                self.logger.info('shutting down {}'.format(t.name))
                t.shutdown()
                t.join(10)
                if t.is_alive():
                    self.logger.warning('  thread won\'t shutdown')
        self._shutdown.set()
