"""anb_sprites.py <file.anb.yc> : list sprites (name, first-frame image idx, frame duration, locators MinaPos/CompletePos/other)."""
import sys,struct,os,json
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
from ycd import load_ycd
from lookup3 import hashlittle
def sprites(path):
    b,root,rt,ptrs=load_ycd(open(path,'rb').read())
    HM=hashlittle(b'MinaPos',1337); HC=hashlittle(b'CompletePos',1337)
    out=[]
    # sprite records: 56 bytes, start at 0x170; fields: +0 name hash, +8 (0,len?) +16 name ptr, +24 {16,4}, +32 frames ptr, +40 {48,12}, +48 locator-ptr-array
    off=0x170
    while off+56<=len(b) and (off+16) in ptrs and (off+32) in ptrs:
        namep=struct.unpack_from('<Q',b,off+16)[0]; nm=bytes(b[namep:namep+40]).split(b'\0')[0].decode()
        fp=struct.unpack_from('<Q',b,off+32)[0]; img,dur=struct.unpack_from('<If',b,fp)
        lp=struct.unpack_from('<Q',b,off+48)[0] if (off+48) in ptrs else 0; locs={}
        n=struct.unpack_from('<I',b,off+44)[0]//1  # count in 4B units /4 -> ptrs
        for k in range(3 if (off+48) in ptrs else 0):
            q=lp+8*k
            if q not in ptrs: break
            o=struct.unpack_from('<Q',b,q)[0]
            tid=struct.unpack_from('<Q',b,o)[0]; h,x,y=struct.unpack_from('<Iff',b,o+12)
            locs[{HM:'MinaPos',HC:'CompletePos'}.get(h,'%08x'%h)]=(x,y)
        out.append(dict(name=nm,image=img,dur=dur,locators=locs)); off+=56
    return out
if __name__=='__main__':
    for s in sprites(sys.argv[1]): print(json.dumps(s))
