"""ycd.py: YCD v8 generic loader: returns fixed-up buffer + root offset."""
import struct
def load_ycd(b):
    b=bytearray(b); n=len(b)
    assert struct.unpack_from('<II',b,0)==(0x00444359,8)
    root,=struct.unpack_from('<Q',b,8); rtype,=struct.unpack_from('<Q',b,0x10)
    ptrs=[]
    for i in range(4):
        base,=struct.unpack_from('<Q',b,0x28+8*i); first,=struct.unpack_from('<Q',b,0x48+8*i)
        if first==0xffffffffffffffff: continue
        site=base+first
        while True:
            nxt,rel=struct.unpack_from('<Ii',b,site)
            struct.pack_into('<Q',b,site,(site+rel)&0xffffffffffffffff); ptrs.append(site)
            if nxt==0xffffffff: break
            site+=struct.unpack('<i',struct.pack('<I',nxt))[0]
    return b,root,rtype,set(ptrs)
