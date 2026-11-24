import struct,sys
src=open('/mnt/c/Claude/mina-port/analysis/tools/dis_annot.py').read().split("st=int(sys.argv[1]")[0]
exec(src)
va=int(sys.argv[1],16); stride=int(sys.argv[2]); n=int(sys.argv[3])
for i in range(n):
    b=rd(va+stride*i,stride); q=struct.unpack('<%dQ'%(stride//8),b)
    out=[]
    for x in q:
        s=cstr(x) if 0x100000000<x<0x110000000 else None
        out.append('"%s"'%s if s else hex(x))
    print(i,' '.join(out))
