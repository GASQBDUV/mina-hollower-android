"""tlb.py: reader for levels/*.tlb.yc (ycTileLevel2). See level-format.md"""
import struct,sys,os
sys.path.insert(0,os.path.dirname(__file__))
from ycd import load_ycd
from wflz import wflz_decompress
def arr(b,o):
    h,p=struct.unpack_from('<QQ',b,o); return (h>>34, p)
def sstr(b,o):
    h,p=struct.unpack_from('<QQ',b,o); n=h>>32
    return bytes(b[p:p+n]).decode('latin1') if p else ''
def f(b,o,fmt): return struct.unpack_from('<'+fmt,b,o)
class Level: pass
def load(data):
    b,root,rt,ptrs=load_ycd(data)
    L=Level(); L.b=b
    L.levelW,L.levelH,L.tileW,L.tileH,L.chunkW,L.chunkH=f(b,root,'6I')
    L.rooms=[]
    n,p=arr(b,root+0x28)
    for i in range(n):
        o=p+64*i
        nh,nlh=f(b,o+16,'II') if False else (0,0)
        cx,cy,w,h=f(b,o+0x2c,'4f')
        L.rooms.append(dict(i=i,cx=cx,cy=cy,w=w,h=h,off=o))
    L.layers=[]
    n,p=arr(b,root+0x38)
    for i in range(n):
        o=p+168*i
        d=dict(i=i,name=sstr(b,o+16),x=f(b,o+104,'f')[0],y=f(b,o+108,'f')[0],
               wt=f(b,o+128,'I')[0],ht=f(b,o+132,'I')[0],coll=f(b,o+136,'I')[0],depth=f(b,o+140,'f')[0],
               parx=f(b,o+112,'f')[0],pary=f(b,o+116,'f')[0],vis=b[o+148],off=o)
        d['tdsize'],d['tdptr']=f(b,o+152,'QQ')[0]>>0,f(b,o+152,'QQ')[1]
        d['tdsize']=f(b,o+152,'Q')[0]
        d['nchunk']=arr(b,o+80)
        L.layers.append(d)
    return L
if __name__=='__main__':
    L=load(open(sys.argv[1],'rb').read())
    print(L.levelW,L.levelH,L.tileW,L.tileH,L.chunkW,L.chunkH)
    for r in L.rooms: print(r)
    for l in L.layers: print(l)

def tileinfo(L):
    b=L.b; root=0x100
    n,p=arr(b,root+0x78)
    return [struct.unpack_from('<HHH',b,p+6*i) for i in range(n)]
def layer_grid(L,l):
    """returns list rows (y down) of tile-info indices (u16), 0 = empty. Uses chunks of chunkW x chunkH."""
    b=L.b; cw,ch=L.chunkW,L.chunkH
    wt,ht=l['wt'],l['ht']
    ncx=-(-wt//cw); ncy=-(-ht//ch)
    n,p=arr(b,l['off']+80)
    starts=struct.unpack_from('<%dI'%n,b,p)
    blk=bytes(b[l['tdptr']:l['tdptr']+l['tdsize']])
    data=wflz_decompress(blk)
    import array
    g=[[0]*wt for _ in range(ht)]
    for ci,s in enumerate(starts):
        if s==0xffffffff: continue
        cx,cy=ci%ncx,ci//ncx
        for ty in range(ch):
            y=cy*ch+ty
            if y>=ht: break
            for tx in range(cw):
                x=cx*cw+tx
                if x>=wt: break
                g[y][x]=struct.unpack_from('<H',data,(s+ty*cw+tx)*2)[0]
    return g
