from twython import Twython
twitter_app_key              = 'xxx'
twitter_app_secret           = 'xxx'
twitter_oauth_token          = 'xxx'
twitter_oauth_token_secret   = 'xxx'
twitter_client               = Twython(twitter_app_key, twitter_app_secret, twitter_oauth_token, twitter_oauth_token_secret)


DB_twitter_lg_icon_ids = {
    'AUTO_ACCID.png'         :'624775274966614016',
    'AUTO_ACCID_HEAD_ON.png' :'624775275499294720',
    'FIRE_ALARM.png'         :'624775276015128576',
    'FIRE_AUTO.png'          :'624775276547829760',
    'FIRE_BLDG.png'          :'624775277063700480',
    'FIRE_WOODS.png'         :'624775277642539009',
    'HAZARD_GAS.png'         :'624775278179414016',
    'INJURY.png'             :'624775278766624770',
    'LOCKOUT.png'            :'624775279295107072',
    'MVA_VS_PED.png'         :'624775279831953408',
    'RESCUE_EMS.png'         :'624775280582787072',
    'ROAD_HAZARD.png'        :'624775281132224512',
}

for img in sorted(DB_twitter_icon_ids):
  photo = open('/var/bluelabs/DispatchBuddy/images/'+img, 'rb')
  response = twitter_client.upload_media(media=photo)
  print('{:<32} is {}'.format(img, response['media_id']))
  photo.close()

#for k,v in sorted(response.items()):
#  print('{:<20} {}'.format(k,v))

#x = twitter_client.update_status(status='test message', media_ids=[response['media_id']])
#x = twitter_client.update_status(status='test message', media_ids=['624759292697120768'])

print()

for k,v in sorted(x.items()):
  print('{:<20} {}'.format(k,v))
