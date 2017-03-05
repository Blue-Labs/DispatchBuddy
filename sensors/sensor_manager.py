#!/usr/bin/env python3

'''
This is the sensor manager module. It is responsible for the overall functioning
of sensors, including fetching updated software and relaunching.
'''

import datetime, threading, logging, importlib
import prctl, time

__version__ = '0.2'


def start_sensor_thread(logger, config, eventmanager, module=None, name="Undefined module"):
    if not module:
        raise Exception('module name is required')

    # ensure module is loaded
    #_module = importlib.import_module('module.'+module)
    try:
        _module = importlib.import_module('sensors.module.'+module, package='sensors')
    except Exception as e:
        logger.error('failed to import required module: {}'.format(e))
        raise
        
    globals()[module] = _module
    logger.info('loaded: {}'.format(_module.__name__[15:]))
    threads = _module.startup(config, eventmanager)
    suffix  = len(threads)!=1 and 's' or ''
    logger.info('{} now running with {} thread{}'.format(globals()[module].__name__, len(threads), suffix))
    return threads


class SensorManager(threading.Thread):
    def __init__(self, logger, config):
        super().__init__()
        self.logger    = logger
        self.config    = config
        self.name      = 'Sensor Manager'
        self.setName('SensorManager')
        self._shutdown = threading.Event()


    def shutdown(self):
        self._shutdown.set()

    
    def run(self):
        # need a config to see which sensors to activate
        foo = self.config.get('main','watchdogs').split('#',1)[0]

        watchdogs = { x.strip():[] for x in self.config.get('main','watchdogs').split('#',1)[0].strip(' ').split(',') if x }

        for watchdog in watchdogs:
            watchdogs[watchdog] = start_sensor_thread(self.logger, self.config, module=watchdog, eventmanager=self.config.event_manager)

        sensors = { x.strip():[] for x in self.config.get('main','sensors').split('#',1)[0].strip(' ').split(',') if x }

        for sensor in sensors:
            sensors[sensor] = start_sensor_thread(self.logger, self.config, module=sensor, eventmanager=self.config.event_manager)

        # after launching everything, we enter a maintenance loop
        self.logger.info('enter sensor monitoring loop')
        try:
            self._shutdown.wait()

        except KeyboardInterrupt:
            pass
        
        except Exception as e:
            self.logger.critical('unexected error: {}'.format(e))
            self.logger.critical(traceback.format_exc)
    
        finally:
            _ = sensors
            _.update(watchdogs)
            for T in _:
                self.logger.info('terminating module: {}'.format(T))
                for th_dict in _[T]:
                    for th,_c in th_dict.items():
                        self.logger.info('  handler stop: {}'.format(th.name))
                        _c.shutdown()
                        th.join(2)
                        if th.is_alive():
                            self.logger.warning('    thread won\'t shutdown')

        self.logger.debug('SensorManager terminated')


if __name__ == '__main__':
    pass
