

'''
This is our SMS gateway tool. short and simple with twilio in the house
we'll accept a message dictionary and format it for SMS and MMS messages.

this module is structured the same as sensors. startup() and threaded.
'''

def format_to_SMS(mdict):
    pass


# Download the Python helper library from twilio.com/docs/python/install
from twilio.rest import Client

# Your Account Sid and Auth Token from twilio.com/user/account
account_sid = "xxx"
auth_token  = "xxx"
client = Client(account_sid, auth_token)

message = client.messages.create(
  to="nnn",
  body="testing my foo",
  from_="nnn",
  media_url=["https://southmeriden-vfd.org/images/dispatchbuddy/AUTO_ACCID.png",
  "https://southmeriden-vfd.org/images/dispatchbuddy/ROAD_HAZARD.png"])

print(message)

for k in [k for k in sorted(dir(message)) if not k[0]=='_' ]:
  #print('key is: {}'.format(k))
  print('{:<18} : {}'.format(k,getattr(message, k)))
  #if k == 'media_list':
  #   medialist = getattr(message, 'media_list')
  #   for kk in [z for z in sorted(dir(medialist)) if not z[0]=='_' ]:
  #       print('    {}'.format(kk))
