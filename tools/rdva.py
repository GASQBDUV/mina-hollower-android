import struct,sys
src=open('/mnt/c/Claude/mina-port/analysis/tools/dis_annot.py').read().split("st=int(sys.argv[1]")[0]
exec(src)
va=int(sys.argv[1],16)
for i in range(int(sys.argv[2])):
    b=rd(va+24*i,24); q=struct.unpack('<3Q',b)
    print(hex(va+24*i),[hex(x) for x in q])
    for x in q:
        if 0x100000000<x<0x110000000:
            s=cstr(x)
            if s: print('   ',s)
