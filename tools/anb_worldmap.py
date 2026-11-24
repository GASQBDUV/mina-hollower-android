"""anb_worldmap.py <worldMap.anb.yc> <global.pal.yc> <out.png> : rebuild the stitched world-map background (sprite 'map', cel 0).
Cel-0 slice records: 24 bytes {u32 w,u32 h,f32 dstX,f32 dstY,f32 srcX,f32 srcY}; dst is relative to map centre; src is in atlas image 0."""
import sys,struct,os
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from ycd import load_ycd
from anb_images import palette,images
from PIL import Image
def build(anb,palpath,out):
    b,root,rt,ptrs=load_ycd(open(anb,'rb').read())
    # cel table: first element at 0x21e8 (array from root+0x40): {.., w,h floats(canvas), {bytes,cnt}, ptr to slice list}
    # locate by scanning: pointer site at celbase+0x20 ; here cel0 base=0x21e8 for both known files -> use root pointer
    celbase=struct.unpack_from('<Q',b,root+0x40)[0]
    cw,ch=struct.unpack_from('<ff',b,celbase+0x10)
    nbytes,=struct.unpack_from('<I',b,celbase+0x18); sp=struct.unpack_from('<Q',b,celbase+0x20)[0]
    pal=palette(palpath); w,h,d=images(anb)[0]
    atlas=Image.new('RGBA',(w,h)); atlas.putdata([pal[c] for c in d[:w*h]])
    canvas=Image.new('RGBA',(int(cw),int(ch)),(0,0,0,0))
    recs=[]
    for i in range(nbytes//16):
        o=sp+24*i; rw,rh,dx,dy,sx,sy=struct.unpack_from('<IIffff',b,o); recs.append((rw,rh,dx,dy,sx,sy))
        canvas.paste(atlas.crop((int(sx),int(sy),int(sx)+rw,int(sy)+rh)),(int(dx+cw/2),int(dy+ch/2)))
    canvas.save(out); return canvas.size,recs
if __name__=='__main__':
    print(build(*sys.argv[1:4]))
