import struct
def wflz_decompress(blk):
    assert blk[:4]==b'WFLZ'
    cs,us=struct.unpack_from('<II',blk,4)
    out=bytearray(); p=16; lit=blk[15]; prev=False
    while True:
        if lit==0 and not prev: break
        if lit:
            out+=blk[p:p+lit]; p+=lit
        off,ln,nl=struct.unpack_from('<HBB',blk,p); p+=4
        if ln==0: prev=False
        else:
            prev=True; n=ln+4
            for _ in range(n): out.append(out[-off])
        lit=nl
        if len(out)>=us and lit==0 and not prev: break
    return bytes(out)
