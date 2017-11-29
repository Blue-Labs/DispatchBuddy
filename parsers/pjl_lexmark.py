
'''
This Parser designed to interpret the PCL emulation engine for the Lexmark E120 printer

https://publications.lexmark.com/publications/pdfs/techref_WB.pdf
http://www.kudesnik.net/bin/PCL5E.PDF (a little detailed with font download information)
http://www.undocprint.org/_media/formats/page_description_languages/hl-series_techreference_revc_oct1994.pdf (describes soft font download in high detail; pg.64)

tangent note: https://opensource.apple.com/source/cups/cups-136/cups/filter/rastertolabel.c for pcl compression

'''

import os, io, re, time, sys
import struct
import string
import logging
import traceback

from dateutil import parser
from collections import namedtuple, OrderedDict

from fontTools.agl import UV2AGL
from fontTools.ttLib import TTFont

glyphset                 = TTFont('/usr/share/fonts/TTF/arialbd.ttf').getGlyphSet()
ascii_to_agl             = {c:UV2AGL[ord(c)] for c in string.printable[:-6]}
glyf_coordinate_to_ascii = {
                            0: {glyphset[ascii_to_agl[c]]._glyph.compileCoordinates():c  # default font
                                for c in string.printable[:-6]},
                           }


# PJL implementation of PCL
class PCLParser():
    t_parameter      = range(33, 48)                 # Parameterized characters range                '!' - '/'
    t_group          = range(96, 127)                # Group characters range                        '`' - '~'
    t_termination    = range(64, 90)                 # Termination characters range                  '@' - 'Z'
    formfeed         = b'\x0c'                       # Form feed
    ESC              = b'\x1b'
    UEL              = b'%-X'
    pcl_to_hpgl2     = b'%B'
    hpgl2_to_pcl     = b'%A'
    reset_pcl_engine = b'E'                     # resets
    reset_lrmargins  = b'9'
    printable        = list(range(8,14)) +list(range(32+3, 127+3))

    parse_error      = False

    # state tracking
    in_pcl           = True
    current_character_data_code = 0
    current_font_id  = 0

    # positional
    last_x		 = 0
    last_y		 = 0
    pcl_units            = 1 # basically, dots per inch

    # PCL color palette
    pcl_color_palette    = {n:0 for n in range(8)}
    pcl_color            = {'R':0, 'G':0, 'B':0} # RGB

    requiresdata = (b"&pX", b"(sW", b")sW", b"*bW", b'*vW', b'*oW', b'(', b')')  # List of known commands that required data section
    f = {a:None for a in ('stream', 'length', 'position')}

    simplex =                       ( "simplex", "duplex - long edge", "duplex - short edge" )
    duplex_side =                   ( "next side", "front side", "back side" )
    job_separation =                ( "disable", "enable" )
    output_bin_selection =          ( "", "Upper output bin", "Lower (rear) output bin" )
    page_size =                     ( "", "Executive", "Letter" )        # this is a sparse array and not populated beyond #2, goes up to 100. see pg.72 of the techref
    paper_source =                  ( "currently selected", "a printer specific tray", "paper -> manual input", "envelope -> manual input", "paper -> lower tray", "paper -> optional source", "envelope from optional feeder" )
    orientation =                   ( "Portrait", "Landscape", "Reverse portrait", "Reverse landscape" )
    perforation_skip =              ( "disable", "enable" )
    push_pop_cursor_position =      ( "store", "retrieve" )
    enter_hpgl_mode =               ( "Position pen at previous HP-GL/2 pen position", "Position pen at current PCL cursor position" )
    enter_pcl_mode =                ( "Position pen at previous PCL cursor position", "Position pen at current HP-GL/2 pen position" )
    manage_palette =                ( "delete all palettes except those in the stack", "delete all palettes in the stack", "delete palette specified by the palette control ID", "", "", "", "copy active palette to ID specified by palette control ID" )
    raster_img_print_direction =    ( "use current print direction", "print along width of physical page" )
    pattern_mask =                  ( "solid black", "solid white", "shading", "cross-hatch", "user defined" )
    pattern_opacity =               ( "transparent", "opaque" )
    source_img_opacity =            ( "transparent", "opaque" )
    start_raster_position =         ( "left graphics margin (0)", "current cursor position (X)" )
    sscc =                          ( "delete all temp and perm user def sym set", "delete all temp", "delete all current", "make current set temporary", "make current set permanent")

    fonts = {
    }

    font_header_formats = {
      0: 'PCL Bitmapped Fonts',
      10: 'Intellifont Bound Scalable',
      11: 'Intellifont Unbound Scalable',
      15: 'TrueType Scalable',
      20: 'Resolution-Specified Bitmapped',
    }

    font_type = {
      0: 'Bound font. Character codes 32 to 127 [decimal] are printable',
      1: 'Bound font. Character codes 32 to 127 [decimal] and 160 to 255 [decimal] are printable',
      2: 'Bound font. All character codes 0 to 255 are printable, except 0, 7 to 15, and 27 [decimal] (see note below)',
      10: 'Unbound font. Character codes correspond to HP MSL numbers (for Intellifont unbound scalable fonts)',
      11: 'Unbound font. Character codes correspond to Unicode numbers (for TrueType unbound scalable fonts)',
    }


    # glyph data in printout is now loaded only for the rectangle being printed and RELOADED for the
    # next rectangle. this means nothing can be hardcoded. we MUST match based on the font data. at
    # present, i have no other way to glean which character is being printed. unfortunately this is
    # horribly inefficient
    #
    #  update: most of the time i can guess the correct symbol and use a global offset
    #          but this is rather fragile
    _c = namedtuple('Glyph', ['x_max','pc',])
    glyph_data_blocks = {
      0: {},
     -1: { 37:_c(x_max=428, pc=':'),
           38:_c(x_max=428, pc=' '),
           30:_c(x_max=428, pc='X1'),
           31:_c(x_max=428, pc='X2'),
           32:_c(x_max=428, pc='A'),
           33:_c(x_max=428, pc='\\d'),
           39:_c(x_max=428, pc='C'),
           40:_c(x_max=428, pc='o'),
           43:_c(x_max=428, pc='\\u'),
           58:_c(x_max=428, pc='\\d'),
           59:_c(x_max=428, pc='\\>'),
           88:_c(x_max=428, pc='\\?'),
         },                                        # educated guess
    }

    # common defined escape sequences
    # http://www.manualslib.com/manual/277365/Hp-Pcl-5.html
    # http://www.hp.com/ctg/Manual/bpl13210.pdf
    # http://www.piclist.com/techref/language/pcl/decoded.htm
    # https://www.lexmark.com/publications/pdfs/v15104844_en.pdf
    escs = {
            b'9':     ("Reset L/R margins", None),
            b'E':     ("Reset print engine", None),

            b'%-X':   ("UEL; universal exit", None),
            b'%B':    ("Enter HP-GL/2 mode", enter_hpgl_mode),
            b'%A':    ("Return to PCL mode", enter_pcl_mode),

            b'&aC':   ("Horizontal cursor position (Columns)", None),
            b'&aG':   ("Duplex side selection", duplex_side),
            b'&aH':   ("Horizontal cursor position (Decipoints)", None),
            b'&aL':   ("Left margin", None),
            b'&aM':   ("Right margin", None),
            b'&aP':   ("Print direction (deg. of rotation)", None),
            b'&aR':   ("Vertical cursor position (Rows)", None),
            b'&aV':   ("Vertical cursor position (Decipoints)", None),
            b'&cS':   ("[1;31munknown command[0m", None),                         # unknown
            b'&d@':   ("Turn underline off", None),
            b'&eF':   ("[1;31munknown command[0m", None),                         # unknown
            b'&fS':   ("Push/Pop cursor position", push_pop_cursor_position),
            b'&kH':   ("Horizontal motion index", None),
            b'&lA':   ("Page size", page_size),
            b'&lC':   ("Vertical motion index", None),
            b'&lD':   ("Line spacing", None),
            b'&lE':   ("Top margin", None),
            b'&lF':   ("Text length", None),
            b'&lG':   ("Output bin selection", output_bin_selection),
            b'&lH':   ("Paper source", paper_source),
            b'&lL':   ("Perforation skip", perforation_skip),
            b'&lO':   ("Orientation", orientation),
            b'&lS':   ("Simplex/Duplex", simplex),
            b'&lT':   ("Job separation", job_separation),
            b'&lU':   ("Left offset registration (decipoints)", None),
            b'&lX':   ("#copies", None),
            b'&lZ':   ("Top offset registration (decipoints)", None),
            b'&pC':   ("Manage palette", manage_palette),
            b'&pI':   ("Set ID to be used for palette control command", None),
            b'&pS':   ("Set active palette ID", manage_palette),
            b'&uD':   ("PCL Units of measure per inch", None),

            b'*bM':   ("Determines how the printer interprets (decodes) compressed binary data in the Transfer Raster Data command", None),	# pg.23 of bpl13205.pdf
            b'*bW':   ("Transfer raster data (load bytes)", None),
            b'*cD':   ("Specify an ID to be used in subsequent font commands", None),
            b'*cE':   ("The Character Code command establishes the decimal code that is associated with the next character downloaded", None),
            b'*cQ':   ("Delete all patterns, temporary and permanent", None),
            b'*cS':   ("Symbol Set Control Command", None),
            b'*cT':   ("HP GL Picture anchor point = current position", None),
            b'*cX':   ("HP GL Picture frame width # decipoints", None),
            b'*cY':   ("HP GL Picture frame height # decipoints", None),
            b'*lO':   ("Set logic operation to be performed", None),	# (do printing) see table 2-6 of bpl13206.pdf (pg 66+)
            b'*lR':   ("Pixel placement", None),
            b'*oW':   ("Colour treatment", None),
            b'*pX':   ("Horizontal cursor position (PCL units)", None),
            b'*pY':   ("Vertical cursor position (PCL units)", None),
            b'*rA':   ("Start raster graphics", start_raster_position),
            b'*rB':   ("End raster graphics", None),
            b'*rC':   ("End raster graphics, as *rB plus: Set compression mode and left margin to 0", None),
            b'*rJ':   ("Render Raster Image method", None),
            b'*rF':   ("Printing direction of raster data", raster_img_print_direction),
            b'*rS':   ("Specifies the width in pixels of the raster picture area", None),
            b'*rT':   ("Specifies the height in raster rows (pixels) of the raster picture area", None),
            b'*tH':   ("PCL Raster Graphis; scaled raster width", None),
            b'*tR':   ("Set graphics resolution", None),
            b'*tS':   ("[1;31munknown command[0m", None),                         # unknown
            b'*tV':   ("PCL Raster Graphis; scaled raster height", None),
            b'*vA':   ("set color component 1:", None),
            b'*vB':   ("set color component 2:", None),
            b'*vC':   ("set color component 3:", None),
            b'*vI':   ("assign color components to palette index:", None),
            b'*vN':   ("Set source image opacity", source_img_opacity),
            b'*vO':   ("Pattern opacity", pattern_opacity),
            b'*vS':   ("Set foreground color to this index of the curent palette", None),
            b'*vT':   ("Apply this pattern to source", pattern_mask),
            b'*vW':   ("Configure color image data; (load bytes)", None),	# bpl13205.pdf pg.24

            b'(X':    ("select primary font by ID", None),
            b'(sV':   ("PCL Font Spacing; Point Size", None),
            b'(sW':   ("Load character data", None),					# (do printing)
            b')sW':   ("Download font header", None) # (do printing)
            }

    def __init__(self, logger=None, id=None):
        if not logger:
            print('instantiating our OWN LOGGER')
            logging.basicConfig()
            logger = logging.getLogger('bluelabs.dispatchbuddy.parsers.pjl_lexmark')
            logger.setLevel(logging.DEBUG)
        self.logger   = logger
        self.id       = id

    def load(self, data=None, filename=None):
        if not (data or filename):
            raise Exception ("specify a data block or filename to load from")
            return

        self.filename=filename
        if data:
            self.pclblob = io.BytesIO(data)

        else:
            try:
                s = os.stat(self.filename)
            except (OSError, IOError) as e:
                raise Exception ("error accessing {}, {}, {}".format(self.filename, e.errno, e))
                return

            with open(self.filename, "rb") as file:
                self.pclblob = io.BytesIO(file.read())
                file.close()

        self.pclblob.seek(io.SEEK_SET, 0)


    def is_cmd(self, string):
        return string in self.escs


    def esc_callback(self, cmd, value):
        title, textra = self.escs.get(cmd, ('[1;36munknown command[0m', None))
        if title == '[1;36munknown command[0m':
            self.logger.warning('command is unknown: {!r} {!r}'.format(cmd, value))

        cmds = str(cmd, encoding='ascii')
        value = str(value, encoding='ascii')

        if self.in_pcl:
            cword = "\x1b[1;32mcommand\x1b[0m:"
        else:
            cword = "\x1b[0;32mcommand\x1b[0m:"

        self.logger.debug('{} ({}; {}) {}'.format(cword, cmds, value, title))

        if cmd == b'*lO' and value == b'252':
            self.logger.debug('[1;30mSTART PRINTING SOMETHING [0m')

        elif cmd in (b'(', b')'):
            if cmd == b'(':
                self.logger.debug('choose primary font, symbol set ID {}'.format(value))
            else:
                self.logger.debug('choose secondary font, symbol set ID {}'.format(value))

        elif cmd in (b'&uD',):
            self.logger.debug('Setting DPI to {}'.format(value))
            self.pcl_units = int(value)

        elif cmd == b'*pY':
            self.logger.debug('move cursor to Y{}, {}in'.format(value, int(value)/self.pcl_units))
            self.last_y = int(value)

        elif cmd == b'*pX':
            self.logger.debug('move cursor to X{}, {}in'.format(value, int(value)/self.pcl_units))
            self.last_x = int(value)

        elif cmd in (b'*vA', b'*vB', b'*vC'):
            #self.logger.debug('set PCL color component: {}'.format(value))
            self.pcl_color[('R','G','B')[65-cmd[-1]]] =int(value)

        elif cmd == b'*vI':
            self.pcl_color_palette[int(value)] = self.pcl_color
            self.logger.debug('set PCL color palette[{}] to {}'.format(int(value), self.pcl_color))

        elif cmd == self.UEL:
            self.logger.debug('Exiting PCL engine')
            self.in_pcl = False

        elif cmd == self.reset_pcl_engine:
            self.logger.debug('Entering PCL engine')
            self.in_pcl = True

        elif cmd == self.pcl_to_hpgl2:
            self.logger.debug('Exiting PCL engine, consume until ESC')
            self.in_pcl = False

        elif cmd == self.hpgl2_to_pcl:
            self.logger.debug('Entering PCL engine')
            self.in_pcl = True

        elif cmd == b'*cD':
            self.current_font_id = int(value)
            if not self.current_font_id in self.fonts:
                self.fonts[self.current_font_id] = None
            if not self.current_font_id in self.glyph_data_blocks:
                self.glyph_data_blocks[self.current_font_id] = {}

        elif cmd == b'(X':
            self.current_font_id = int(value)

        # *cE downloads a character from the host into the printer
        elif cmd == b'*cE':
            self.logger.debug('load char to printer: {}'.format(value))
            self.current_character_data_code = int(value)

        # load character, pg 11-67 of http://data.manualslib.com/pdf2/28/2774/277365-hp/pcl_5.pdf?d37285c979e3d5cb83a8156d3f1e8b66
        elif cmd == b'(sW':
            # read character data
            # character data block header
            _format =  ord(self.read_block(1))
            _continuation = self.read_block(1) == b'\x01'
            _descriptor_size = ord(self.read_block(1))
            _class = ord(self.read_block(1))

            if _descriptor_size == 2:
                _descriptor_additional = ''
            else:
                _descriptor_additional = self.read_block(_descriptor_size-2)

            _data_size = struct.unpack('>H', self.read_block(2))[0]
            _glyph_id = struct.unpack('>H', self.read_block(2))[0]

            rd_size = _data_size-4
            self.logger.debug('read 10+{} bytes of glyph data, continuation:{}'.format(rd_size, _continuation))

            if rd_size > 0:
                # read glyph data table
                _gd_number_of_contours = struct.unpack('>h', self.read_block(2))[0]
                _gd_x_min = struct.unpack('>h', self.read_block(2))[0]
                _gd_y_min = struct.unpack('>h', self.read_block(2))[0]
                _gd_x_max = struct.unpack('>h', self.read_block(2))[0]
                _gd_y_max = struct.unpack('>h', self.read_block(2))[0]
                _glyph_data = self.read_block(_data_size-4-10).rstrip(b'\x00')

                self.read_block(1) # terminating \0 and reserved byte
                _glyph_checksum = ord(self.read_block(1))
            else:
                _gd_number_of_contours = 0
                _gd_x_min = 0
                _gd_y_min = 0
                _gd_x_max = 0
                _gd_y_max = 0
                _glyph_data = b''
                _glyph_checksum = 0

            _c = namedtuple('Glyph', ['format','descriptor_size','class_','id','pc',
                                      'data_size','contours',
                                      'x_min','y_min','x_max','y_max',
                                      'data'])

            _print = None
            if not self.current_font_id in glyf_coordinate_to_ascii:
                glyf_coordinate_to_ascii[self.current_font_id] = {}

            try:
                pc = glyf_coordinate_to_ascii[self.current_font_id].get(_glyph_data)
                if not pc:
                    pc = glyf_coordinate_to_ascii[0][_glyph_data]
            except KeyError:
                _print = True
                try:
                    # blind assumption based on past knowledge
                    pc = chr(_glyph_id+29)
                    glyf = [k for k,v in glyf_coordinate_to_ascii[self.current_font_id].items() if v == pc][0]
                    del glyf_coordinate_to_ascii[self.current_font_id][glyf]
                    glyf_coordinate_to_ascii[self.current_font_id][_glyph_data] = pc
                    self.logger.warning('replaced glyph data for \'{}\' in font set {}'.format(pc, self.current_font_id))
                except IndexError:
                    _print = False
                    glyf_coordinate_to_ascii[self.current_font_id][_glyph_data] = pc
                    self.logger.error('glyf data not found, adding chr(\'{}\') to font set {}'.format(pc, self.current_font_id))

            C = _c(_format, _descriptor_size, _class, _glyph_id, pc,
                   _data_size, _gd_number_of_contours,
                   _gd_x_min, _gd_y_min, _gd_x_max, _gd_y_max,
                   _glyph_data)
            print(C)

            if _print is None:
                self.logger.debug('Glyph(\x1b[1;36m{!r}\x1b[0m) stored at index[{}/{}]'.format(pc, self.current_font_id, self.current_character_data_code))
            elif _print is True:
                self.logger.debug('Glyph(\x1b[1;36m{!r}\x1b[0m) (by assumption) stored at index[{}/{}]'.format(pc, self.current_font_id, self.current_character_data_code))
            elif _print is False:
                self.logger.debug('Glyph(\x1b[1;36m{!r}\x1b[0m) (by wild assumption) stored at index[{}/{}]'.format(pc, self.current_font_id, self.current_character_data_code))

            self.glyph_data_blocks[self.current_font_id][self.current_character_data_code] = C
            return

        # update PCL absolute horizontal position
        elif cmd == b'*pX':
            self.last_x = int(value)

        # update PCL absolute vertical position
        elif cmd == b'*pY':
            self.last_y = int(value)


        # raster things
        elif cmd == b'*bM':
            decoder = {0:'Default (uncoded)', 1:'RLE', 2:'TIFF', 3:'DeltaRow'}[int(value)]
            self.logger.debug('use {} decoding on raster data'.format(decoder))

        # we don't do anything with this data right now
        if cmd in self.requiresdata:

            if cmd == b'*bW':
                raster_data = self.read_block(int(value))
                return

            elif cmd == b')sW':
                # read font data
                fh = OrderedDict()

                fh['font_descriptor_size']       = struct.unpack('>H', self.read_block(2))[0]
                _ = ord(self.read_block(1))
                fh['header_format']              = '{}/{}'.format(_, self.font_header_formats[_])
                _ = ord(self.read_block(1))
                fh['font_type']                  = '{}/{}'.format(_, self.font_type[_])
                fh['style']                      = ord(self.read_block(1)) << 8
                self.read_block(1) # reserved
                fh['baseline_position']          = struct.unpack('>H', self.read_block(2))[0]
                fh['cell_width']                 = struct.unpack('>H', self.read_block(2))[0]
                fh['cell_height']                = struct.unpack('>H', self.read_block(2))[0]
                fh['orientation']                = {0:'portrait',1:'landscape',2:'rev. portrait',3:'rev landscape'}[ord(self.read_block(1))]
                fh['spacing']                    = {0:'fixed',1:'proportional'}[ord(self.read_block(1))]
                _ = struct.unpack('>H', self.read_block(2))[0]
                _set = _ >> 5
                _term = chr((_ & 31) + 64)
                fh['symbol_set']                 = '{}{}'.format(_set,_term)
                _ = struct.unpack('>H', self.read_block(2))[0]
                fh['pitch']                      = _
                fh['height']                     = struct.unpack('>H', self.read_block(2))[0]
                fh['x-height']                   = struct.unpack('>H', self.read_block(2))[0]
                fh['width_type']                 = ord(self.read_block(1))
                fh['style']                     |= ord(self.read_block(1))

                _v = fh['style']
                posture          =  _v & 0b11
                appearance_width = (_v & 0b11100) >> 2
                structure        = (_v & 0b1111100000) >> 5

                posture = {0:'upright', 1:'italic', 2:'alt italic', 3:'reserved'}[posture]

                fh['style'] = posture

                appearance_width = {
                  0: 'normal',
                  1: 'condensed',
                  2: 'compressed or extra condensed',
                  3: 'extra compressed',
                  4: 'ultra compressed',
                  5: 'reserved',
                  6: 'extended or expanded',
                  7: 'extra extended or extra expanded',
                }[appearance_width]

                structure = {
                  0: 'solid',
                  1: 'outline',
                  2: 'inline',
                  3: 'contour, distressed',
                  4: 'solid w/ shadow',
                  5: 'outline w shadow',
                  6: 'inline w/ shadow',
                  7: 'contour w/ shadow',
                  8: 'patterned (complex)',
                  9: 'patterned (complex)',
                  10: 'patterned (complex)',
                  11: 'patterned (complex)',
                  12: 'patterned w/ shadow',
                  13: 'patterned w/ shadow',
                  14: 'patterned w/ shadow',
                  15: 'patterned w/ shadow',
                  16: 'inverse',
                  17: 'inverse in open border',
                  18: '',
                  19: '',
                  20: '',
                  21: '',
                  22: '',
                  23: '',
                  24: '',
                  25: '',
                  26: '',
                  27: '',
                  28: '',
                  29: '',
                  30: '',
                  31: 'unknown',
                }[structure]

                fh['style'] = ', '.join([posture, appearance_width, structure])

                fh['stroke_weight']              = ord(self.read_block(1))
                fh['typeface']                   = ord(self.read_block(1))
                fh['typeface']                  |= (ord(self.read_block(1)) << 8)
                fh['serif_style']                = ord(self.read_block(1))
                fh['quality']                    = {0:'draft',1:'nlq',2:'lq'}[ord(self.read_block(1))]
                fh['placement']                  = ord(self.read_block(1))
                fh['underline_position']         = ord(self.read_block(1))
                fh['underline_thickness']        = ord(self.read_block(1))
                fh['text_height']                = struct.unpack('>H', self.read_block(2))[0]
                fh['text_width']                 = struct.unpack('>H', self.read_block(2))[0]
                fh['first_code']                 = struct.unpack('>H', self.read_block(2))[0]
                fh['last_code']                  = struct.unpack('>H', self.read_block(2))[0]
                fh['pitch_extended']             = ord(self.read_block(1))
                fh['height_extended']            = ord(self.read_block(1))
                fh['cap_height']                 = struct.unpack('>H', self.read_block(2))[0]
                fh['font_number']                = struct.unpack('>I', self.read_block(4))[0]

                _num = fh['font_number'] & 0xffffff
                _vend = (fh['font_number'] >> 24) & 0b1111111
                _vend = {65:'Adobe',66:'Bitstream',67:'&AFGA;',72:'Bigelow & Holmes',76:'Linotype',77:'Monotype'}[_vend]
                _nat  = fh['font_number'] >> 31

                fh['font_number'] = '{}; {}; {}'.format(_num, _vend, _nat >0)

                fh['font_name']                  = (self.read_block(16)).strip(b'\0').decode()

                fh['scale_factor']               = struct.unpack('>H', self.read_block(2))[0]
                fh['master_underline_position']  = struct.unpack('>H', self.read_block(2))[0]
                fh['master_underline_thickness'] = struct.unpack('>H', self.read_block(2))[0]
                fh['font_scaling_technology']    = ord(self.read_block(1))
                fh['variety']                    = ord(self.read_block(1))
                fh['additional']                 = self.read_block(fh['font_descriptor_size']-72)

                for k,v in fh.items():
                  self.logger.debug('{:<32}: {}'.format(k,v))

                self.logger.debug('reading {} bytes of font data'.format(int(value) - fh['font_descriptor_size']-2))
                fh['segments'] = self.read_segmented_font_data(int(value) - fh['font_descriptor_size']-2)

                fh['reserved']                   = ord(self.read_block(1))
                fh['checksum']                   = ord(self.read_block(1))

                self.fonts[self.current_font_id] = fh

            else:
                self.read_block(int(value), cmd)


    def read_segmented_font_data(self, size):
        _t = {}

        # segmented font data has three parts, the SI, SS, and DS. segmented font data must terminate
        # with a null segment. if not, the font is invalidated

        # SI segment indicator
        # SS segment size
        # DS data segment


        segments = []
        do_break = False
        while size:
            SI = self.read_block(2)
            self.logger.debug('SI: {!r}'.format(SI))

            # sometimes we get broken font data. i hope this is a one-of issue
            if SI in (b'\xff\xff',b'\x00\x00'):
                do_break = True
            else:
                SI = SI.decode()

            ss = 0
            if not SI == b'\x00\x00':
                print('reading size')
                ss = struct.unpack('>H', self.read_block(2))[0]
            else:
                self.unread_block(1) # manual intervention for possibly corrupt font file
            size -= 4;

            self.logger.debug('segment: (size rem:{}) SI:{} ss:{}'.format(size, SI, ss))

            if do_break:
                self.logger.debug('TTF table parsing finished')
                break

            if size <= 0:
                self.logger.warning('table parsing failed, SI is funny!')

            if SI == 'PA':  # skip, this is only 1 10 byte entry...?
                ds = self.read_block(ss)
                size -= ss
            elif SI == 'GT': # Global TrueType Data
                # read the Table Directory
                _scaler = struct.unpack('>I', self.read_block(4))[0]
                _numTables = struct.unpack('>H', self.read_block(2))[0]
                _searchRange = struct.unpack('>H', self.read_block(2))[0]
                _entrySelector = struct.unpack('>H', self.read_block(2))[0]
                _rangeShift = struct.unpack('>H', self.read_block(2))[0]
                self.logger.debug('{} bytes remaining'.format(size))

                _tables = []
                for i in range(_numTables):
                    _tag = (self.read_block(4)).strip().decode()

                    T = namedtuple(_tag, ['checksum','offset','length','data'])
                    _t = T(struct.unpack('>I', self.read_block(4))[0],
                           struct.unpack('>I', self.read_block(4))[0],
                           struct.unpack('>I', self.read_block(4))[0],
                           '')

                    _tables.append(_t)
                    self.logger.debug('  {}'.format(_t))

                self.logger.debug('ss is {}, stack remaining size is {}'.format(ss, size))
                #size -= 12                 # table header
                #size -= len(_tables)*16    # per table

                rs = ss - 12 - len(_tables)*16
                self.logger.debug('trying to read {} bytes for {} tables'.format(rs, len(_tables)))
                blob = self.read_block(rs)
                size -= ss
                # fill in the data sections of each table
                for i,T in enumerate(_tables):
                    if not T.length:
                        continue

                    print('blob: {}-{}'.format(T.offset,T.offset+T.length))
                    off_start = T.offset - 12 - (16*len(_tables))
                    _data = blob[off_start:off_start+T.length]

                    if not len(_data) == T.length:
                      self.logger.error('WHINE AND MOAN')

                    T = T._replace(data=_data)

                    # how in hell did we overrun by 135 bytes? aka, why are we missing bytes in payload?
                    if i == 7:
                      if b'\x00\x00\r\x1b' in _data:
                          self.logger.debug(_data)
                          self.logger.error('backing up to recover ESC code, missing payload chunk')
                          try:
                            xpos = len(_data) - _data.rindex(b'\x00\x00\r\x1b') + 1
                            self.unread_block(xpos)
                          except Exception as e:
                            traceback.print_exc()

            if size < 0:
                self.logger.error('invalidate this font, data underflow')

        return segments


    '''
    find a nearby (vertical offset) line that is within 10 dot lines. this is caused by
    things like bolded fonts. the "Time Out" text is also shifted :)
    '''
    def find_nearby_line(self, here, matrix):
        for there in matrix:
            offset = abs(here-there)
            if offset < 10:
                #if offset:
                #    print('found slightly offset line, diff={} between here={} and known={}'.format(offset, here, there))
                return there
        return here


    def add_char(self, char, matrix):
        # if there's a nearby dot line, shift to it
        #self.logger.debug('add char: {}'.format(char))
        ypos     = self.find_nearby_line(self.last_y, matrix)
        #leftside = self.last_x

        #self.logger.debug('last_y:{} last_x:{} ypos:{}'.format(self.last_y, self.last_x, ypos))

        # if we haven't printed on this line, add line to matrix
        if not ypos in matrix:
            matrix[ypos] = {}

        if self.last_x in matrix[ypos]:
            # char printed on top of another -- we'll record it on the right side of the original char
            while self.last_x in matrix[ypos] and matrix[ypos][self.last_x]:
                self.last_x += 1
        matrix[ypos][self.last_x] = char
        self.last_x += (char.x_max//72)


    def unread_block(self, amount):
        self.pclblob.seek(-1*amount, io.SEEK_CUR)


    def read_block(self, amount, cmd=None, return_it=False):
        stream = self.pclblob

        if cmd == None:
            return_it = True

        # occasionally we get a stream offset
        skip_offset = stream.seek(0, os.SEEK_CUR)
        #self.logger.debug('Command {0} wants to read {1} bytes at offset: {2}'.format(cmd,amount,skip_offset))

        # if this was *bW, read until we find *rB or *rC
        position = None
        data = b''
        zlen = 0
        if cmd == b'*bW' or cmd == b'(sW':
            while 1:
                zin = stream.read(100)
                if not zin:
                    self.logger.debug('EOF?')
                    if not len(data) == amount:
                        self.block_pprint(data, amount)
                    else:
                        self.block_pprint(data)
                    break

                data += zin

                m = re.search(b'\0\0\0\0\x1b\*r(?:B|C)', data)
                if m:
                    ynow = m.start()+4
                    data = data[:ynow]
                    #self.logger.debug('found at {0}'.format(ynow))
                    zlen = len(data)
                    ynow = stream.seek(skip_offset+ynow, os.SEEK_SET)
                    #self.logger.debug('terminated by *rB or *rC at {0} bytes'.format(zlen))
                    break

                # if we're past the expected amount and we encounter an ESC, assume we're done
                if len(data) > amount:
                    if cmd == b'(sW':
                        m = re.search(b'\x1b', data[amount:])
                    else:
                        m = re.search(b'\0\0\x1b', data[amount:])
                    if m:
                        ynow = m.start()+amount
                        if not cmd == b'(sW':
                            ynow += 2
                        data = data[:ynow]

                        self.logger.debug('found some sort of escape at {0}'.format(ynow))
                        zlen = len(data)
                        ynow = stream.seek(skip_offset+ynow, os.SEEK_SET)
                        self.logger.debug('terminated by ESC after {} bytes (expected to end by {})'.format(zlen,amount))
                        break
        else:
            data = stream.read(amount)
            if return_it:
                #self.block_pprint(data)
                return data

            zlen = len(data)

            if self.ESC+b'*' in data:
                position = data.find(self.ESC+b'*')
                y2 = stream.seek(-(zlen-position), os.SEEK_CUR)
                self.logger.warning('\x1b[1;33mwarning, \\x1b* first found inside data after {} bytes; backing up to that\x1b[0m'.format(position-1, amount))

        if not zlen == amount:
            self.logger.warning('rohroh, we read {} bytes of {}'.format(zlen,amount))
            pass

        self.logger.debug('read {} bytes'.format(len(data)))

        if not len(data) == amount:
            self.block_pprint(data, amount)
        else:
            self.block_pprint(data)


    def block_pprint(self, block, olen=None):

        if olen:
           self.logger.warning('wanted {}, got {}'.format(olen, len(block)))
        if False:
            return

        _width = 32

        # make it sort of readable
        count = 0
        pblock = []

        def hexdumpline(data, width=32):
            hsx = []
            cc  = len(data)
            for _c in data:
              _c = _c == '\x1b' and '\x1b[1;41;37m1b\x1b[0m' or '{:02x}'.format(ord(_c))
              hsx.append(_c)

            hs = ' '.join(hsx)
            ts =  ''.join([31 < ord(_c) < 127 and _c or '.' for _c in data])
            return (hs,ts)

        ts = ''
        for c in block:
          if len(ts) == _width:
            # push line
            pblock.append( hexdumpline(ts, _width) )
            ts  = ''
          ts += chr(c)

        if ts:
            pblock.append( hexdumpline(ts, _width) )

        all_good = block.endswith(b'\0\0\0\0')

        if all_good:
            _last,_last_s = pblock.pop()
            _last_len = len(_last)
            if _last_len < 11:
              _2last,_2last_s = pblock.pop()
              _chop_head = _2last[:-(11-_last_len-1)]
              _chop_tail = _2last[-(11-_last_len-1):]
              _2last = _chop_head + '\x1b[1;32m'+_chop_tail + '\x1b[0m'
              pblock.append((_2last,_2last_s))
              _last = '\x1b[1;32m'+_last
            else:
              _chop_head = _last[:-11]
              _chop_tail = _last[-11:]
              _last = _chop_head + '\x1b[1;32m'+_chop_tail
            # append the checkmark
            _last += '\u2714\x1b[0m'
            pblock.append((_last,_last_s))

        if olen:
            underflow = olen-len(block)

            suffix_add = _width - len(pblock[-1][1])
            if suffix_add:
              h,a = pblock.pop()
              _t = ' '.join(['\u2610\u2610']*suffix_add)
              h += ' \x1b[1;31m'+_t+'\x1b[0m'
              a += ' '*suffix_add
              pblock.append((h,a))
              underflow -= suffix_add

            loop = (underflow//_width)
            for l in range(loop):
                _ = '\x1b[1;31m'+' '.join(['\u2610\u2610']*_width)+'\x1b[0m'
                pblock.append( (_,' '*_width) )

            underflow -= loop*_width
            if underflow:
                _ = '\x1b[1;31m'+' '.join(['\u2610\u2610']*underflow)+'\x1b[0m'
                pblock.append( (_,' '*underflow) )

        for h,a in pblock:
            if len(a) < _width:
              h +='   '*(_width-len(a))
              if all_good:
                h = h[:-1]
            self.logger.debug('{}  {}'.format(h,a))

        '''
        for c in block:
            if olen and count == olen:
                print('wtfers')
                z += ' \x1b[1;31;47m<--|-->\x1b[0m '
            count += 1

        #z = re.sub('\*rB', '\x1b[1;32m*rB\x1b[0m', z)
        #z = re.sub('0x0\s+0x0\s+', '\x1b[1;35m0x0 0x0 \x1b[0m', z)
        '''


    def parse(self):
        matrix       = {}
        stream = self.pclblob
        skip = b''
        read_count = 0
        unknown_glyph_tally = 0

        while 1:
            inb = stream.read(1)
            #self.logger.debug('stream read: {0}'.format(inb))

            if not inb:
                self.logger.debug('end of file, byebye')
                break

            if inb == self.ESC:
                parameter   = b''
                group       = b''
                termination = b''
                value       = b''

                # reset count
                unknown_glyph_tally = 0

                parameter = stream.read(1)

                if not parameter:
                    logger.debug('break on !parameter')
                    break;

                # short commands (the next character must not be a group character)
                if parameter in (b'E', b'9'):
                    self.esc_callback(parameter, value)
                    continue

                if ord(parameter) in self.t_parameter:
                    #self.logger.debug('{0} is parameterized'.format(parameter))

                    if parameter in (b'(', b')'):
                        nibble = stream.read(1)
                        #self.logger.debug('nutcheck: {!r} {!r}'.format(parameter, nibble))
                        if 97 <= ord(nibble) <= 122:
                            group = nibble
                            #self.logger.debug('group read: {0}'.format(group))
                        else:
                            # oops, not really
                            stream.seek(-1, os.SEEK_CUR)
                            group = parameter
                            parameter = b''
                    else:
                        group = stream.read(1)

                    if ord(group) in list((40,41,45,48,49)) + list(self.t_group):
                        finish_sub  = False
                        finish_seq  = False
                        while 1:
                            #self.logger.debug('{0} is grouped'.format(group))
                            value       = b''

                            # if the two HP-GL/2 commands, short circuit
                            #self.logger.debug('group is: {!r}'.format(group))
                            if group in (b'0',b'1'):
                                inz = stream.read(1)
                                self.logger.debug('GL/2 command: {0}'.format(inz))

                                if inz in (b'A',b'B'):
                                    value = group
                                    group = b''
                                    termination = inz
                                    finish_seq = True
                                    self.esc_callback(parameter+group+termination, value)
                                    break
                                else:
                                    self.logger.warning('wtf eh. unexpectedly read: {0}'.format(inz))

                                    # read until another ESC is found
                                    while 1:
                                        inz = stream.read(1)
                                        if inz == self.ESC:
                                            stream.seek(-1, os.SEEK_CUR)
                                            finish_seq = True
                                            break
                                        self.logger.debug(' unexpected: {}'.format(inz))
                                    if finish_seq:
                                        break


                            while 1:
                                inz = stream.read(1)
                                if len(inz):
                                    if ord(inz) in list((45, 46)) + list(range(48, 58)):
                                        value += inz
                                    else:
                                        stream.seek(-1, os.SEEK_CUR)
                                        #self.logger.debug('end of value: {0}'.format(inz))
                                        break


                            #self.logger.debug('{0} has a value'.format(value))

                            termination = stream.read(1)

                            # not a valid terminator?
                            if not ord(termination) in list(range(64, 91))+ list(range(97, 123)):
                                self.logger.warning('invalid terminator? {0}'.format(termination))

                                # read until another ESC is found
                                while 1:
                                    inz = stream.read(1)
                                    if inz == self.ESC:
                                        stream.seek(-1, os.SEEK_CUR)
                                        finish_seq = True
                                        break
                                    #self.logger.debug(str(inz))
                                if finish_seq:
                                    break

                            if ord(termination) in range(64, 90):
                                finish_seq = True

                            # convert to capital letter
                            elif ord(termination) in range(97, 123):
                                termination = bytes(chr(ord(termination)-32), encoding='ascii')

                            try:
                                self.esc_callback(parameter+group+termination, value)
                            except Exception as e:
                                self.logger.error('Error during parsing: {}'.format(e.__class__.__name__))
                                traceback.print_exc()
                                finish_seq = True

                            if finish_seq:
                                #self.logger.debug('finished sequence')
                                finished = True

                                break

                            #self.logger.debug('continuing')
                            continue

                        if finished:
                            #self.logger.debug('exit ESC processing')
                            continue

                    #self.logger.debug('invalid escape sequence found? {0}'.format(parameter+group+value+termination))

                else:
                    # continue until we find another ESC
                    uesc = b'\x1b'
                    while 1:
                        uesc += parameter
                        parameter = stream.read(1)
                        if parameter == self.ESC:
                            stream.seek(-1, os.SEEK_CUR)
                            break
                    self.logger.debug('unknown escape sequence: {}'.format(uesc))
            else:
                if self.in_pcl:
                    # characters are now mapped arbitrarily with no numerical relation. fuck me
                    pc = self.glyph_data_blocks[self.current_font_id].get(ord(inb))

                    if not pc: # try the manual glyph set
                        pc = self.glyph_data_blocks[-1].get(ord(inb))
                        if pc:
                            self.logger.error('had to find {} in manual font'.format(pc))
                    if not pc: # try the default glyph set
                        pc = self.glyph_data_blocks[0].get(ord(inb))
                        if pc:
                            self.logger.error('had to find {} in alt font'.format(pc))

                    # last ditch effort (this can garbage up things)
                    if not pc: # try all font sets sent to us
                        omgwtf=ord(inb)
                        x = [x for x in [self.glyph_data_blocks[fs].get(ord(inb)) for fs in self.fonts if not fs == self.current_font_id ] if x]
                        if x:
                            pc = x[0]
                            self.logger.error('had to find {} {} in alt font'.format(omgwtf, pc))

                    if pc and not unknown_glyph_tally:
                        self.logger.debug('adding to final stream: {!r}'.format(pc.pc))
                        self.add_char(pc, matrix)
                        unknown_glyph_tally=0
                    else:
                        unknown_glyph_tally += 1
                        if not pc and not inb == b'\r':
                            # no more +29 nonsense, we now lookup glyf coords to map from arialbd.ttf
                            self.logger.error('({}) unknown glyph id in set {}:\x1b[1;31m{:> 4}\x1b[0m, possibly:{:> 4}/{}'
                                .format(unknown_glyph_tally, self.current_font_id,
                                        ord(inb),
                                        (ord(inb)+29)%256, chr((ord(inb)+29)%256))
                                       )
                            for cset in glyf_coordinate_to_ascii:
                              known = ''.join(sorted([c for g,c in glyf_coordinate_to_ascii[cset].items()]))
                              self.logger.debug(' known set {:>6} \'{}\''.format(cset, known))

                else:
                    skip += inb
                    if inb == b'\n':
                        self.logger.debug('skipped: {}'.format(skip))
                        skip = b''


        self.logger.debug('printing matrix')
        #self.logger.debug(matrix)
        mline = '\n'
        for y in sorted(matrix):
            xmatrix = sorted(matrix[y])
            #mline += '{}'.format(matrix[y], end=' ')
            mline += '{}: '.format(y, end=' ')
            for x in xmatrix:
                mline += '({},{}) '.format(x,(matrix[y][x]).pc, end='')
            mline += '\n'

        self.logger.debug(mline)

        M = Matrix(self.logger, self.id)
        return M.parse_matrix(matrix)


class Matrix():
    parse_error      = False
    # set defaults and make sure all keys exist


    def __init__(self, logger=None, id=None):
        self.logger = logger

        _ = {'date'        :time.strftime('%F'),          # simple date on dispatch
             'time_out'    :time.strftime('%H:%M:%S'),    # simple time on dispatch
             'isotimestamp':None,                         # generated from date + time out
             'date_time'   :None,                         # generated from date + time out
             'nature'      :'INCOMPLETE OR DAMAGED DISPATCH', # coded nature
             'business'    :'',                           # name of business or residence
             'notes'       :'',                           # description of event
             'msgtype'     :'dispatch',                   # no longer sent in dispatches
             'cross'       :'',                           # cross streets
             'address'     :'',                           # address of incident
             'units'       :'',                           # units assigned
             'city'        :'',                           # almost always 'MER'
             'case_number' :'',                           # no longer sent in dispatches
             'gmapurl'     :'',                           # google maps address location
             'gmapurldir'  :'',                           # gmaps drive route from 31 camp st to address
             'event_uuid'  :id,                           # event UUID

             'premise'     :'',                           # reason for dispatch
             'subdivision' :'',                           #
             'ra'          :'',                           #
             }

        ev = namedtuple('Event', _.keys())
        self.ev = ev(**_)


    def ev_update(self, k,v):
        if not v:
            #self.logger.debug('blank line passed to dict_append()')
            return

        self.ev = self.ev._replace(**{k:v})


    def line_filter(self, line):
        line = re.sub('\s+', ' ', line)
        line = re.sub(':\s+', ':', line)
        line = re.sub('^\s+', '', line)
        line = re.sub('\s+$', '', line)
        if len(line):
            # use ISO 8601 ordering; MFD uses MM/DD/YY, change to YY/MM/DD
            if line.startswith('Date:'):
                m = re.search('(\d{2})/(\d{2})/(\d{2})', line)
                if m:
                    line = 'date:20'+m.group(3)+'-'+m.group(1)+'-'+m.group(2)
            elif line.startswith('Time Out:'):
                line = line.replace('Time Out:', 'time_out:')

        return line


    def post_filters(self):
        # convert to ISO 8601 date time format, then make it easy to read
        x           = parser.parse (self.ev.date + 'T' + self.ev.time_out + time.strftime('%z'))
        self.ev_update('isotimestamp', str(x))
        self.ev_update('date_time', x.strftime('%b%d, ')+x.strftime('%l:%M%P').strip())

        # add inhouse generated data
        if self.ev.address:
            address = self.ev.address
            # make the address more readable
            address = re.sub('(\sSM(?:/|$))', '', address)

            if '/' in address:
                address = re.sub('//', '/', address)
                m = address.split('/', 1)
                address = m[0] + ' & ' + m[1]

            self.ev_update('address', address.title())

            daddr = address.replace(' ', ',')
            self.ev_update('gmapurl', 'https://www.google.com/maps/place/{daddr},+Meriden,+CT+06451'.format(daddr=daddr))

            saddr = 'South+Meriden+Volunteer+Fire+Department,+31,+Camp,+Street,+Meriden,+CT+06451'
            self.ev_update('gmapurldir', 'https://www.google.com/maps/dir/{saddr}/{daddr},+Meriden,+CT+06451/data=!4m2!4m1!3e0!5m1!1e1'.format(daddr=daddr,saddr=saddr))


    def parse_matrix(self, matrix):
        prelines     = []

        #address      = None
        #cross        = None
        #nature       = None
        #notes        = None

        # prebuild lines, splitting lines where appropriate
        # x (horizontal movement) is our X,Y geometry reference. if our current X is greater than
        # x_p, we're evaluating a new line. if not, it's [possibly a slight offset]
        # on the current line so we append this text to our existing line
        for y in sorted(matrix):
            xmatrix = sorted([(x,g.pc) for x,g in matrix[y].items()])
            self.logger.debug('matrix:  {:<5} {}'.format(y, xmatrix))

            # line continuation?
            if prelines and xmatrix[0][0] > 1000:
                line = prelines.pop()+' '
                x_p = xmatrix[0][0]
                if line.startswith('Cross:'):
                   line += '& '
            else:
                line = ''
                x_p = 0

            for x,c in xmatrix:
                # large shift to right, this happens for Time Out and City (and beginning of every page line)
                if x_p and x > x_p + 400: # only push if line has already been started, otherwise we're pushing
                                          #  a blank line at the start of every char set

                    self.logger.debug('preline append: {}'.format(line))
                    prelines.append(self.line_filter(line))
                    line = ''
                try:
                  line += c
                except:
                  pass
                x_p = x

            prelines.append(self.line_filter(line))

        del (matrix)

        for pl in prelines:
            self.logger.debug('pl: {}'.format(pl))

        # now process them
        for line in prelines:
            if not line:
                continue

            self.logger.info('LINE(len={}): {!r}'.format(len(line),line))

            # if at the beginning, look for the keywords of dispatch, dispose, etc
            # otherwise:
            # test for \w+:
            # if it's a recognized keyword, process it
            # if not, check to see if we have cross or notes set, if so, append it to the appropriate line

            # ignore this word
            if line == 'END':
                #self.logger.debug('  ignored')
                continue

            # no longer applicable
            #    if line.lower() in ('dispatch','dispose','update','en route','on scene'):
            #        beginning = False
            #        self.ev_update('msgtype', line.lower())

            else:
                m = re.match('(\w+(?:\s\w+|)):(.*)', line)
                if m:
                    '''
                    # if this line matched and we were holding a bookmark for appending, delete
                    if address:
                        self.ev_update('address', address)
                        address = None
                    elif cross:
                        self.ev_update('cross', cross)
                        cross = None
                    elif nature:
                        self.ev_update('nature', nature)
                        nature = None
                    elif notes:
                        self.ev_update('notes', notes)
                        notes = None
                    '''

                    lhs = m.group(1).lower()
                    if hasattr(self.ev, lhs):
                        #self.logger.info('  LHS found')
                        '''
                        if lhs == 'address':
                            address = m.group(2)
                        elif lhs == 'cross':
                            cross = m.group(2)
                        elif lhs == 'nature':
                            nature = m.group(2)
                        elif lhs == 'notes':
                            notes = m.group(2)
                        else:
                        '''
                        self.ev_update(lhs, m.group(2))
                    else:
                        self.logger.warning('{} is an unrecognized line type: {}'.format(lhs, m.group(2)))

                # are we bookmarking lines to append?
                '''
                else:
                    if address:
                        address += line
                    elif cross:
                        cross   += ' & ' + line
                    elif nature:
                        nature  += line
                    elif notes:
                        notes   += line

                    self.logger.debug('  append this line')
                '''

        '''
        if address:
            self.ev_update('address', address)
            address = None
        elif cross:
            self.ev_update('cross', cross)
            cross = None
        elif nature:
            self.ev_update('nature', nature)
            nature = None
        elif notes:
            self.ev_update('notes', notes)
            notes = None
        '''

        self.post_filters()

        return self.ev


if __name__ == '__main__':
    P = PCLParser()
    P.load(filename=sys.argv[1])
    m=P.parse()
    print(m)
