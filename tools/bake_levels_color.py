#!/usr/bin/env python
"""bake_levels_color.py <data_dir> <out_dir> [name-substr ...]
COLOR composite of each level's tile layers (see analysis/level-color.md).
 - Palette: every layer names palettes/global.pal.yc (from global_startup.pak.yc); a few layers name a
   different one (palettes/environments/*.pal.yc etc.) - those layers are drawn with their own palette.
 - Output frame == _preview.png of bake_levels.py (1 px = 1 authoring unit, origin from <level>.json).
 - <level>_color.png     opaque RGB on dark bg (18,18,28), full resolution
 - <level>_color_a.png   RGBA, transparent where nothing is drawn
 - <level>_color_half.png half-resolution RGB (always written; handy for huge levels)
Layers: parallax==0 only (parallax backdrops cannot be placed in a flat frame), sorted by depth ascending
(low = back; BG ... GROUND(1) ... WALL(2.9) ... FENCE(3.3) ... RAFTERS(5)), tile flip bits 0x4000 (H) / 0x8000 (V), palette index 0 transparent.
"""
import sys,os,glob,json,struct
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import ycpak
from tlb import load,tileinfo,layer_grid,arr,sstr
from wflz import wflz_decompress
from ycd import load_ycd
from PIL import Image
import numpy as np

SKIP=('COLLISION','MOVE_','FOG','PIT_BLACK','BLOCK','VISTA','SHADOW','SPARKLES','KEY_REWARD','FLIP_OFF','FLIP_ON',
      'TAVERN_','TRAIN','BOSS','_ModeWeather','ModeFinalGuard','WINDOWBREAK','_BURROW','WLAYER_','MOON')
WATER_PAL={}   # level -> waterTileset palette (filled below)
BG=(18,18,28)

def read_pal(blob):
    b,root,rt,ptrs=load_ycd(blob)
    arrp=struct.unpack_from('<Q',b,root+0x10)[0]
    pal=np.zeros((256,4),np.uint8)
    for i in range(1,256):
        if arrp+4*i+4<=len(b): pal[i]=list(b[arrp+4*i:arrp+4*i+4])
    return pal

def find_pals(D):
    """name -> palette blob bytes for all palettes referenced (searched lazily)"""
    return {}

class PalStore:
    def __init__(s,D):
        s.D=D; s.idx=None; s.cache={}
    def build(s):
        s.idx={}
        for p in sorted(glob.glob(os.path.join(s.D,'*.pak.yc'))):
            b,ents=ycpak.load(p)
            for nm,sz,dp in ents:
                if nm.endswith('.pal.yc') and nm not in s.idx: s.idx[nm]=(p,nm)
    def get(s,name):
        if name in s.cache: return s.cache[name]
        if s.idx is None: s.build()
        pal=None
        if name in s.idx:
            b,ents=ycpak.load(s.idx[name][0])
            for nm,sz,dp in ents:
                if nm==name: pal=read_pal(bytes(b[dp:dp+sz])); break
        s.cache[name]=pal; return pal

def atlas_of(L):
    b=L.b; n,p=arr(b,0x188); res=[]
    for i in range(n):
        w,h,tw,th,f,sz,ptr=struct.unpack_from('<IIIIQQQ',b,p+40*i)
        tex=np.frombuffer(wflz_decompress(bytes(b[ptr:ptr+(sz&0xffffffff)])),dtype=np.uint8)[:w*h].reshape(h,w)
        cols=w//tw
        res.append(tex.reshape(h//th,th,cols,tw).transpose(0,2,1,3).reshape(-1,th,tw))
    return res

WATER_BY_LEVEL=[('astralOrrery_mirrorHub','astralOrrery'),('astralOrrery','astralOrrery'),('bayou_overworld','BayouOW'),('bayou','bayou'),
 ('boneBeachA','boneBeachA'),('boneBeachB','boneBeachA'),('boneBeach_overworld','default'),('crypt','default'),('fishingHole','FishingHole'),
 ('frozenTrainyard','FrozenTrainyard'),('hub_overworld_east','overworldEast'),('hub_overworld_west','OWWest'),('hub_overworld_south','default'),
 ('hub','hub'),('introBeach','IntroBeach'),('introSea','IntroBeach'),('mansion','mansionOutside'),('septemburg_overworld','SeptemburgOW'),
 ('septemburgSewer','sewer'),('septemburgB','septemburgB'),('septemburg','septemburg')]
def water_pal(name):
    base=name.split('__')[-1]
    for k,v in WATER_BY_LEVEL:
        if base.startswith(k) : return 'palettes/environments/waterTileset_%s.pal.yc'%v
    return None
def is_water_layer(n):
    u=n.upper()
    return (u.startswith('WATER') or u.startswith('DEEP_WATER')) and not any(k in u for k in ('DECOR','FLOWER','MOON'))

def bake_color(name,L,info,pals,outdir):
    T=L.tileW; ti=tileinfo(L); atl=atlas_of(L)
    ow,oh=info['origin_wx'],info['origin_wy']; W,H=info['width_px'],info['height_px']
    out=np.zeros((H,W,4),np.uint8)
    used=set(); b=L.b
    layers=[l for l in L.layers if l['parx']==0 and l['pary']==0 and l['wt'] and not any(k in l['name'] for k in SKIP)]
    layers.sort(key=lambda l:(l['depth'],l['i']))
    gpal=pals.get('palettes/global.pal.yc')
    for l in layers:
        n,pp=arr(b,l['off']+48)
        pn=sstr(b,pp) if n else 'palettes/global.pal.yc'
        pal=pals.get(pn) if pn!='palettes/global.pal.yc' else gpal
        if pal is None: pal=gpal
        wp=water_pal(name)
        if wp and pn=='palettes/global.pal.yc' and is_water_layer(l['name']):
            pw=pals.get(wp)
            if pw is not None:
                # waterTileset_*.pal only defines the water ramp (global idx 2..8); everything else is chroma-green/black filler -> fall through to global
                pal=gpal.copy(); keep=(pw[:9,3]>0)&~((pw[:9,0]==0)&(pw[:9,1]==255)&(pw[:9,2]==0)); keep[:2]=False
                pal[:9][keep]=pw[:9][keep]; pn=wp
        used.add(pn)
        g=np.array(layer_grid(L,l),dtype=np.uint16)
        ys,xs=np.nonzero(g)
        ox=int(round(l['x']))-ow; oy=-int(round(l['y']))*-1  # placeholder
        ox=int(round(l['x']))-ow; oy=int(round(l['y']))+oh   # oh = -my*T (world y up) -> px y = layer_y - (-oh)
        for y,x in zip(ys,xs):
            v=int(g[y,x]); idx=(v&0x3fff)-1
            if idx<0 or idx>=len(ti): continue
            tex,tidx,_=ti[idx]
            if tex>=len(atl) or tidx>=len(atl[tex]): continue
            t=atl[tex][tidx]
            if v&0x4000: t=t[:,::-1]
            if v&0x8000: t=t[::-1,:]
            X=ox+x*T; Y=oy+y*T
            x0,y0=max(X,0),max(Y,0); x1,y1=min(X+T,W),min(Y+T,H)
            if x1<=x0 or y1<=y0: continue
            t=t[y0-Y:y1-Y,x0-X:x1-X]
            m=t>0
            rgba=pal[t]; m&=rgba[...,3]>0
            reg=out[y0:y1,x0:x1]; reg[m]=rgba[m]
    rgb=np.where(out[...,3:4]>0,out[...,:3],np.array(BG,np.uint8)).astype(np.uint8)
    Image.fromarray(rgb,'RGB').save(os.path.join(outdir,name+'_color.png'))
    Image.fromarray(out,'RGBA').save(os.path.join(outdir,name+'_color_a.png'))
    Image.fromarray(rgb,'RGB').resize((max(W//2,1),max(H//2,1)),Image.LANCZOS).save(os.path.join(outdir,name+'_color_half.png'))
    return sorted(used),[l['name'] for l in layers]

def main():
    D,O=sys.argv[1],sys.argv[2]; flts=sys.argv[3:] or ['']
    pals=PalStore(D)
    class P(dict):
        def get(s,k,d=None): return pals.get(k)
    ps=P()
    rep={}
    for p in sorted(glob.glob(os.path.join(D,'*.pak.yc'))):
        b,ents=ycpak.load(p)
        for nm,sz,dp in ents:
            if not nm.endswith('.tlb.yc') or not any(f in nm for f in flts): continue
            base=nm[:-7].replace('/','__')
            jp=os.path.join(O,base+'.json')
            if not os.path.exists(jp): continue
            info=json.load(open(jp))
            try:
                L=load(bytes(b[dp:dp+sz]))
                used,names=bake_color(base,L,info,ps,O)
                rep[nm[:-7]]=used; print('ok',nm,used,len(names),'layers',flush=True)
            except Exception as e:
                import traceback; traceback.print_exc(); print('FAIL',nm,repr(e))
    json.dump(rep,open(os.path.join(O,'color_palettes.json') if flts==[''] else os.path.join(O,'color_palettes_part.json'),'w'),indent=1)
if __name__=='__main__': main()
