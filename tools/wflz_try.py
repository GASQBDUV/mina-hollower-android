import sys,struct,itertools
sys.path.insert(0,'tools')
from ycd import load_ycd
b,root,rt,ptrs=load_ycd(open('_x/UI/worldMap.anb.yc','rb').read())
def dec(blk, lenfn, msb=True, hdr=16):
    cs,us=struct.unpack_from('<II',blk,4)
    p=hdr; out=bytearray()
    try:
        while len(out)<us:
            tag=struct.unpack_from('<I',blk,p)[0]; p+=4
            for i in range(32):
                bit=(tag>>(31-i if msb else i))&1
                if len(out)>=us: break
                if not bit: out.append(blk[p]); p+=1
                else:
                    w=blk[p]|blk[p+1]<<8; p+=2
                    ln,off=lenfn(w)
                    if off<=0 or off>len(out): return None
                    for _ in range(ln): out.append(out[-off])
    except IndexError: return None
    return bytes(out[:us]) if len(out)>=us else None
blk=b[0x9bb3:0x9bb3+899]
variants={}
for msb in (True,False):
  for lb in (3,4,5,6):
    for lk in (0,1,2,3,4):
      for low in (True,False):
        for ok in (0,1):
            def f(w,lb=lb,lk=lk,low=low,ok=ok):
                if low: return ((w&((1<<lb)-1))+lk,(w>>lb)+ok)
                else: return ((w>>(16-lb))+lk,(w&((1<<(16-lb))-1))+ok)
            r=dec(blk,f,msb)
            if r: print(msb,lb,lk,low,ok,r[:40].hex())
