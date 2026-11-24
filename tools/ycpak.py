"""ycpak.py: YCD v8 'ycPakBin' reader. Usage: ycpak.py list <pak> | extract <pak> <name-substr> <outdir>"""
import os, struct, sys
def load(pak):
    b = bytearray(open(pak,'rb').read()); n=len(b)
    assert struct.unpack_from('<II',b,0)==(0x00444359,8)
    root_off,=struct.unpack_from('<Q',b,8)
    for i in range(4):
        base,=struct.unpack_from('<Q',b,0x28+8*i); first,=struct.unpack_from('<Q',b,0x48+8*i)
        if first==0xffffffffffffffff: continue
        site=base+first
        while True:
            nxt,rel=struct.unpack_from('<Ii',b,site)
            struct.pack_into('<Q',b,site,(site+rel)&0xffffffffffffffff)
            if nxt==0xffffffff: break
            site+=struct.unpack('<i',struct.pack('<I',nxt))[0]
    w0,w1,toc=struct.unpack_from('<IIQ',b,root_off); count=w1>>2
    ents=[]
    for i in range(count):
        e=toc+0x10+32*i
        nlen,nptr=struct.unpack_from('<IQ',b,e-0xc); size,=struct.unpack_from('<I',b,e); dptr,=struct.unpack_from('<Q',b,e+8)
        ents.append((bytes(b[nptr:nptr+nlen]).decode('latin1'),size,dptr))
    return b,ents
if __name__=='__main__':
    b,ents=load(sys.argv[2])
    if sys.argv[1]=='list':
        for nm,sz,_ in ents: print(sz,nm)
    else:
        for nm,sz,d in ents:
            if sys.argv[3] in nm:
                p=os.path.join(sys.argv[4],nm.replace(':','_')); os.makedirs(os.path.dirname(p),exist_ok=True); open(p,'wb').write(b[d:d+sz]); print(p,sz)
