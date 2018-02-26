import configparser
import datetime
import logging
import pyrebase
import pyfcm
import traceback

'''
Push data into Google's Firebase real-time database.
'''
class Firebase:
    firebase=None
    firebase_db=None
    firebase_user=None
    firebase_user_authtime=0
    push_service=None
    domain=''

    def __init__(self, config, logger=None, debug=False):
        if not logger:
            print('instantiating our OWN LOGGER')
            logging.basicConfig()
            logger = logging.getLogger('bluelabs.dispatchbuddy.api.firebase')
            logger.setLevel(logging.INFO)

        self.logger = logger
        self.config = config
        self.connectToFirebase();
        self.domain = self.config['Firebase']['username'].split('@')[1].replace('.', '_')
        self.push_service = pyfcm.FCMNotification(api_key=self.config['Firebase']['ServerKey'])


    def connectToFirebase(self):
        self.firebase = pyrebase.initialize_app(self.config['Firebase'])

        try:
            self.refreshAuthToken()
        except:
            logger.error('Failed to login to Firebase: {}'.format(e))

        self.firebase_db = self.firebase.database()


    def refreshAuthToken(self):
        auth = self.firebase.auth()
        self.firebase_user = auth.sign_in_with_email_and_password(
            self.config['Firebase']['username'],
            self.config['Firebase']['password'])

        self.firebase_user_authtime = datetime.datetime.utcnow()
        self.logger.debug('Authenticated to Firebase');


    def alreadyInFirebase(self, path, ev):
        nodes = self.firebase_db.child(path).get(token=self.firebase_user['idToken'])
        path = path.replace('dispatches', 'dispatchIDs')
        pkey_val = {'pkey':'{}, {}, {}'.format(ev['isotimestamp'], ev['nature'], ev['address'])}
        self.logger.debug('checking for {}/{}'.format(path, pkey_val))

        try:
            if [e.item[1] for e in self.firebase_db         \
                .child(path)\
                .order_by_child('pkey')                     \
                .equal_to(pkey_val['pkey'])                 \
                .get(token=self.firebase_user['idToken'])   \
                .each()]
                self.logger.info('{} already in Firebase'.format(pkey_val))
                return True
        except Exception as e:
            traceback.print_exc()

        # store the pkey
        self.logger.debug('storing {}/{}'.format(path, pkey_val))
        self.firebase_db.child(path).push(pkey_val, token=self.firebase_user['idToken'])
        return False


    def pushEvent(self, ev):
        try:
            if (datetime.datetime.utcnow() - self.firebase_user_authtime).total_seconds() > 3540:
                auth = self.firebase.auth()
                self.firebase_user = auth.sign_in_with_email_and_password(
                    self.config['Firebase']['username'],
                    self.config['Firebase']['password'])

                self.firebase_user_authtime = datetime.datetime.utcnow()
        except:
            self.logger.error('Failed to authenticate to Firebase: {}'.format(e))
            return

        # we won't push dupes into the dispatches collection, but we will emit a new
        # notification

        # debug+ is hardwired in here so i can build testing data with real content
        for path in ('/dispatches/'+self.domain, '/debug/dispatches/'+self.domain):
            if not self.alreadyInFirebase(path, ev):
                self.logger.debug('push path is: {}'.format(path))
                try:
                    self.firebase_db.child(path).push(dict(ev._asdict()), token=self.firebase_user['idToken'])
                except Exception as e:
                    self.logger.warning('Failed to store event in Firebase:{}: {}'.format(path,e))

        try:
            self.sendPushNotification(ev)
        except Exception as e:
            self.logger.warning('Failed to send push notifications: {}'.format(e))
            traceback.print_exc()


    def sendPushNotification(self, ev):
        data_message = {
            "nature":       ev['nature'],
            "address":      ev['address'],
            "isotimestamp": ev['isotimestamp'],
            "notification": {
                "title": "DispatchBuddy",
                "body" : "Dispatch received, open DispatchBuddy. This should've happened automatically",
                "icon" : "ic_launcher",
                "click_action": "OPEN_MAIN_ACTIVITY"
            }
        }

        # debug+ is hardwired in here so i can build testing data with real content
        # do non-debug first. get device registrations, then push to them
        for path in ('/deviceRegistrations/'+self.domain, '/debug/deviceRegistrations/'+self.domain):
            self.logger.debug("pushing data-notifications for: {}".format(path))
            try:
                registration_ids = [e.item[1] for e in
                    self.firebase_db.child(path).get(token=self.firebase_user['idToken']).each() ]
            except:
                # empty list of registrations
                continue

            results = self.push_service.multiple_devices_data_message(
                registration_ids=[r['firebaseMessagingRegToken'] for r in registration_ids],
                data_message=data_message,
                )

            # report?
            if results: # will be None if nobody is registered
                for i,result in enumerate(results['results']):
                    self.logger.debug('{:<40}: {}'.format(registration_ids[i]['registeredUser'], result))


if __name__ == '__main__':
    from collections import namedtuple

    _ = {'date'           :'2016-05-18',
         'time_out'       :'05:52',
         'isotimestamp'   :'2016-05-18 05:52:00-05:00',
         'date_time'      :'May18, 5:52am',
         'nature'         :'RESCUE EMS CALL',
         'business'       :'',
         'notes'          :'FEMALE UNRESPONSIVE [05/18/16 05:51:50 588]',
         'msgtype'        :'dispatch',
         'cross'          :'CHARLES ST SM & CHARLES ST SM',
         'address'        :'156 Douglas Dr',
         'units'          :'E1, E11',
         'city'           :'Meriden, CT',
         'incident_number':'',
         'report_number'  :'',
         'gmapurl'        :'https://www.google.com/maps/place/156,DOUGLAS,DR,+Meriden,+CT+06451',
         'gmapurldir'     :'https://www.google.com/maps/dir/South+Meriden+Volunteer+Fire+Department,+31,+Camp,+Street,+Meriden,+CT+06451/156,DOUGLAS,DR,+Meriden,+CT+06451/data=!4m2!4m1!3e0!5m1!1e1',
         'event_uuid'     :'ff92722e-0825-45e4-93fa-fed616e395ab',
         'premise'        :'',
         'subdivision'    :'',
         'ra'             :'',
         }


    ev = namedtuple('Event', _.keys())
    ev = ev(**_)

    logging.basicConfig()
    logger = logging.getLogger('DB.Firebase')
    logger.setLevel(logging.DEBUG)

    configfile   = '/etc/dispatchbuddy/DispatchBuddy.conf'
    config       = configparser.ConfigParser()

    if not config.read(configfile):
        logger.warning ('Error reading required configuration file: {}'.format(configfile))

    logger.debug('Firebase startup')
    FB=Firebase(config, logger)
    FB.pushEvent(ev)
