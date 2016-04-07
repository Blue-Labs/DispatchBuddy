#!/usr/bin/env python3

'''
This is the rewrite of DB using proper threading and splitting the sensor off into a single unit. The
sensor is responsible for
    * reacting to packets that match recipes
    * extracting the payload from the packet data
    * combining the payload into one block
    * sending the payload data with a recipe identifier to the API
    * accepting recipe updates from the API
    * accepting file updates from the API and relaunching self

The DispatchBuddy daemon is responsible for
    * storing the event data in a queue
    * monitoring and reporting health of sensors
    * providing a human presentable status webpage
    * providing icinga/nagios health data per sensor and for the API
    * managing subscriptions to broadcast messages

The Celery daemon is responsible for
    * operating each task triggered from the DispatchBuddy daemon, generally this is:
    - formatting based on message templates
    - broadcasting the generated messages

Project code layout
    /__init__.py
    /common/        files for both the API and sensors
    /api/           API code
    /sensor/        sensor code
'''

print('pickles')
