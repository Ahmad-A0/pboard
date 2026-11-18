#!/usr/bin/env python3
"""Builds app/src/main/res/font/key_sans.ttf from Google Sans Flex (SIL OFL 1.1).

Google Sans Flex: https://github.com/google/fonts/tree/main/ofl/googlesansflex (licence: OFL.txt there).
The variable font is instanced at the optical size / width that best matches Gboard's key labels on the
reference device (opsz 12, wdth 90, measured with tools-side glyph-box fitting), fixed at weight 400, and
subset to Latin, Latin-1/Extended-A, combining marks, punctuation, currency and common math symbols. Other
characters fall back to the system font at runtime.

usage: font_subset.py <GoogleSansFlex[...].ttf> <out.ttf>   (needs fonttools)
"""
import sys
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from fontTools import subset

src, out = sys.argv[1], sys.argv[2]
font = TTFont(src)
font = instancer.instantiateVariableFont(font, {"opsz": 12, "wdth": 90, "ROND": 0, "slnt": 0, "wght": 400})
ranges = [(0x20, 0x7E), (0xA0, 0x17F), (0x192, 0x192), (0x2C6, 0x2DC), (0x300, 0x36F), (0x2010, 0x2044),
          (0x20A0, 0x20CF), (0x2122, 0x2122), (0x2190, 0x2199), (0x2202, 0x2264), (0x25CA, 0x25CA), (0xFB01, 0xFB02)]
unicodes = [u for a, b in ranges for u in range(a, b + 1)]
opts = subset.Options()
opts.layout_features = ["kern", "liga", "calt", "ccmp", "mark", "mkmk", "locl"]
opts.name_IDs = ["*"]
opts.notdef_outline = True
sub = subset.Subsetter(opts)
sub.populate(unicodes=unicodes)
sub.subset(font)
# Renamed so the derived font does not carry the "Google Sans" trademark (OFL modified version).
name = font["name"]
for rec in list(name.names):
    if rec.nameID in (1, 3, 4, 6, 16, 17, 21, 22, 25):
        name.removeNames(nameID=rec.nameID)
for nid, val in ((1, "Pboard Key Sans"), (4, "Pboard Key Sans Regular"), (6, "PboardKeySans-Regular"), (3, "PboardKeySans-Regular")):
    name.setName(val, nid, 3, 1, 0x409)
font.save(out)
print("saved", out)
