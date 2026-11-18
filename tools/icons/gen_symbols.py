#!/usr/bin/env python3
"""Generates app/src/main/java/dev/pboard/ime/view/Symbols.kt from the Material Symbols variable fonts (Apache-2.0).

  tools/icons/fetch_fonts.sh && tools/icons/py tools/icons/gen_symbols.py

Every icon is one official Material Symbols glyph instanced at the axis location below (style, FILL, wght, GRAD, opsz);
nothing else is drawn. The locations were chosen by measurement (tools/icons/fit.py): each candidate variant was
rasterised at pboard's size and position and scored against Gboard 18.6's rendered icons (soft IoU of anti-aliased
coverage, see fitlib.py). `score` = that match (1.0 = identical) for the chosen variant, `was` = the previous path data
(Rounded, wght 400, opsz 24 from an older font release) fitted the same way. Icons Gboard's reference set does not show
use the family default of the surface they sit on:

  KEY   Rounded wght 350 GRAD 0 opsz 28 - every enter-key glyph matches this one location at one box size (83.6 px at
        440 dpi), so it is also used for other key-surface icons without a reference.
  PANEL Rounded wght 400 GRAD 0 opsz 24 - the header, emoji-panel and clipboard-panel icons match the font default.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import symlib as S  # noqa: E402

KEY = ('Rounded', 350, 0, 28)
PANEL = ('Rounded', 400, 0, 24)


def v(glyph, fill=0, at=PANEL, note=None, score=None, was=None):
    style, wght, grad, opsz = at
    return dict(glyph=glyph, fill=fill, style=style, wght=wght, grad=grad, opsz=opsz, note=note, score=score, was=was)


# const -> variant. Scores: mean over the Gboard 18.6 light + dark samples of tools/icons/targets.py.
ICONS = {
    # Key surface
    'BACKSPACE': v('backspace', at=('Rounded', 375, -25, 32), score=0.989, was=0.733,
                   note='backspace key and the emoji bar delete'),
    'RETURN': v('keyboard_return', at=KEY, score=0.983, was=0.893),
    'CHECK': v('check', at=KEY, score=0.993, was=0.871, note='enter: done'),
    'ENTER_SEARCH': v('search', at=('Rounded', 375, -25, 30), score=0.984, was=0.874, note='enter: search'),
    'SEND': v('send', 1, at=KEY, score=0.996, was=0.985, note='enter: send'),
    'ENTER_GO': v('arrow_right_alt', at=KEY, score=0.997, was=0.781, note='enter: go (was arrow_forward)'),
    'TAB': v('keyboard_tab', at=KEY, score=0.994, was=0.885, note='enter: next'),
    'TAB_RTL': v('keyboard_tab_rtl', at=KEY, score=0.993, was=None, note='enter: previous (was arrow_back)'),
    'EMOJI': v('mood', at=('Rounded', 450, 50, 32), score=0.985, was=0.980,
               note='emoji key; also the emoji bar (0.972, was sentiment_satisfied 0.905) and toolbar item'),
    'LANGUAGE': v('language', at=KEY, note='globe key (no reference)'),
    # Shift key: drawn by Icons.drawShift (hand-drawn arrow: 0.739 / 0.955 / 0.889 for off / on / locked against the
    # best Material Symbols 0.514 shift, 0.865 shift FILL1, 0.804 shift_lock FILL1). Kept for other uses.
    'SHIFT': v('shift', at=KEY), 'SHIFT_FILLED': v('shift', 1, at=KEY), 'SHIFT_LOCK': v('shift_lock', 1, at=KEY),
    # Header toolbar
    'GRID': v('grid_view', 1, at=('Rounded', 400, 150, 20), score=0.991, was=0.932),
    'STICKER': v('sticker', score=0.986, was=0.986, note='header; emoji bar 0.805 (no variant above 0.863)'),
    'GIF': v('gif', at=('Rounded', 425, 0, 42), score=0.971, was=0.866, note='header'),
    'GIF_BAR': v('gif', at=('Outlined', 400, 50, 30), score=0.973, was=0.937, note='emoji bar'),
    'CLIPBOARD': v('assignment', at=('Rounded', 375, 75, 28), score=0.987, was=0.976),
    'MIC': v('mic', 1, score=0.993, was=0.993),
    'MIC_OFF': v('mic_off', note='voice typing unavailable in this field'),
    'SETTINGS': v('settings', 1), 'PALETTE': v('palette', 1), 'TRANSLATE': v('translate'),
    'BACK_HAND': v('back_hand'), 'TEXT_EDITING': v('settings_ethernet'), 'RESIZE': v('crop_free'),
    'SPLIT': v('vertical_split'), 'SCAN': v('document_scanner'), 'STICKY_NOTE': v('sticky_note_2'),
    'KEYBOARD': v('keyboard'), 'KEYBOARD_HIDE': v('keyboard_hide'), 'UNDO': v('undo'), 'MORE': v('more_horiz'),
    'EMOJI_SMILE': v('sentiment_satisfied'),
    # Emoji panel
    'ARROW_BACK': v('arrow_back', score=0.994, was=0.994, note='panel back buttons'),
    'SEARCH': v('search', score=0.998, was=0.998, note='emoji search pill'),
    'RECENT': v('schedule', 1, score=0.999, was=0.376, note='category tabs are filled (FILL 1)'),
    'EMOJI_CATEGORY': v('mood', 1, score=0.999, was=None, note='smileys tab (was the outlined emoji glyph)'),
    'PEOPLE': v('emoji_people', 1, score=0.995, was=0.995),
    'NATURE': v('emoji_nature', 1, score=0.993, was=0.959),
    'FOOD': v('emoji_food_beverage', 1, at=('Rounded', 400, 0, 26), score=0.993, was=0.575),
    'TRAVEL': v('emoji_transportation', 1, score=0.989, was=0.795),
    'ACTIVITIES': v('sports_and_outdoors', 1, note='filled like the tabs measured'),
    'OBJECTS': v('emoji_objects', 1, note='filled like the tabs measured'),
    'SYMBOLS': v('emoji_symbols', 1, note='filled like the tabs measured'),
    'FLAG': v('flag', 1, note='filled like the tabs measured'),
    'CLOSE': v('close'),
    # Clipboard panel and other surfaces
    'EDIT': v('edit', score=0.997, was=0.997),
    'CLIPBOARD_EMPTY': v('content_paste'), 'PASTE': v('content_paste'),
    'PIN': v('push_pin'), 'PIN_FILLED': v('push_pin', 1), 'DELETE': v('delete'),
    'SELECT_ALL': v('select_all'), 'COPY': v('content_copy'), 'CUT': v('content_cut'),
    'ARROW_DOWN': v('keyboard_arrow_down'), 'ARROW_UP': v('keyboard_arrow_up'),
    'ARROW_DOWN_KEY': v('keyboard_arrow_down'), 'ARROW_LEFT': v('keyboard_arrow_left'),
    'ARROW_RIGHT': v('keyboard_arrow_right'), 'CHEVRON_LEFT': v('chevron_left'), 'CHEVRON_RIGHT': v('chevron_right'),
    'FIRST_PAGE': v('first_page'), 'LAST_PAGE': v('last_page'), 'CANCEL_FILLED': v('cancel', 1),
    'DRAG_HANDLE': v('drag_handle'), 'ZOOM_OUT_MAP': v('zoom_out_map'), 'RESIZE_FULL': v('open_in_full'),
    # System integration (clipboard entity chips, physical keyboard menu, stickers, scan text); no Gboard reference
    'CALL': v('call', note='clipboard phone-number chip'), 'LINK': v('link', note='clipboard link chip'),
    'MAIL': v('mail', note='clipboard e-mail chip'), 'NUMBERS': v('123', note='clipboard code / number chip'),
    'LOCATION': v('location_on', note='clipboard address chip'), 'MENU': v('menu'), 'ADD': v('add'),
    'ADD_PHOTO': v('add_photo_alternate'), 'IMAGE': v('image'), 'CAMERA': v('photo_camera'),
}

OUT = os.path.join(S.REPO, 'app/src/main/java/dev/pboard/ime/view/Symbols.kt')


def describe(c):
    """KDoc lines: the glyph and its axis location, then the measured score and a note when there are any."""
    out = [f"{c['glyph']}: {c['style']} FILL {c['fill']}, wght {c['wght']}, GRAD {c['grad']}, opsz {c['opsz']}"]
    extra = []
    if c['score'] is not None:
        extra.append(f"Score {c['score']:.3f}" + (f" (was {c['was']:.3f})" if c['was'] is not None else ''))
    if c['note']:
        extra.append(c['note'][0].upper() + c['note'][1:])
    if extra:
        out.append('. '.join(extra) + '.')
    return out


def main():
    lines = [
        'package dev.pboard.ime.view',
        '',
        '/**',
        " * Path data from Google's Material Symbols variable fonts, Apache License 2.0",
        ' * (https://github.com/google/material-design-icons, see NOTICE). Viewport (0,-960)..(960,0).',
        ' * Each icon is one glyph at the axis location in its comment, chosen by measurement against Gboard 18.6.',
        ' * Generated by tools/icons/gen_symbols.py; do not edit by hand.',
        ' */',
        'internal object Symbols {',
    ]
    for const, c in ICONS.items():
        cmds = S.glyph_commands(c['style'], c['glyph'], c['fill'], c['wght'], c['grad'], c['opsz'])
        doc = describe(c)
        if len(doc) == 1:
            lines.append(f'    /** {doc[0]} */')
        else:
            lines += ['    /**'] + [f'     * {d}' for d in doc] + ['     */']
        lines.append(f'    const val {const} = "{S.to_svg(cmds)}"')
    lines.append('}')
    with open(OUT, 'w') as fh:
        fh.write('\n'.join(lines) + '\n')
    print(f'wrote {OUT} ({len(ICONS)} icons)')


if __name__ == '__main__':
    main()
