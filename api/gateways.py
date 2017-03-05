
# send message
# for each recipient, store the event ID, sending ID, and sent status in db.

import os
import re
import smtplib
import datetime
import logging

from email.message import EmailMessage
from email.utils import make_msgid
from email.mime.text import MIMEText
from email.mime.image import MIMEImage
from email.mime.multipart import MIMEMultipart
from email.mime.application import MIMEApplication

# put these into the configfile
from twilio.rest import TwilioRestClient
from twython import Twython

logging.basicConfig(level=logging.DEBUG)

media_urls = {
    # primary icons, red or blue
    '^AUTO ACCID HEAD ON OR ROLLOVER' :'AUTO_ACCID_HEAD_ON.png',   # cars facing each other
    '^AUTO ACCID [^H]'                :'AUTO_ACCID.png',           # cars at 90deg
    '^AUTO ACCID WITH PEOPLE TRAPPED' :'',
    '^ALARM FIRE SOUNDING'            :'FIRE_ALARM.png',
    'MASTER\s?BOX'                    :'BOX.png',
    '^FIRE AUTO'                      :'FIRE_AUTO.png',
    '^FIRE GRASS'                     :'FIRE_WOODS.png',
    '^FIRE ILLEGAL'                   :'FIRE_ILLEGAL.png',
    '^FIRE INSIDE'                    :'FIRE_BLDG.png',
    '^HAZARD GAS'                     :'HAZARD_GAS.png',
    '^LOCKOUT EMERGENCY'              :'LOCKOUT.png',
    '^MV ACCIDENT INVOLVING BUILDING' :'MV_ACCIDENT_INVOLVING_BUILDING.png',
    '^MV ACCID '                      :'AUTO_ACCID.png',
    '^MVA WITH HAZMAT '               :'',
    '^ODOR OF SMOKE'                  :'',
    '^SPEC RESPONSE CODE GREEN'       :'',
    '^WIRES DOWN'                     :'WIRES_DOWN.png',
    '^RESCUE EMS'                     :'RESCUE_EMS.png',

    # supplementary icons (gray)
    'MV VS PED'                       :'MVA_VS_PED.png',
    'ANIMAL BITE'                     :'ANIMAL_BITE.png',
    'INJURIES'                        :'INJURY.png',
    'WITH INJURY'                     :'INJURY.png',
    #'UNKNOWN'                         :'UNKNOWN.png',
    '^AUTO ACCID .* HAZARD'           :'ROAD_HAZARD.png',
    'MOTORCYCLE'                      :'MOTORCYCLE.png',
}


#with open('/var/bluelabs/DispatchBuddy/images/magnify.gif', 'rb') as f:
#    icon_magnify_gif = MIMEImage(f.read())
#
#icon_magnify_gif.add_header('Content-Type','image/gif',name='magify.gif')
#icon_magnify_gif.add_header('Content-ID','<magnify.gif@smvfd.info>')
#icon_magnify_gif.add_header('Content-Disposition','inline',filename='magnify.gif')

class Gateway():
    def __init__(self):
        self.logger = logging.getLogger('Gateway')

    def deliver(self, id, rx, msgbody, status_recorder):
        ''' send to a single recipient
            pass status_recorder function into call for immediacy timestamps
        '''
        gwd = getattr(self, 'deliver_'+self.gateway, None)
        if not gwd:
            self.logger.critical('delivery method for {} not known'.format(self.gateway))
            #raise NotImplementedError
            return
        
        # get back a tuple of the ID and Status returned from our gateway provider
        self.logger.debug('sending to {}/{}'.format(self.gateway, self.mediatype))
        gwd(id, rx, msgbody, status_recorder)
        self.db.conn.commit()
    
    
    def deliver_twilio(self, id, rx, msgbody, status_recorder):
        ''' gateway gives us plenty of info, what we want out of it is:
              date_created, error_code, error_message, sid, status
              
              we'll parse the error_code and error_message and return them in sendStatus
        '''
        
        send_results = []
        
        twilio_messaging_service_sid = self.config.get('Twilio', 'twilio_messaging_service_sid')
        twilio_account_sid           = self.config.get('Twilio', 'twilio_account_sid')
        twilio_auth_token            = self.config.get('Twilio', 'twilio_auth_token')
        twilio_from                  = self.config.get('Twilio', 'twilio_from')
        twilio_client                = TwilioRestClient(twilio_account_sid, twilio_auth_token)

        args = {'body':msgbody, 'messaging_service_sid':twilio_messaging_service_sid}
        medias = []
        if self.mediatype == 'mms':
            for rk in sorted(media_urls):
                if re.search(rk, self.evdict['nature']) or re.search(rk, self.evdict['notes']):
                    media = media_urls.get(rk)
                    if not media:
                        media = media_urls['UNKNOWN']
                    media = 'https://southmeriden-vfd.org/images/dispatchbuddy/'+media
                    medias.append(media)
            
            if os.getenv('TESTING'):
                medias.append('https://southmeriden-vfd.org/images/dispatchbuddy/test.png')

            args['media_url'] = set(medias)

        #self.logger.debug('args list: {}'.format(args))

        for r in rx:
            
            #self.logger.debug(r)
            addr = r.address
            
            if '@' in addr:
                addr = addr.split('@',1)[0]
            if len(addr) == 10:
                addr = '+1'+addr
            
            if not re.fullmatch('\+1\d{10}', addr):
                self.logger.warning('recipient address malformed for {}; {}'.format(addr, self.gateway))
                continue
            
            args['to'] = addr
            
            try:
                message = twilio_client.messages.create(**args)
                if message.status == 'accepted':
                    status_recorder(recipient=message.to, delivery_id=message.sid, status='accepted')
                else:
                    reason = '{}: {}'.format(message.error_code, message.error_message)
                    status_recorder(recipient=message.to, delivery_id=message.sid, status='failed', reason=reason, completed=True)
                    self.logger.warning('twilio bad sending response: {}'.format(message))
            except Exception as e:
                self.logger.warning('Twilio got grumpy sending to {}@{}: {}'.format(addr, self.gateway, e))
                status_recorder(recipient=message.to, delivery_id=message.sid, delivery_ts=message.date_created, status='failed', reason=str(e), completed=True)


    def deliver_email(self, id, rx, msgbody, status_recorder):
        ''' gateway gives us plenty of info, what we want out of it is:
              date_created, error_code, error_message, sid, status
              
              we'll parse the error_code and error_message and return them in sendStatus
        '''
        urgheaders = [
           ('Priority','Urgent'),
           ('X-Priority','1 (Highest)'),
           ('Importance','High'),
           ('X-MMS-Priority','Urgent')
        ]
        
        emailheaders = [
           ('To', 'SMVFD'),
           ('From', 'DispatchBuddy <m@smvfd.info>'),
           ('Subject', 'Fire Dispatch: {address}'.format(address=self.evdict['address'])),
        ]
        
        send_results = []
        
        now = datetime.datetime.utcnow()
        
        msg = EmailMessage()
        for h,v in emailheaders:
            msg[h]=v
        
        msg.set_content('This is an HTML only email')
        magnify_icon_cid = make_msgid()

        medias = []
        fdict  = {'meta_icons':'', 'magnify_icon_cid':magnify_icon_cid[1:-1]}

        self.logger.debug('ADD MMS urls, search for keys in: {}'.format(self.evdict['nature']))
        for rk in sorted(media_urls):
            if re.search(rk, self.evdict['nature']) or re.search(rk, self.evdict['notes']):
                self.logger.debug('found key: {}'.format(rk))
                media = media_urls.get(rk)
                if not media:
                    media = media_urls['UNKNOWN']
                media = 'https://southmeriden-vfd.org/images/dispatchbuddy/icon-'+media
                medias.append(media)
        
        medias = set(medias)
        
        self.logger.debug('media urls: {}'.format(medias))

        meta_icons_t = ''
        if medias:
            meta_icons_t = ''
            for url in medias:
                meta_icons_t += '<img class="meta_icons" src="{url}">'.format(url=url)

        fdict.update({'meta_icons':meta_icons_t})
        
        for kw in ('meta_icons', 'magnify_icon_cid'):
            msgbody = msgbody.replace('{{'+kw+'}}','{'+kw+'}')
        
        msgbody = msgbody.format(**fdict)
        
        # now replace all the {{ and }}
        msgbody = msgbody.replace('{{','{').replace('}}','}')
        
        msg.add_alternative(msgbody, subtype='html')

        with open('/var/bluelabs/DispatchBuddy/images/magnify.gif', 'rb') as img:
            msg.get_payload()[1].add_related(img.read(), 'image', 'gif', cid=magnify_icon_cid)

        '''
        related = MIMEMultipart(_subtype='related')
        innerhtml = MIMEText(msgbody, _subtype='html')
        related.attach(innerhtml)
        related.attach(icon_magnify_gif)
        '''
        
        for h,v in urgheaders:
            msg[h]=v
        
        bcc = [r.address for r in rx]
        
        '''
            medias = []
            if self.mediatype == 'mms':
                for rk in sorted(media_urls):
                    if re.search(rk, self.evdict['nature']) or re.search(rk, self.evdict['notes']):
                        media = media_urls.get(rk)
                        if not media:
                            media = media_urls['UNKNOWN']
                        media = 'https://southmeriden-vfd.org/images/dispatchbuddy/'+media
                
                args['media_url'] = medias
            
            print('args list: {}'.format(args))
        '''

        host  = self.config.get('SMTP', 'host')
        ehlo  = self.config.get('SMTP', 'ehlo')
        user  = self.config.get('SMTP', 'user')
        pass_ = self.config.get('SMTP', 'pass')
        from_ = self.config.get('SMTP', 'from')

        try:
            s = smtplib.SMTP(host=host, port='25')
            #s.set_debuglevel(1)
            s.starttls()
            s.ehlo(ehlo)
            s.login(user, pass_)
            sresponse = s.sendmail(from_, bcc, msg.as_string(), keep_results=True)
            qresponse = s.quit()
            self.logger.debug('server sresponse: {}'.format(sresponse))
            self.logger.debug('server qresponse: {}'.format(qresponse))

            for r in bcc:
                code,status = sresponse[r]
                status      = status.decode().split()
                id          = status[4]
                status      = status[2]

                try:
                    status_recorder(recipient=r, delivery_id=id, status=status)
                except Exception as e:
                    self.logger.warning('unable to relay status elements to DB recorder: {}'.format(e))
                    status_recorder(recipient=r, delivery_id=id, status='failed', reason=str(e), completed=True)

        except Exception as e:
            self.logger.warning('Failed to send message to recipients: {}'.format(e))
            for r in bcc:
                status_recorder(recipient=r, delivery_id=r.gateway, status='failed', reason=str(e), completed=True)


    def deliver_twitter(self, id, rx, msgbody, status_recorder):
        ''' gateway gives us plenty of info, what we want out of it is:
              date_created, error_code, error_message, sid, status
              
              we'll parse the error_code and error_message and return them in sendStatus
        '''
        
        send_results = []
        twitter_app_key            = self.config.get('Twython', 'twitter_app_key')
        twitter_app_secret         = self.config.get('Twython', 'twitter_app_secret')
        twitter_oauth_token        = self.config.get('Twython', 'twitter_oauth_token')
        twitter_oauth_token_secret = self.config.get('Twython', 'twitter_oauth_token_secret')

        twitter_client        = Twython(twitter_app_key, twitter_app_secret, twitter_oauth_token, twitter_oauth_token_secret)

        args = {'status':msgbody}
        medias = []
        for rk in sorted(media_urls):
            # search for cues in nature and notes
            if re.search(rk, self.evdict['nature']) or re.search(rk, self.evdict['notes']):
                self.logger.debug('found key: {}'.format(rk))
                media = media_urls[rk]
                self.logger.debug('got media id {}'.format(media))
                if not media:
                    #media = media_urls['UNKNOWN']   # don't post an unknown to twitter
                    continue
                with open('/var/bluelabs/DispatchBuddy/images/'+media, 'rb') as ph:
                    retries=3
                    while retries:
                        try:
                            response = twitter_client.upload_media(media=ph, timeout=30)
                            medias.append(response['media_id'])
                            break
                        except Exception as e:
                            self.logger.warning('Error uploading to twitter: {}'.format(e))
                            retries -= 1
                            continue


        if medias:
            args['media_ids'] = set(medias)
        
        try:
            response = twitter_client.update_status(**args)
            print('twitter response: {}'.format(response))
            status_recorder(recipient='twitter', delivery_id=response['id'], status='delivered', completed=True)
        except Exception as e:
            status_recorder(recipient='twitter', delivery_id='HTTP error', status='failed', reason=str(e), completed=True)
            self.logger.warning('unable to relay status elements to DB recorder: {}'.format(e))
