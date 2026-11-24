"""anb_images.py <file.anb.yc> <palette.pal.yc> <outdir>: decode all WFLZ paletted images of an .anb.yc to PNG (RGBA via palette)."""
import sys,struct,os
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from ycd import load_ycd
from wflz import wflz_decompress
from PIL import Image
def palette(path):
    b,root,rt,ptrs=load_ycd(open(path,'rb').read())
    n=struct.unpack_from('<I',b,root)[0]  # 255
    arr=struct.unpack_from('<Q',b,root+0x10)[0]
    pal=[(255,255,255,0)]+[tuple(b[arr+4*i:arr+4*i+4]) for i in range(1,256) if arr+4*i+4<=len(b)]
    return pal
def images(path):
    b,root,rt,ptrs=load_ycd(open(path,'rb').read())
    res=[]
    for p in sorted(ptrs):
        t=struct.unpack_from('<Q',b,p)[0]
        if b[t:t+4]==b'WFLZ':
            w,h=struct.unpack_from('<II',b,p-24); cs=struct.unpack_from('<I',b,p-8)[0]
            res.append((w,h,wflz_decompress(bytes(b[t:t+cs]))))
    return res
if __name__=='__main__':
    pal=palette(sys.argv[2]); os.makedirs(sys.argv[3],exist_ok=True)
    for i,(w,h,d) in enumerate(images(sys.argv[1])):
        im=Image.new('RGBA',(w,h))
        im.putdata([pal[c] if c<len(pal) else (255,0,255,255) for c in d[:w*h]])
        im.save(os.path.join(sys.argv[3],'img%02d.png'%i))
