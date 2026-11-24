"""xref.py <hexva>...: find adrp+add pairs (and adrp+ldr/str) referencing target VAs in the arm64 slice. Prints instruction VA."""
import struct,sys
B=r"C:\Program Files (x86)\Steam\steamapps\content\app_1875580\depot_1875584\Mina the Hollower.app\Contents\MacOS\MinaTheHollower"
d=open(B,'rb').read()
if d[:4]==b'\xca\xfe\xba\xbe':
    n=struct.unpack('>I',d[4:8])[0]
    for i in range(n):
        ct,cs,off,size,al=struct.unpack('>5I',d[8+i*20:28+i*20])
        if ct==0x0100000c: d=d[off:off+size]
def scan(targets,lo=0x100000000,hi=0x100000000+0x1000000*0+0x0d000000):
    res={t:[] for t in targets}
    tp={t&~0xfff:t for t in targets}
    base=0x100000000
    n=len(d)//4
    adrp={}
    for i in range(0,min(n,(hi-base)//4)):
        w=struct.unpack_from('<I',d,i*4)[0]; va=base+i*4
        if (w&0x9f000000)==0x90000000:
            imm=((w>>5)&0x7ffff)<<2|((w>>29)&3)
            if imm&0x100000: imm-=0x200000
            pg=(va&~0xfff)+(imm<<12); adrp[w&31]=(pg,i)
        elif (w&0xff800000)==0x91000000:
            rn=(w>>5)&31
            if rn in adrp and i-adrp[rn][1]<64:
                t=adrp[rn][0]+((w>>10)&0xfff)
                if t in res: res[t].append(va)
    return res
if __name__=='__main__':
    ts=[int(x,16) for x in sys.argv[1:]]
    for t,v in scan(ts).items(): print(hex(t),[hex(x) for x in v])
