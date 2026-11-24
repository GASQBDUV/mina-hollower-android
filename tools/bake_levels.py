#!/usr/bin/env python
"""bake_levels.py <data_dir> <out_dir> [name-substr]
Bakes every levels/**.tlb.yc in the game's paks into <out_dir>/<name>.png + <name>.json
 - PNG: SCALE px per tile, RGBA. Colors: wall(kind0 solid collider) / floor / other surface; room AABBs outlined.
 - JSON: world<->pixel mapping. World coords are runtime coords: wx = layer_x, wy = -layer_y (y up).
   pixel x = (wx - origin_x) / tile * scale ; pixel y = (-wy - origin_y) / tile * scale  (origin = top-left in layer frame)
See analysis/level-format.md.
"""
import sys,os,glob,json,struct
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import ycpak
from tlb import load,tileinfo,layer_grid,arr
from PIL import Image,ImageDraw
import numpy as np
from wflz import wflz_decompress
SCALE=8
CLS_EMPTY,CLS_FLOOR,CLS_OTHER,CLS_WALL=0,1,2,3
COL={CLS_FLOOR:(70,120,80,255),CLS_OTHER:(60,80,150,255),CLS_WALL:(190,190,205,255)}
ROOM=(255,60,60,255)
def coldefs(binpath=None):
    # kCollisionDef: 47 entries x 48 bytes (3 kinds x {u32 shape,u32 pad,u64 mask}); embedded (extracted from Mach-O @0x100d4d9f8)
    return COLDEF
# kind0 = solid wall-ish (shape!=0), kind1 = surface; classes per collisionType id
def _mk():
    # collisionType (TileInfo+4) -> class, from kCollisionDef (Mach-O 0x100d4d9f8, 47 x 48 B = 3 kinds x {u32 shape,u32,u64 mask}):
    # kind0 shape!=0 -> solid wall; type 1 (kind1 mask 1) -> walkable floor; other kind1-only types -> 'other' (pits/stairs/hazards/special)
    k0={7,8,13,14,15,16,17,18,19,20,21,22,23,24,25,26,27,28,29,30,31,45}
    special={9,10,11,12}   # kind0 with mask bit46 + a direction normal: one-way/conveyor-like (guess)
    d={}
    for t in range(47):
        if t==0: d[t]=CLS_EMPTY
        elif t in k0: d[t]=CLS_WALL
        elif t==1: d[t]=CLS_FLOOR
        else: d[t]=CLS_OTHER
    return d
TCLS=_mk()

ART_SKIP=('COLLISION','MOVE_','FOG','BG','PIT_BLACK','BLOCK','VISTA','SHADOW')
def bake_art(L,ti,mx,my,W,H,path):
    """grayscale composite of the real tile art (8-bit palette indices x5). Same pixel frame as the class PNG when SCALE==tile."""
    b=L.b; n,p=arr(b,0x188)
    if n<1: return False
    w,h,tw,th,f,sz,ptr=struct.unpack_from('<IIIIQQQ',b,p)
    tex=np.frombuffer(wflz_decompress(bytes(b[ptr:ptr+(sz&0xffffffff)])),dtype=np.uint8).reshape(h,w)
    T=L.tileW; cols=w//tw
    # tile atlas -> [ntiles,8,8]
    atlas=tex.reshape(h//th,th,cols,tw).transpose(0,2,1,3).reshape(-1,th,tw)
    out=np.zeros((H*T,W*T),np.uint8)
    for l in sorted([l for l in L.layers if l['parx']==0 and l['pary']==0 and not any(k in l['name'] for k in ART_SKIP)],key=lambda l:l['depth']):
        g=layer_grid(L,l); ox=int(l['x'])//T-mx; oy=int(l['y'])//T-my
        for y,row in enumerate(g):
            for x,v in enumerate(row):
                if not v: continue
                idx=(v&0x3fff)-1
                if idx<0 or idx>=len(ti) or ti[idx][0]!=0: continue
                t=atlas[ti[idx][1]]
                if v&0x4000: t=t[:,::-1]
                if v&0x8000: t=t[::-1,:]
                X=(ox+x)*T; Y=(oy+y)*T
                if X<0 or Y<0 or X+T>out.shape[1] or Y+T>out.shape[0]: continue
                reg=out[Y:Y+T,X:X+T]; m=t>0
                reg[m]=t[m]
    Image.fromarray(np.clip(out.astype(np.int32)*5,0,255).astype(np.uint8),'L').save(path)
    return True

def bake(name,data,outdir):
    L=load(data)
    ti=tileinfo(L)
    cells={}
    pr={CLS_EMPTY:0,CLS_FLOOR:1,CLS_OTHER:2,CLS_WALL:3}
    # priority wall > other(pit/stairs) > floor
    for l in L.layers:
        if not l['coll'] or l['wt']==0: continue
        g=layer_grid(L,l)
        for y,row in enumerate(g):
            for x,v in enumerate(row):
                if not v: continue
                idx=(v&0x3fff)-1
                if idx<0 or idx>=len(ti): continue
                c=TCLS.get(ti[idx][2],CLS_OTHER)
                if c==CLS_EMPTY: continue
                if l['name'].startswith('EXTRA_COLLISION') and c!=CLS_WALL: continue   # invisible enemy/limit collision, not terrain
                k=(int(l['x'])//L.tileW+x,int(l['y'])//L.tileH+y)
                if pr[c]>pr.get(cells.get(k,0),0): cells[k]=c
    if not cells: return None
    xs=[k[0] for k in cells]; ys=[k[1] for k in cells]
    mx,Mx,my,My=min(xs),max(xs),min(ys),max(ys)
    W,H=Mx-mx+1,My-my+1
    im=Image.new('RGBA',(W*SCALE,H*SCALE),(0,0,0,0)); px=im.load()
    for (tx,ty),c in cells.items():
        for dy in range(SCALE):
            for dx in range(SCALE): px[(tx-mx)*SCALE+dx,(ty-my)*SCALE+dy]=COL[c]
    dr=ImageDraw.Draw(im)
    T=L.tileW
    rooms=[]
    for r in L.rooms:
        x0=(r['cx']-r['w']/2)/T-mx; x1=(r['cx']+r['w']/2)/T-mx
        y0=(-(r['cy']+r['h']/2))/T-my; y1=(-(r['cy']-r['h']/2))/T-my
        dr.rectangle([x0*SCALE,y0*SCALE,x1*SCALE-1,y1*SCALE-1],outline=ROOM)
        rooms.append(dict(cx=r['cx'],cy=r['cy'],w=r['w'],h=r['h']))
    im.save(os.path.join(outdir,name+'.png'))
    bg=Image.new('RGBA',im.size,(18,18,28,255)); bg.alpha_composite(im); bg.convert('RGB').save(os.path.join(outdir,name+'_preview.png'))
    try: has_art=bake_art(L,ti,mx,my,W,H,os.path.join(outdir,name+'_art.png'))
    except Exception as e: has_art=False; print('  art failed',repr(e))
    info=dict(name=name,art_png=(name+'_art.png') if has_art else None,art_px_per_tile=L.tileW,tile=T,scale=SCALE,width_px=W*SCALE,height_px=H*SCALE,
        origin_wx=mx*T,origin_wy=-my*T,   # world coords (y up) of the PNG's top-left corner
        px_per_unit=SCALE/T,
        level_tiles=[L.levelW,L.levelH],n_rooms=len(L.rooms),rooms=rooms,
        legend={'floor':COL[CLS_FLOOR][:3],'wall':COL[CLS_WALL][:3],'other_surface':COL[CLS_OTHER][:3],'room_aabb':ROOM[:3]},
        formula='px=(wx-origin_wx)*px_per_unit; py=(origin_wy-wy)*px_per_unit')
    json.dump(info,open(os.path.join(outdir,name+'.json'),'w'),indent=1)
    return info
def main():
    D,O=sys.argv[1],sys.argv[2]; flt=sys.argv[3] if len(sys.argv)>3 else ''
    os.makedirs(O,exist_ok=True)
    idx={}
    for p in sorted(glob.glob(os.path.join(D,'*.pak.yc'))):
        b,ents=ycpak.load(p)
        for nm,sz,dp in ents:
            if not nm.endswith('.tlb.yc') or flt not in nm: continue
            base=nm[:-7].replace('/','__')
            try:
                info=bake(base,bytes(b[dp:dp+sz]),O)
                if info: idx[nm[:-7]]=dict(file=base,**{k:info[k] for k in ('origin_wx','origin_wy','width_px','height_px','px_per_unit','n_rooms')})
                print('ok ' if info else 'empty',nm)
            except Exception as e: print('FAIL',nm,repr(e))
    json.dump(idx,open(os.path.join(O,'index.json'),'w'),indent=1)
if __name__=='__main__': main()
