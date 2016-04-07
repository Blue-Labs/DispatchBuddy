#!/usr/bin/env python3

'''
Sensor manager __init__.py

structure for sensor manager:
    * one thread will run per sensor module
        after event collection, write event to disk based queue in pickled format (handle binary)

    * health thread
        periodically (60 seconds) push health status to API over https
            current status will be idle-good-health, in-event:count, api-xmit:count, api-xmit-error:count, host-error:[]
            :count is an indication of how many of such are noted
            host-error is currently only indicative of general host fault such as out of disk space; a list of messages explaining

        after pushing health status, ask about software updates
            if software updates available, pull them and update disk files
                set a marker to reload it (if sensor manager, we're os.exec() ourselves)
                as soon as it is not in busy status, reload it (if sensor manager, wait until no busy sensors)

    * event push thread
        waits on thread events notifications
        when notified, scans disk based queue for events
        attempts to deliver events to API
        upon successful delivery, removes event from queue
'''

