import struct,bisect
B=r"C:\Program Files (x86)\Steam\steamapps\content\app_1875580\depot_1875584\Mina the Hollower.app\Contents\MacOS\MinaTheHollower"
d=open(B,'rb').read()
if d[:4]==b'\xca\xfe\xba\xbe':
    n=struct.unpack('>I',d[4:8])[0]
    for i in range(n):
        ct,cs,off,size,al=struct.unpack('>5I',d[8+i*20:28+i*20])
        if ct==0x0100000c: d=d[off:off+size]
syms=[]
for l in open('mina_symbols.txt',encoding='utf8',errors='ignore'):
    p=l.split(' ',1)
    try: syms.append((int(p[0],16),p[1].strip()))
    except: pass
syms.sort()
ks=[s[0] for s in syms]
words=struct.unpack('<%dI'%(len(d)//4),d[:len(d)//4*4])
for i,w in enumerate(words):
    if (w&0xffe00000)==0x52800000 and ((w>>5)&0xffff) in (0x4657,0x5a4c):
        for j in range(i+1,min(i+6,len(words))):
            w2=words[j]
            if (w2&0xffe00000)==0x72a00000 and (w2&31)==(w&31) and ((w2>>5)&0xffff) in (0x5a4c,0x4657):
                va=0x100000000+i*4; k=bisect.bisect(ks,va)-1
                print(hex(va),syms[k][1][:90])
print('loose')
for i,w in enumerate(words):
    if (w&0x7f800000) in (0x52800000,0x72800000) and ((w>>5)&0xffff) in (0x4657,0x5a4c):
        va=0x100000000+i*4; k=bisect.bisect(ks,va)-1
        print(hex(va),hex(w),syms[k][1][:90])
