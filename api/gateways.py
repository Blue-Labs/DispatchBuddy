
# send message
# for each recipient, store the event ID, sending ID, and sent status in db.

import re, smtplib, datetime
from email.message import EmailMessage
from email.utils import make_msgid
from email.mime.text import MIMEText
from email.mime.image import MIMEImage
from email.mime.multipart import MIMEMultipart
from email.mime.application import MIMEApplication

# put these into the configfile
from twilio.rest import TwilioRestClient

from twython import Twython


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
        pass

    def deliver(self, id, rx, msg):
        ''' send to a single recipient
        '''
        gwd = getattr(self, 'deliver_'+self.gateway, None)
        if not gwd:
            self.logger.critical('delivery method for {} not known'.format(self.gateway))
            #raise NotImplementedError
            return
        
        # get back a tuple of the ID and Status returned from our gateway provider
        print('sending to {}/{}'.format(self.gateway, self.mediatype))
        statuses = gwd(id, rx, msg)
        for s in statuses:
            print('recording: {}'.format(s))
            self.record_delivery(id, *s)

    def record_delivery(self, event_id, recipient, delivery_id, delivery_ts, delivery_status):
        #print('recording: {} {} {} {}'.format(eventID, sendID, sendTS, sendStatus))
        with self.db.conn.cursor() as c:
            c.execute('INSERT INTO event_deliveries VALUES(%s,%s,%s,%s,%s,%s,%s)',
                (event_id, recipient, self.gateway, self.mediatype,
                 delivery_id, delivery_ts, delivery_status))
        self.db.conn.commit()
    
    
    def deliver_twilio(self, id, rx, msg):
        ''' gateway gives us plenty of info, what we want out of it is:
              date_created, error_code, error_message, sid, status
              
              we'll parse the error_code and error_message and return them in sendStatus
        '''
        
        send_results = []
        
        twilio_account_sid = self.config.get('Twilio', 'twilio_account_sid')
        twilio_auth_token  = self.config.get('Twilio', 'twilio_auth_token')
        twilio_from        = self.config.get('Twilio', 'twilio_from')
        twilio_client      = TwilioRestClient(twilio_account_sid, twilio_auth_token)

        args = {'body':msg, 'from_':twilio_from}
        medias = []
        if self.mediatype == 'mms':
            for rk in sorted(media_urls):
                if re.search(rk, self.evdict['nature']) or re.search(rk, self.evdict['notes']):
                    media = media_urls.get(rk)
                    if not media:
                        media = media_urls['UNKNOWN']
                    media = 'https://southmeriden-vfd.org/images/dispatchbuddy/'+media
                    medias.append(media)
            
            args['media_url'] = set(medias)

        print('args list: {}'.format(args))

        for r in rx:
            
            print(r)
            addr = r.address
            
            if '@' in addr:
                addr = addr.split('@',1)[0]
            if len(addr) == 10:
                addr = '+1'+addr
            
            if not re.fullmatch('\+1\d{10}', addr):
                self.logger.warning('recipient address malformed for {}; {}'.format(addr, self.gateway))
                continue
            
            args['to'] = addr
            
            message = twilio_client.messages.create(**args)
            send_results.append( (message.to, message.sid, message.date_created, message.status) )

        return send_results

    def deliver_email(self, id, rx, msgcontent):
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
        
        now = datetime.datetime.now()
        
        msg = EmailMessage()
        for h,v in emailheaders:
            msg[h]=v
        
        msg.set_content('This is an HTML only email')
        magnify_icon_cid = make_msgid()

        medias = []
        fdict  = {'meta_icons':'', 'magnify_icon_cid':magnify_icon_cid[1:-1]}

        print('ADD MMS urls, search for keys in: {}'.format(self.evdict['nature']))
        for rk in sorted(media_urls):
            if re.search(rk, self.evdict['nature']) or re.search(rk, self.evdict['notes']):
                print('found key: {}'.format(rk))
                media = media_urls.get(rk)
                if not media:
                    media = media_urls['UNKNOWN']
                media = 'https://southmeriden-vfd.org/images/dispatchbuddy/icon-'+media
                medias.append(media)
        
        medias = set(medias)
        
        print('media urls: {}'.format(medias))

        meta_icons_t = ''
        if medias:
            meta_icons_t = ''
            for url in medias:
                meta_icons_t += '<img class="meta_icons" src="{url}">'.format(url=url)

        fdict.update({'meta_icons':meta_icons_t})
        
        for kw in ('meta_icons', 'magnify_icon_cid'):
            msgcontent = msgcontent.replace('{{'+kw+'}}','{'+kw+'}')
        
        msgcontent = msgcontent.format(**fdict)
        
        # now replace all the {{ and }}
        msgcontent = msgcontent.replace('{{','{').replace('}}','}')
        
        msg.add_alternative(msgcontent, subtype='html')

        with open('/var/bluelabs/DispatchBuddy/images/magnify.gif', 'rb') as img:
            msg.get_payload()[1].add_related(img.read(), 'image', 'gif', cid=magnify_icon_cid)

        '''
        related = MIMEMultipart(_subtype='related')
        innerhtml = MIMEText(msg, _subtype='html')
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
            s.starttls()
            s.ehlo(ehlo)
            s.login(user, pass_)
            sresponse = s.sendmail(from_, bcc, msg.as_string())
            qresponse = s.quit()
            print('server response: {}'.format(sresponse))
            status='sent'
        except Exception as e:
            print('Failed to send message to recipients: {}'.format(e))
            status='failed to send'

        for r in bcc:
            send_results.append( (r, 'no-id', now, status) )

        return send_results

    def deliver_twitter(self, id, rx, msg):
        ''' gateway gives us plenty of info, what we want out of it is:
              date_created, error_code, error_message, sid, status
              
              we'll parse the error_code and error_message and return them in sendStatus
        '''
        
        send_results = []
        twitter_app_key            = self.config.get('Twilio', 'twitter_app_key')
        twitter_app_secret         = self.config.get('Twilio', 'twitter_app_secret')
        twitter_oauth_token        = self.config.get('Twilio', 'twitter_oauth_token')
        twitter_oauth_token_secret = self.config.get('Twilio', 'twitter_oauth_token_secret')

        twitter_client        = Twython(twitter_app_key, twitter_app_secret, twitter_oauth_token, twitter_oauth_token_secret)

        args = {'status':msg}
        medias = []
        for rk in sorted(media_urls):
            # search for cues in nature and notes
            if re.search(rk, self.evdict['nature']) or re.search(rk, self.evdict['notes']):
                print('found key:',rk)
                media = media_urls[rk]
                print('got media id ',media)
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
                            print('Error uploading to twitter: {}'.format(e))
                            retries -= 1
                            continue


        if medias:
            args['media_ids'] = set(medias)
        
        print('args is',args)
        
        try:
            response = twitter_client.update_status(**args)
            send_results.append( ('twitter', response['id'], response['created_at'], 'posted') )
        except Exception as e:
            send_results.append( ('twitter', 'HTTP error', datetime.datetime.now(), 'failed: {}'.format(e)) )

        return send_results

