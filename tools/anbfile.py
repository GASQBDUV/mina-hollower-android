"""anbfile.py: generic .anb.yc reader (sprites, cels, images) + palette loader.
Layout (verified on locationPlate, worldMap, UI/*):
 root+0x08 ptr -> palette name (e.g. 'palettes/locationPlate.pal.yc')
 root+0x28 {count*16,..} root+0x30 ptr -> sprites (56 B)
 root+0x38 {..}          root+0x40 ptr -> cels (56 B)
 root+0x48 {..}          root+0x50 ptr -> images (32 B: u32 w,h,u64 0,u32 csize,pad,ptr->WFLZ)
 sprite: +0 hash,+8 {0,namelen},+16 name ptr,+24 {16*nframes,..},+32 frames ptr (frame 16 B: u32 cel,f32 dur,...),+48 locator ptr array
 cel: +8 pivot f32x2, +0x10 canvas f32x2, +0x18 {16*nslices,..}, +0x20 slices ptr (24 B: u32 w,h; f32 dstX,dstY (rel. canvas centre), srcX,srcY)
 slice source image index == cel index (verified by bounds)."""
import struct, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from ycd import load_ycd
from wflz import wflz_decompress
from PIL import Image

def read_palette(path):
    b, root, rt, ptrs = load_ycd(path if isinstance(path, (bytes, bytearray)) else open(path, 'rb').read())
    arr = struct.unpack_from('<Q', b, root + 0x10)[0]
    return [(0, 0, 0, 0)] + [tuple(b[arr + 4*i:arr + 4*i + 4]) for i in range(1, 256)]

class Anb:
    def __init__(self, path):
        self.path = path
        b, root, rt, ptrs = load_ycd(path if isinstance(path, (bytes, bytearray)) else open(path, 'rb').read())
        self.b, self.root, self.ptrs = b, root, ptrs
        g = lambda o: struct.unpack_from('<Q', b, o)[0]
        np_ = g(root + 8)
        self.palname = bytes(b[np_:np_ + 80]).split(b'\0')[0].decode('latin1')
        sp, cp, ip = g(root + 0x30), g(root + 0x40), g(root + 0x50)
        ns = struct.unpack_from('<I', b, root + 0x28)[0] // 16
        nc = struct.unpack_from('<I', b, root + 0x38)[0] // 16
        ni = struct.unpack_from('<I', b, root + 0x48)[0] // 16
        self.images = []
        for i in range(ni):
            o = ip + 32*i
            w, h = struct.unpack_from('<II', b, o); cs = struct.unpack_from('<I', b, o + 16)[0]; t = g(o + 24)
            d = wflz_decompress(bytes(b[t:t + cs])) if b[t:t+4] == b'WFLZ' else b''
            self.images.append((w, h, d))
        self.cels = []
        for i in range(nc):
            o = cp + 56*i
            px, py, cw, ch = struct.unpack_from('<4f', b, o + 8)
            n = struct.unpack_from('<I', b, o + 0x18)[0] // 16; s = g(o + 0x20)
            sl = [struct.unpack_from('<IIffff', b, s + 24*k) for k in range(n)]
            self.cels.append(dict(pivot=(px, py), canvas=(cw, ch), slices=sl))
        self.sprites = []
        for i in range(ns):
            o = sp + 56*i
            nl = struct.unpack_from('<I', b, o + 12)[0]; npp = g(o + 16)
            name = bytes(b[npp:npp + nl]).decode('latin1')
            nf = struct.unpack_from('<I', b, o + 24)[0] // 16; fp = g(o + 32)
            frames = [struct.unpack_from('<If', b, fp + 16*k) for k in range(nf)]
            self.sprites.append(dict(name=name, frames=frames))
    def cel_image(self, ci, pal):
        """Render a cel to RGBA (bbox of its slices). self.anchor[ci] = (x,y) of the cel reference point (slice coords 0,0) inside the image."""
        c = self.cels[ci]
        if ci >= len(self.images) or not self.images[ci][2] or not c['slices']:
            return Image.new('RGBA', (1, 1), (0, 0, 0, 0))
        x0 = min(int(round(s[2])) for s in c['slices']); y0 = min(int(round(s[3])) for s in c['slices'])
        x1 = max(int(round(s[2])) + s[0] for s in c['slices']); y1 = max(int(round(s[3])) + s[1] for s in c['slices'])
        out = Image.new('RGBA', (x1 - x0, y1 - y0), (0, 0, 0, 0))
        w, h, d = self.images[ci]
        at = Image.new('RGBA', (w, h)); at.putdata([pal[v] for v in d[:w*h]])
        for (sw, sh, dx, dy, sx, sy) in c['slices']:
            out.paste(at.crop((int(sx), int(sy), int(sx) + sw, int(sy) + sh)), (int(round(dx)) - x0, int(round(dy)) - y0))
        self.anchor = getattr(self, 'anchor', {}); self.anchor[ci] = (-x0, -y0)
        return out

def find_palette(raw, name):
    stem = os.path.basename(name).split('.')[0]
    for dp, dn, fn in os.walk(os.path.join(raw, 'palettes')):
        for f in fn:
            if f.split('.')[0] == stem and f.endswith('.pal.yc'): return os.path.join(dp, f)
    return None
