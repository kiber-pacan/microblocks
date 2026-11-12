#!/usr/bin/env python3
"""Draws the textures of the tables and the tools (16 x 16, pixel by pixel). Run from the project folder: python3 tools/textures.py"""
import random
from PIL import Image

OUT = 'common/src/main/resources/assets/microblocks/textures/'

def rgb(h): return ((h >> 16) & 255, (h >> 8) & 255, h & 255, 255)
def mul(c, f): return tuple(max(0, min(255, int(v * f))) for v in c[:3]) + (255,)

def noisy(img, seed, amount=7, keep=()):
    r = random.Random(seed)
    px = img.load()
    for y in range(16):
        for x in range(16):
            if px[x, y][3] == 0 or (x, y) in keep: continue
            px[x, y] = mul(px[x, y], 1 + r.randint(-amount, amount) / 100)
    return img

def from_rows(rows, palette):
    img = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(rows):
        assert len(row) == 16, (y, row)
        for x, ch in enumerate(row):
            if ch != '.': img.putpixel((x, y), rgb(palette[ch]))
    return img

def half(rows):
    """Sixteen rows of eight: the right half is the left one mirrored."""
    return [row + row[::-1] for row in rows]

def quarter(rows):
    """Eight rows of eight: mirrored to the right and down, the same from all four sides."""
    top = half(rows)
    return top + top[::-1]

# ---------------------------------------------------------------- the tables
IRON = {'h': 0xEDEDED, 'a': 0xD6D6D6, 'b': 0xBDBDBD, 'c': 0xA2A2A2, 'd': 0x858585, 'e': 0x666666, 'f': 0x474747, 'w': 0x8B6234, 'x': 0x65441F, 'v': 0xAC7F48, 'k': 0x2E2E2E}
BLUE = {'h': 0xDCE8FF, 'a': 0x5B8DE0, 'b': 0x3F6FC8, 'c': 0x3360B4, 'd': 0x2A509A, 'e': 0x213F7C, 'f': 0x172C58, 'w': 0x8B6234, 'x': 0x65441F, 'v': 0xAC7F48, 'p': 0xF2EFE2, 'q': 0xCFC9B4}

iron_top = quarter([     # a plate with the grid of a crafting table cut into it; the same from every side
    'feeeeeee',
    'ehaaaaaa',
    'eabbbdbb',
    'eabbbdbb',
    'eabbbdbb',
    'eadddddd',
    'eabbbdbb',
    'eabbbdbb',
])
iron_side = [           # the plate on top, legs at the corners, a saw and a chisel hanging on the wall between them
    'ehaaaaaaaaaaaahe',
    'eabbbbbbbbbbbbce',
    'feeeeeeeeeeeeeef',
    'edccccccccccccde',
    'edchhhhhhcccwcde',
    'edcabababccwvcde',
    'edccccccccwvxcde',
    'edcccccccchxccde',
    'edccccccchaccdde',
    'edcccccccaccccde',
    'edccccccccccccde',
    'edddddddddddddde',
    'eecccccccccccdee',
    'ee............ee',
    'ed............de',
    'fe............ef',
]
iron_bottom = [
    'feeeeeeeeeeeeeef',
    'edddddddddddddde',
    'edhdccccccccdhde',
    'eddcccccccccddde',
    'edccccccccccccde',
    'edccccccccccccde',
    'edccccddddccccde',
    'edccccdeedccccde',
    'edccccdeedccccde',
    'edccccddddccccde',
    'edccccccccccccde',
    'edccccccccccccde',
    'eddcccccccccddde',
    'edhdccccccccdhde',
    'edddddddddddddde',
    'feeeeeeeeeeeeeef',
]
blue_top = quarter([     # a sheet of blueprint on the table: the white lines of a drawing on blue; the same from every side
    'feeeeeee',
    'ebbbbbbb',
    'ebchcccc',
    'ebhhhhhh',
    'ebchcccc',
    'ebchchhh',
    'ebchchcc',
    'ebchchch',
])
blue_side = half([       # blue planks, a sheet and a ruler on the wall; the left half is the right one mirrored
    'eabbbbbb',
    'ebcccccc',
    'feeeeeee',
    'edcccccc',
    'edcqpppp',
    'edcqphhh',
    'edcqpppp',
    'edcccccc',
    'edeeeeee',
    'edcccccc',
    'edcvvvvv',
    'edcvxvxv',
    'eecccccc',
    'ee......',
    'ed......',
    'fe......',
])
blue_bottom = [
    'feeeeeeeeeeeeeef',
    'edddddddddddddde',
    'edccccccccccccde',
    'edccccccccccccde',
    'edeeeeeeeeeeeede',
    'edccccccccccccde',
    'edccccccccccccde',
    'edccccccccccccde',
    'edeeeeeeeeeeeede',
    'edccccccccccccde',
    'edccccccccccccde',
    'edccccccccccccde',
    'edeeeeeeeeeeeede',
    'edccccccccccccde',
    'edddddddddddddde',
    'feeeeeeeeeeeeeef',
]

def table(rows, palette, seed, under, mirror=0):
    img = from_rows(rows, palette)
    # the space between the legs: the shadow under the table
    for y in range(16):
        for x in range(16):
            if img.getpixel((x, y))[3] == 0: img.putpixel((x, y), rgb(under))
    img = noisy(img, seed, 5)
    px = img.load()
    if mirror >= 1:
        for y in range(16):
            for x in range(8): px[15 - x, y] = px[x, y]
    if mirror >= 2:
        for y in range(8):
            for x in range(16): px[x, 15 - y] = px[x, y]
    return img

table(iron_top, IRON, 1, 0, 2).save(OUT + 'block/table_top.png')
table(iron_side, IRON, 2, 0x2B2B2B).save(OUT + 'block/table_side.png')
table(iron_bottom, IRON, 3, 0).save(OUT + 'block/table_bottom.png')
table(blue_top, BLUE, 4, 0, 2).save(OUT + 'block/blueprint_table_top.png')
table(blue_side, BLUE, 5, 0x101C38, 1).save(OUT + 'block/blueprint_table_side.png')
table(blue_bottom, BLUE, 6, 0).save(OUT + 'block/blueprint_table_bottom.png')

# ---------------------------------------------------------------- the tools
# a tool lies from the lower left corner (the handle) to the upper right (the head), as the tools of the game do
T = {'w': 0x8B6234, 'x': 0x5E3F1C, 'v': 0xB0834B, 'k': 0x3A2710,
     'h': 0xF4F4F4, 'a': 0xD8D8D8, 'b': 0xB4B4B4, 'c': 0x8E8E8E, 'd': 0x6B6B6B, 'e': 0x444444,
     'g': 0xE9C46A, 'y': 0xB8923C, 'r': 0xB5432F, 's': 0x7E2A1C}

chisel_fine = [         # a thin straight blade with a sharp point
    '..............eh',
    '.............eah',
    '............eabe',
    '...........eabe.',
    '..........eabe..',
    '.........eabe...',
    '........dcce....',
    '.......kdde.....',
    '......kvwk......',
    '.....kvwxk......',
    '....kvwxk.......',
    '...kvwxk........',
    '..kvwxk.........',
    '.kvwxk..........',
    '.kwxk...........',
    '..kk............',
]
chisel_flat = [         # a wide flat blade, the edge straight across
    '..........ehhe..',
    '.........ehaahe.',
    '........eaaabbhe',
    '.......eaaabbbae',
    '.......ebabbbce.',
    '........ebbbce..',
    '........dcbce...',
    '.......kddce....',
    '......kvwke.....',
    '.....kvwxk......',
    '....kvwxk.......',
    '...kvwxk........',
    '..kvwxk.........',
    '.kvwxk..........',
    '.kwxk...........',
    '..kk............',
]
chisel_gouge = [        # a blade bent to a half circle: a dark hollow down its middle
    '...........ehe..',
    '..........ehdah.',
    '.........eaddbhe',
    '........eaddbbae',
    '........ebdbbce.',
    '.........ebbce..',
    '........dcbce...',
    '.......kddce....',
    '......kvwke.....',
    '.....kvwxk......',
    '....kvwxk.......',
    '...kvwxk........',
    '..kvwxk.........',
    '.kvwxk..........',
    '.kwxk...........',
    '..kk............',
]
chisel_splitter = [     # a heavy wedge with a brass ring, struck on the end of its handle
    '............eee.',
    '...........ecbde',
    '..........ecbbde',
    '.........ecbbde.',
    '........ecbbde..',
    '.......ecbbde...',
    '.......ydbde....',
    '......kgyde.....',
    '.....kvwyk......',
    '....kvwxk.......',
    '...kvwxk........',
    '..kvwxk.........',
    '.ksrxk..........',
    'ksrrsk..........',
    'ksrsk...........',
    '.kkk............',
]
drill_thin = [          # a hand drill: the crank wheel with its knob, a thin twisted bit
    '..............eh',
    '.............eae',
    '............ece.',
    '...........eae..',
    '..........ece...',
    '.....ee..eae....',
    '....ebbeece.....',
    '...ebhcbde......',
    '...ebcbde..ks...',
    '....ebdeeeksrs..',
    '....kede...ksk..',
    '...kvwk.........',
    '..kvwxk.........',
    '.kvwxk..........',
    '.kwxk...........',
    '..kk............',
]
drill_wide = [          # the same drill with a broad spade bit
    '...........ehhe.',
    '..........ehaahe',
    '.........ecabbae',
    '..........ecbbe.',
    '..........ecae..',
    '.....ee..eae....',
    '....ebbeece.....',
    '...ebhcbde......',
    '...ebcbde..ks...',
    '....ebdeeeksrs..',
    '....kede...ksk..',
    '...kvwk.........',
    '..kvwxk.........',
    '.kvwxk..........',
    '.kwxk...........',
    '..kk............',
]
for name, rows in [('chisel_fine', chisel_fine), ('chisel_flat', chisel_flat), ('chisel_gouge', chisel_gouge), ('chisel_splitter', chisel_splitter), ('drill_thin', drill_thin), ('drill_wide', drill_wide)]:
    from_rows(rows, T).save(OUT + 'item/' + name + '.png')
print('done')
