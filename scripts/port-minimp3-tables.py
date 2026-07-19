#!/usr/bin/env python3
"""Extract minimp3.h constant tables and emit them as Kotlin (object Mp3Tables).

Mechanical extraction so no digit is ever hand-transcribed.
"""
import re, sys

SRC = sys.argv[1]
OUT = sys.argv[2]
text = open(SRC).read()

def block_for(name):
    # match "name[...]...= {" then capture to the matching closing "};"
    m = re.search(re.escape(name) + r"\s*\[[^=]*=\s*\{", text)
    if not m:
        raise SystemExit(f"table {name} not found")
    i = m.end() - 1  # at the '{'
    depth = 0
    start = i
    while True:
        c = text[i]
        if c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return text[start:i + 1]
        i += 1

def strip_comments(s):
    return re.sub(r"/\*.*?\*/", "", s, flags=re.S)

def tokens(s):
    return [t for t in re.split(r"[\s,]+", s.strip()) if t]

def flat_values(name):
    b = strip_comments(block_for(name))
    inner = b.strip()[1:-1]
    if '{' in inner:
        raise SystemExit(f"{name} is nested")
    return tokens(inner)

def nested_rows(name):
    b = strip_comments(block_for(name))
    rows = []
    depth = 0
    cur = None
    for c in b:
        if c == '{':
            depth += 1
            if depth == 2:
                cur = []
        elif c == '}':
            if depth == 2:
                rows.append(''.join(cur))
            depth -= 1
        elif depth == 2:
            cur.append(c)
    return [tokens(r) for r in rows]

def kf(tok):  # kotlin float literal
    t = tok.rstrip('fF') if tok[-1] in 'fF' else tok
    return t + 'f'

def ki(tok):
    return tok

def emit_flat(out, kname, name, kind, width=None, pad_to=None):
    vals = flat_values(name)
    if pad_to is not None:
        assert len(vals) <= pad_to, (name, len(vals))
        vals = vals + ['0'] * (pad_to - len(vals))
    conv = kf if kind == 'f' else ki
    fn = 'floatArrayOf' if kind == 'f' else 'intArrayOf'
    out.append(f"  val {kname} = {fn}(")
    line = "    "
    for v in vals:
        piece = conv(v) + ", "
        if len(line) + len(piece) > 118:
            out.append(line.rstrip())
            line = "    "
        line += piece
    out.append(line.rstrip().rstrip(','))
    out.append("  )")
    return len(vals)

def emit_nested(out, kname, name, kind, row_pad):
    rows = nested_rows(name)
    conv = kf if kind == 'f' else ki
    fn = 'floatArrayOf' if kind == 'f' else 'intArrayOf'
    out.append(f"  val {kname} = arrayOf(")
    for r in rows:
        assert len(r) <= row_pad, (name, len(r))
        r = r + ['0'] * (row_pad - len(r))
        out.append(f"    {fn}({', '.join(conv(v) for v in r)}),")
    out.append("  )")
    return (len(rows), row_pad)

def emit_nested3(out, kname, name):
    # halfrate: uint8[2][3][15] — three levels
    b = strip_comments(block_for(name))
    # split level-1 groups
    lvl1 = []
    depth = 0
    for c in b:
        if c == '{':
            depth += 1
            if depth == 2:
                cur2 = []
            if depth == 3:
                cur3 = []
        elif c == '}':
            if depth == 3:
                cur2.append(tokens(''.join(cur3)))
            if depth == 2:
                lvl1.append(cur2)
            depth -= 1
        elif depth == 3:
            cur3.append(c)
    out.append(f"  val {kname} = arrayOf(")
    for g in lvl1:
        out.append("    arrayOf(")
        for r in g:
            out.append(f"      intArrayOf({', '.join(r)}),")
        out.append("    ),")
    out.append("  )")
    return [len(g) for g in lvl1]

out = []
out.append("package com.episode6.tacita.audio")
out.append("")
out.append("/**")
out.append(" * Constant tables for [Mp3Decoder], extracted mechanically from minimp3.h")
out.append(" * (https://github.com/lieff/minimp3, CC0) by scripts/port-minimp3-tables.py.")
out.append(" * Do not edit by hand.")
out.append(" */")
out.append("@Suppress(\"MagicNumber\", \"LargeClass\")")
out.append("internal object Mp3Tables {")

sizes = {}
sizes['halfrate'] = emit_nested3(out, 'HALFRATE', 'halfrate')
sizes['g_scf_long'] = emit_nested(out, 'SCF_LONG', 'g_scf_long', 'i', 23)
sizes['g_scf_short'] = emit_nested(out, 'SCF_SHORT', 'g_scf_short', 'i', 40)
sizes['g_scf_mixed'] = emit_nested(out, 'SCF_MIXED', 'g_scf_mixed', 'i', 40)
sizes['g_expfrac'] = emit_flat(out, 'EXPFRAC', 'g_expfrac', 'f')
sizes['g_scf_partitions'] = emit_nested(out, 'SCF_PARTITIONS', 'g_scf_partitions', 'i', 28)
sizes['g_scfc_decode'] = emit_flat(out, 'SCFC_DECODE', 'g_scfc_decode', 'i')
sizes['g_mod'] = emit_flat(out, 'MOD', 'g_mod', 'i')
sizes['g_preamp'] = emit_flat(out, 'PREAMP', 'g_preamp', 'i')
sizes['g_pow43'] = emit_flat(out, 'POW43', 'g_pow43', 'f')
sizes['tabs'] = emit_flat(out, 'HUFF_TABS', 'tabs', 'i')
sizes['tab32'] = emit_flat(out, 'HUFF_TAB32', 'tab32', 'i')
sizes['tab33'] = emit_flat(out, 'HUFF_TAB33', 'tab33', 'i')
sizes['tabindex'] = emit_flat(out, 'HUFF_TABINDEX', 'tabindex', 'i')
sizes['g_linbits'] = emit_flat(out, 'HUFF_LINBITS', 'g_linbits', 'i')
sizes['g_pan'] = emit_flat(out, 'PAN', 'g_pan', 'f')
sizes['g_aa'] = emit_nested(out, 'ANTIALIAS', 'g_aa', 'f', 8)
sizes['g_twid9'] = emit_flat(out, 'TWID9', 'g_twid9', 'f')
sizes['g_twid3'] = emit_flat(out, 'TWID3', 'g_twid3', 'f')
sizes['g_mdct_window'] = emit_nested(out, 'MDCT_WINDOW', 'g_mdct_window', 'f', 18)
sizes['g_sec'] = emit_flat(out, 'DCT_SEC', 'g_sec', 'f')
sizes['g_win'] = emit_flat(out, 'SYNTH_WIN', 'g_win', 'f')

out.append("}")
open(OUT, 'w').write('\n'.join(out) + '\n')
for k, v in sizes.items():
    print(k, v)
