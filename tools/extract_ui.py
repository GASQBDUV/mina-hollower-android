"""extract_ui.py <Resources/data dir> [outdir]   (default outdir: analysis/_ui)
Extracts the game's UI art from the user's OWN game data (never publish the output):
  fonts/<name>.png + fonts/<name>.json   bitmap fonts (Gothic7px-Bold, Gothic8px-Bold/Regular, GothicHyroglyph)
  sprites/<anb>/<sprite>[_fN].png        every UI/*.anb.yc sprite frame (cel) with its palette
  plates/locationPlate_<loc>.png         the 9-slice location plate in each of the 17 palettes (+ sample)
  index.json                             name -> {png,w,h,pivot,anb,palette}, plus <anb>/_nineslice entries
  contact_*.png                          contact sheets
Formats: see analysis/ui-assets.md."""
import sys, os, json, struct, re
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ycpak
from ycd import load_ycd
from anbfile import Anb, read_palette
from PIL import Image, ImageDraw


def load_paks(data):
    ents = {}
    for pak in ('global.pak.yc', 'global_startup.pak.yc'):
        b, es = ycpak.load(os.path.join(data, pak))
        for nm, sz, d in es:
            ents[nm] = bytes(b[d:d + sz])
    return ents


def font_convert(blob, name, out):
    """Font .font.yc: root u32 glyphCount; root+0x10 -> header; root+0x20 -> glyphs (32 B each).
    header: f32 pixelSize-1, f32 -1.0, f32 lineHeight, u32 a, u32 b, u32 atlasW, u32 atlasH, u32 0, u32 px, u32 256,
            u32 atlasBytes, u32 0, ptr -> 8bpp atlas (values 0/255), row-major W*H.
    glyph: u32 codepoint, u32 x, u32 y, u32 w, u32 h (rect incl. 2px pad), f32 adv, f32 xoff, f32 top.
           draw rect at (pen+xoff, baseline-top); pen += adv."""
    b, root, rt, ptrs = load_ycd(blob)
    cnt = struct.unpack_from('<I', b, root)[0]
    hp = struct.unpack_from('<Q', b, root + 0x10)[0]
    gp = struct.unpack_from('<Q', b, root + 0x20)[0]
    sz1, m1, lh = struct.unpack_from('<3f', b, hp)
    W, H, _, px = struct.unpack_from('<4I', b, hp + 0x14)
    nbytes = struct.unpack_from('<I', b, hp + 0x28)[0]
    ap = struct.unpack_from('<Q', b, hp + 0x30)[0]
    assert nbytes == W * H, (nbytes, W, H)
    atlas = Image.frombytes('L', (W, H), bytes(b[ap:ap + W * H]))
    rgba = Image.new('RGBA', (W, H), (255, 255, 255, 0))
    rgba.putalpha(atlas)
    rgba.save(os.path.join(out, name + '.png'))
    gl = {}
    for i in range(cnt):
        c, x, y, w, h, adv, xo, top = struct.unpack_from('<5I3f', b, gp + 32 * i)
        gl[str(c)] = dict(x=x, y=y, w=w, h=h, adv=adv, xoff=xo, top=top)
    meta = dict(name=name, atlas=name + '.png', atlasW=W, atlasH=H, pixelSize=px, lineHeight=lh, glyphs=gl,
                note='draw glyph rect (x,y,w,h) at (penX+xoff, baselineY-top); penX += adv. Atlas alpha=ink, tint with any colour.')
    json.dump(meta, open(os.path.join(out, name + '.json'), 'w'), indent=1)
    return meta


def render_text(meta, atlas, s, color=(255, 255, 255, 255)):
    gl = meta['glyphs']
    base = 12
    im = Image.new('RGBA', (400, 24), (0, 0, 0, 0))
    pen = 0
    for ch in s:
        g = gl.get(str(ord(ch)))
        if not g:
            continue
        cr = atlas.crop((g['x'], g['y'], g['x'] + g['w'], g['y'] + g['h']))
        cr = Image.composite(Image.new('RGBA', cr.size, color), Image.new('RGBA', cr.size, (0, 0, 0, 0)), cr.split()[3])
        im.alpha_composite(cr, (int(pen + g['xoff']) + 2, int(base - g['top'])))
        pen += g['adv']
    return im.crop(im.getbbox() or (0, 0, 1, 1))


def pal_for(ents, name, cache={}):
    cands = [name, 'palettes/' + os.path.basename(name)]
    cands += [k for k in ents if k.endswith('/' + os.path.basename(name))]
    for k in cands:
        if k in ents:
            if k not in cache:
                cache[k] = read_palette(ents[k])
            return cache[k], k
    return None, None


NINE = ('topL', 'top', 'topR', 'left', 'center', 'right', 'botL', 'bottom', 'botR')


def main(data, out):
    os.makedirs(out, exist_ok=True)
    ents = load_paks(data)
    index = {}
    fo = os.path.join(out, 'fonts')
    os.makedirs(fo, exist_ok=True)
    fonts = {}
    for nm in ents:
        if nm.startswith('fonts/') and nm.count('/') == 1 and nm.endswith('.font.yc'):
            short = os.path.basename(nm)[:-8]
            fonts[short] = font_convert(ents[nm], short, fo)
            print('font', short, len(fonts[short]['glyphs']))
    for nm in sorted(ents):
        if not nm.endswith('.anb.yc') or not (nm.startswith('UI/') or nm.startswith('objects/checkpointMap')):
            continue
        if nm.startswith('UI/newspaper') or nm.startswith('UI/dinnerScene'):
            continue
        anb = Anb(ents[nm])
        pal, pk = pal_for(ents, anb.palname)
        if pal is None:
            print('no palette', nm, anb.palname)
            continue
        stem = nm[:-len('.anb.yc')]
        tag = stem.replace('/', '_')
        d = os.path.join(out, 'sprites', tag)
        os.makedirs(d, exist_ok=True)
        cache = {}
        items = []
        for s in anb.sprites:
            for fi, (ci, dur) in enumerate(s['frames'][:24]):
                if ci >= len(anb.cels):
                    continue
                if ci not in cache:
                    cache[ci] = anb.cel_image(ci, pal)
                im = cache[ci]
                key = (s['name'] or 'unnamed%d' % anb.sprites.index(s)) + ('' if len(s['frames']) == 1 else '_f%d' % fi)
                fn = '%s.png' % re.sub(r'[^A-Za-z0-9_-]', lambda m: '%%%02x' % ord(m.group(0)), key)
                im.save(os.path.join(d, fn))
                c = anb.cels[ci]
                index[tag + '/' + key] = dict(png='sprites/%s/%s' % (tag, fn), w=im.width, h=im.height,
                                              pivot=list(c['pivot']), anchor=list(anb.anchor.get(ci, (0, 0))), cel=ci, dur=dur, anb=nm, palette=pk)
                items.append((key, im))
        names = {s['name'] for s in anb.sprites}
        if all(n in names for n in NINE):
            sz = {s['name']: anb.cels[s['frames'][0][0]]['canvas'] for s in anb.sprites}
            m = dict(left=int(sz['topL'][0]), top=int(sz['topL'][1]), right=int(sz['topR'][0]), bottom=int(sz['botL'][1]))
            index[tag + '/_nineslice'] = dict(margins=m, pieces={n: 'sprites/%s/%s.png' % (tag, n) for n in NINE}, anb=nm, palette=pk,
                                              note='corner cels topL/topR/botL/botR; edges top/bottom/left/right tile or stretch; center fill')
        if items:
            S = 2
            cw = max(i.width for _, i in items) * S + 8
            chh = max(i.height for _, i in items) * S + 16
            cols = max(1, min(8, 1400 // cw))
            rows = (len(items) + cols - 1) // cols
            sh = Image.new('RGBA', (cols * cw, rows * chh), (60, 40, 70, 255))
            dr = ImageDraw.Draw(sh)
            for n, (k, im) in enumerate(items):
                x, y = (n % cols) * cw, (n // cols) * chh
                sh.alpha_composite(im.resize((im.width * S, im.height * S), Image.NEAREST), (x + 4, y + 12))
                dr.text((x + 2, y), k[:max(4, cw // 6)], fill=(255, 255, 0, 255))
            sh.save(os.path.join(out, 'contact_' + tag + '.png'))
        print(nm, len(items))
    # location plates in all palettes
    anb = Anb(ents['UI/locationPlate.anb.yc'])
    po = os.path.join(out, 'plates')
    os.makedirs(po, exist_ok=True)
    plate_pals = sorted(k for k in ents if k.startswith('palettes/locationPlate') and k.endswith('.pal.yc'))
    name2cel = {s['name']: s['frames'][0][0] for s in anb.sprites}

    def build_plate(pal, w, h):
        im = Image.new('RGBA', (w, h), (0, 0, 0, 0))
        c = lambda n: anb.cel_image(name2cel[n], pal)
        m = 8
        for y in range(m, h - m, 8):
            for x in range(m, w - m, 8):
                im.alpha_composite(c('center'), (x, y))
        for x in range(m, w - m, 8):
            im.alpha_composite(c('top'), (x, 0))
            im.alpha_composite(c('bottom'), (x, h - m))
        for y in range(m, h - m, 8):
            im.alpha_composite(c('left'), (0, y))
            im.alpha_composite(c('right'), (w - m, y))
        im.alpha_composite(c('topL'), (0, 0))
        im.alpha_composite(c('topR'), (w - m, 0))
        im.alpha_composite(c('botL'), (0, h - m))
        im.alpha_composite(c('botR'), (w - m, h - m))
        return im

    fa = Image.open(os.path.join(fo, 'Gothic8px-Regular.png')).convert('RGBA')
    sheet = Image.new('RGBA', (3 * 200, 6 * 70), (30, 30, 40, 255))
    dr = ImageDraw.Draw(sheet)
    for n, pk in enumerate(plate_pals):
        pal = read_palette(ents[pk])
        loc = os.path.basename(pk)[len('locationPlate'):-len('.pal.yc')] or 'default'
        fn = 'plates/locationPlate_%s.png' % loc
        build_plate(pal, 64, 24).save(os.path.join(out, fn))
        index['locationPlate_' + loc] = dict(png=fn, w=64, h=24, anb='UI/locationPlate.anb.yc', palette=pk,
                                             note='sample 64x24 built from the 9 pieces (8px margins); tile/stretch for other sizes')
        pp = build_plate(pal, 96, 24)
        x0, y0 = (n % 3) * 200, (n // 3) * 70
        sheet.alpha_composite(pp.resize((192, 48), Image.NEAREST), (x0 + 4, y0 + 14))
        dr.text((x0 + 4, y0 + 2), loc, fill=(255, 255, 0, 255))
    sheet.save(os.path.join(out, 'contact_locationPlates.png'))
    t = Image.new('RGBA', (170, 70), (40, 30, 50, 255))
    for k, (nm, col) in enumerate((('Gothic8px-Regular', (255, 255, 255, 255)), ('Gothic8px-Bold', (255, 220, 120, 255)),
                                   ('Gothic7px-Bold', (160, 220, 255, 255)))):
        a = Image.open(os.path.join(fo, nm + '.png')).convert('RGBA')
        t.alpha_composite(render_text(fonts[nm], a, 'Hello Mina! ÅÄÖ åäö 0123', col), (4, 4 + 22 * k))
    t.resize((t.width * 5, t.height * 5), Image.NEAREST).save(os.path.join(out, 'font_sample.png'))
    json.dump(index, open(os.path.join(out, 'index.json'), 'w'), indent=1)
    print('index entries', len(index))


if __name__ == '__main__':
    data = sys.argv[1]
    out = sys.argv[2] if len(sys.argv) > 2 else os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', '_ui')
    main(data, os.path.abspath(out))
