"""Icon scoring targets: where each pboard icon appears in the Gboard 18.6 reference set (dev phone, 1080x2400, 440 dpi)
and which Material Symbols glyphs are candidates for it.

The reference shots (docs/ref/gboard18/set, gitignored) are only a scoring target: the tools here measure how well an
official Material Symbols variant matches them; nothing is traced or copied from them.

Each target:
  const    the Symbols.kt constant now used for it (gen_symbols.py ICONS)
  old      the constant pboard drew before (in the saved copy of the old Symbols.kt), or 'hand:<state>' for the
           hand-drawn shift arrow
  glyphs   Material Symbols names searched by fit.py; fill = FILL values searched
  samples  [(shot, cx, cy)]: reference shots and the approximate icon centre in screen px; `half` = half-size of the
           crop window (it must stay inside the uniform background around the icon)
  pboard   samples in pboard's own capture of the same states (tools/icons/phone_capture.sh) when they differ from
           `samples` (other toolbar order on the phone), or [] when the capture doesn't show the icon
"""

KB_ROW3_Y = 2042   # shift / backspace row
KB_ROW4_Y = 2183   # bottom row
HEADER_Y = 1626    # toolbar icons
EMOJI_HDR_Y = 1286
EMOJI_BAR_Y = 2202
CLIP_HDR_Y = 1628


def both(shot, x, y):
    return [(f'light_{shot}', x, y), (f'dark_{shot}', x, y)]


def T(id, const, old, glyphs, fill, half, samples, pboard=None):
    return dict(id=id, const=const, old=old, glyphs=glyphs, fill=fill, half=half, samples=samples,
                pboard=samples if pboard is None else pboard)


ENTER = ['go', 'search', 'send', 'next', 'previous', 'multiline', 'done']

TARGETS = [
    # Key surface
    T('backspace', 'BACKSPACE', 'BACKSPACE', ['backspace'], [0], 46, both('qwerty_lower', 991, KB_ROW3_Y)),
    T('shift', 'hand:0', 'hand:0', ['shift'], [0], 46, both('qwerty_lower', 85, KB_ROW3_Y)),
    T('shift_on', 'hand:1', 'hand:1', ['shift'], [1], 46, both('text_autocap', 85, KB_ROW3_Y)),
    T('shift_lock', 'hand:2', 'hand:2', ['shift_lock', 'keyboard_capslock'], [1], 46,
      [('light_qwerty_capslock', 85, KB_ROW3_Y)], pboard=[]),
    T('enter_done', 'CHECK', 'CHECK', ['check'], [0], 44,
      both('qwerty_lower', 994, KB_ROW4_Y) + both('field_done', 994, KB_ROW4_Y)),
    T('enter_go', 'ENTER_GO', 'ARROW_FORWARD', ['arrow_forward', 'arrow_right_alt', 'east', 'trending_flat'], [0], 44,
      both('field_go', 994, KB_ROW4_Y)),
    T('enter_search', 'ENTER_SEARCH', 'SEARCH', ['search'], [0], 44, both('field_search', 994, KB_ROW4_Y)),
    T('enter_send', 'SEND', 'SEND', ['send'], [1], 44, both('field_send', 994, KB_ROW4_Y)),
    T('enter_next', 'TAB', 'TAB', ['keyboard_tab', 'last_page'], [0], 44, both('field_next', 994, KB_ROW4_Y)),
    T('enter_previous', 'TAB_RTL', 'ARROW_BACK', ['keyboard_tab_rtl', 'first_page'], [0], 44,
      both('field_previous', 994, KB_ROW4_Y)),
    T('enter_return', 'RETURN', 'RETURN', ['keyboard_return'], [0], 44, both('field_multiline', 994, KB_ROW4_Y)),
    T('emoji_key', 'EMOJI', 'EMOJI', ['mood', 'sentiment_satisfied'], [0], 40, both('qwerty_lower', 325, KB_ROW4_Y)),
    # Header toolbar (the phone's pboard toolbar shows grid, clipboard, settings, palette, mic)
    T('grid', 'GRID', 'GRID', ['grid_view'], [1], 40, both('qwerty_lower', 63, HEADER_Y)),
    T('sticker', 'STICKER', 'STICKER', ['sticker', 'note_stack'], [0], 40, both('qwerty_lower', 301, HEADER_Y),
      pboard=[]),
    T('gif', 'GIF', 'GIF', ['gif', 'gif_2', 'gif_box'], [0, 1], 44, both('qwerty_lower', 538, HEADER_Y), pboard=[]),
    T('clipboard', 'CLIPBOARD', 'CLIPBOARD', ['assignment', 'content_paste'], [0], 40,
      both('qwerty_lower', 775, HEADER_Y), pboard=both('qwerty_lower', 301, HEADER_Y)),
    T('mic', 'MIC', 'MIC', ['mic'], [1], 34, both('qwerty_lower', 1015, HEADER_Y)),
    # Emoji panel
    T('back', 'ARROW_BACK', 'ARROW_BACK', ['arrow_back'], [0], 36,
      both('emoji_panel', 68, EMOJI_HDR_Y) + both('clipboard_panel', 64, CLIP_HDR_Y),
      pboard=both('emoji_panel', 68, EMOJI_HDR_Y)),
    T('emoji_search', 'SEARCH', 'SEARCH', ['search'], [0], 30, both('emoji_panel', 184, EMOJI_HDR_Y)),
    T('cat_recent', 'RECENT', 'RECENT', ['schedule'], [0, 1], 34, both('emoji_panel', 510, EMOJI_HDR_Y)),
    T('cat_smileys', 'EMOJI_CATEGORY', 'EMOJI', ['mood', 'sentiment_satisfied'], [1], 34,
      both('emoji_panel', 604, EMOJI_HDR_Y)),
    T('cat_people', 'PEOPLE', 'PEOPLE', ['emoji_people'], [0, 1], 34, both('emoji_panel', 697, EMOJI_HDR_Y)),
    T('cat_nature', 'NATURE', 'NATURE', ['emoji_nature'], [0, 1], 34, both('emoji_panel', 791, EMOJI_HDR_Y)),
    T('cat_food', 'FOOD', 'FOOD', ['emoji_food_beverage'], [0, 1], 34, both('emoji_panel', 886, EMOJI_HDR_Y)),
    T('cat_travel', 'TRAVEL', 'TRAVEL', ['emoji_transportation'], [0, 1], 34, both('emoji_panel', 979, EMOJI_HDR_Y)),
    T('bar_emoji', 'EMOJI', 'EMOJI_SMILE', ['mood', 'sentiment_satisfied'], [0], 36,
      both('emoji_panel', 266, EMOJI_BAR_Y)),
    T('bar_gif', 'GIF_BAR', 'GIF', ['gif', 'gif_2', 'gif_box'], [0, 1], 40, both('emoji_panel', 446, EMOJI_BAR_Y)),
    T('bar_sticker', 'STICKER', 'STICKER', ['sticker', 'note_stack'], [0], 40, both('emoji_panel', 626, EMOJI_BAR_Y)),
    T('bar_delete', 'BACKSPACE', 'BACKSPACE', ['backspace'], [0], 46, both('emoji_panel', 984, EMOJI_BAR_Y)),
    # Clipboard panel (pboard shows no edit button in this state)
    T('clip_edit', 'EDIT', 'EDIT', ['edit'], [0, 1], 36, both('clipboard_panel', 1015, CLIP_HDR_Y), pboard=[]),
]
