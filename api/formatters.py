
# static content

class Formatter():
    def __init__(self):
        pass

    @property
    def hippa_text(self):
        return '''\
        Remember, do not share personal and/or private
        information with the public. The only information you may share is
        the location and type of event. You should practice further
        discretion if the situation warrants it. You should also refer all
        questions to your commanding officer or the scene commander.

        This information is sensitive and must be protected according to all
        applicable laws, regulations, and policies including federal HIPAA
        requirements.

        "HIPAA" is an acronym for the Health Insurance Portability
        & Accountability Act of 1996 (August 21), Public Law 104-191, which
        amended the Internal Revenue Service Code of 1986.

        You may be fined up to $250K and/or imprisoned up to 10 years for
        knowing misuse of individually identifiable health information in addition
        to any state law or local ordinances per federal HIPAA regulations.'''

    @property
    def hippa_html(self):
        return '''\
        <p class=caution>Remember, do not share personal and/or private
        information with the public. The only information you may share is
        the location and type of event. You should practice further
        discretion if the situation warrants it. You should also refer all
        questions to your commanding officer or the scene commander.</p>

        <p><b>This information is sensitive and must be protected according to all
        applicable laws, regulations, and policies including federal HIPAA
        requirements.</b></p>

        <p>&ldquo;HIPAA&rdquo; is an acronym for the Health Insurance Portability
        &amp; Accountability Act of 1996 (August 21), Public Law 104-191, which
        amended the Internal Revenue Service Code of 1986.</p>

        <p>You may be fined up to $250K and/or imprisoned up to 10 years for
        knowing misuse of individually identifiable health information in addition
        to any state law or local ordinances per federal HIPAA regulations.</p>'''

    @property
    def css(self):
        return '''\
        <style type='text/css'>
          body {
             font-family:verdana, times new roman, serif;
             font-size:12pt;
             background-color:white;
             color:black;
          }

          div.ring {
             position:relative;
             -moz-border-radius:8px;
             border:4px solid rgb(128,192,255);
             width:510px;
             padding:.5em;
          }

          a.url img {
             border:none;
          }

          table.event {
             word-break:break-all;
             border-collapse:collapse;
             border:none;
             margin-right:0;
             margin-left:auto;
             margin-top:.5em;
             margin-bottom:1em;
          }

          table.event caption {
            font-size:1.5em;
          }

          table.event td {
             font-size:1em;
             vertical-align:baseline;
             border:1px solid rgb(128,192,255);
             padding:9px 9px 1px 9px;
          }

          table.event td:first-child {
             text-align:right;
             font-weight:900;
             width:10em;
          }

          div.ring div {
             position:absolute;
             top:.5em; right:.5em;
             margin:0;
             height:33px; width:28px;
          }

          div.hipaa {
             width:510px;
             padding:.5em;
             font-style:italic;
             text-align:justify;
             font-size:.67em;
             border:4px solid white;
             margin-left:2px;
          }

          p.caution {
             font-style:normal;
             color:black;
             background-color:yellow;
             border:1px solid black;
             padding:.5em;
          }

          img.meta_icon {
            display:inline-block;
          }

        </style>
        '''.replace('{', '{{').replace('}', '}}')

    def format(self, evdict):
        # generic text/plain meant to be overridden with more specific media design
        self.tmpl = '''{units} dispatched to {address} for {nature}'''
        return self.tmpl.format(**evdict)


class HTML(Formatter):
    def __init__(self, msgtype):
        self.msgtype = msgtype

    def format(self, evdict):
        self.tmpl = '''\
        <!DOCTYPE>
        <HTML>
        <head>
        {style}
        </head>
        <body>

        <div class=ring>
          <a class=url
              href="http://southmeriden-vfd.org/internal/incident-detail.php?case_number={event_uuid}"
               alt="incident detail on smfd.info"
             title="Incident Detail">

             <div><img border="0" height="33" width="28"
                alt="incident detail on smfd.info"
              title="Incident Detail"
                src="cid:{{magnify_icon_cid}}"
             ></div>
          </a>

          <table class=event>

            <caption>(Dispatch)</caption>

            <tr>
              <td colspan="2">{{meta_icons}}</td>
            </tr>
            <tr>
              <td>Timestamp    &nbsp;</td><td>{date_time}</td>
            </tr>
            <tr>
              <td>Nature       &nbsp;</td><td>{nature}</td>
            </tr>
            <tr>
              <td>Address      &nbsp;</td><td><a href="{gmapurl}">{address}<br>{gmapurl}</a></td>
            </tr>
            <tr>
              <td>Cross        &nbsp;</td><td>{cross}</td>
            </tr>
            <tr>
              <td>Directions   &nbsp;</td><td><a href="{gmapurldir}">{gmapurldir}</a></td>
            </tr>
            <tr>
              <td>Notes        &nbsp;</td><td>{notes}</td>
            </tr>
          </table>
        </div>

        <div class=hipaa>
        {hippa}
        </div>
        </body>
        </html>'''

        _ = evdict
        _.update({'style':self.css})
        _.update({'hippa':self.hippa_html})

        return self.tmpl.format(**_)


# not different from PUBLIC140 right now, maybe eventually put images and map ref?
class PUBLIC(Formatter):
    def __init__(self, msgtype):
        self.msgtype = msgtype

    def format(self, evdict):
        self.tmpl = '''{units} dispatched to {address} for {nature}'''
        return self.tmpl.format(**evdict)

# used for twitter, limited data
class PUBLIC140(Formatter):
    def __init__(self, msgtype):
        self.msgtype = msgtype

    def format(self, evdict):
        self.tmpl = '''{units} dispatched to {address} for {nature}'''
        return self.tmpl.format(**evdict)

class SMS(Formatter):
    def __init__(self, msgtype):
        self.msgtype = msgtype

    def format(self, evdict):
        self.tmpl = '''{msgtype} at {date_time}\n  {address}\n  {nature}\n--\n{notes:.90}'''
        return self.tmpl.format(**evdict)

class MMS(Formatter):
    def __init__(self, msgtype):
        self.msgtype = msgtype

    def format(self, evdict):
        self.tmpl = '''{msgtype} at {date_time}\n  {address}\n  {nature}\n--\n{notes}\n\n
Cross:\n  {cross}\n\nUnits: {units}\n\nLocation: {gmapurl}\n\nDirections: {gmapurldir}'''
        return self.tmpl.format(**evdict)
