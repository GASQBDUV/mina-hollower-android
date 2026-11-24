import struct,sys,subprocess,re
B="/mnt/c/Program Files (x86)/Steam/steamapps/content/app_1875580/depot_1875584/Mina the Hollower.app/Contents/MacOS/MinaTheHollower"
d=open(B,'rb').read()
if d[:4]==b'\xca\xfe\xba\xbe':
    n=struct.unpack('>I',d[4:8])[0]
    for i in range(n):
        ct,cs,off,size,al=struct.unpack('>5I',d[8+i*20:28+i*20])
        if ct==0x0100000c: d=d[off:off+size]
# segments
ncmds=struct.unpack_from('<I',d,16)[0]; p=32; segs=[]
for _ in range(ncmds):
    cmd,sz=struct.unpack_from('<II',d,p)
    if cmd==0x19:
        nm=d[p+8:p+24].rstrip(b'\0'); vm,vs,fo,fs=struct.unpack_from('<QQQQ',d,p+24); segs.append((vm,vs,fo,fs))
    p+=sz
def rd(va,n=64):
    for vm,vs,fo,fs in segs:
        if vm<=va<vm+vs and va-vm<fs: return d[fo+va-vm:fo+va-vm+n]
    return None
def cstr(va):
    b=rd(va,80)
    if not b: return None
    s=b.split(b'\0')[0]
    if len(s)>=3 and all(32<=c<127 for c in s): return s.decode()
st=int(sys.argv[1],16); ln=int(sys.argv[2],16)
o=st-0x100000000
txt=' '.join('0x%02x,'%b for b in d[o:o+ln])
r=subprocess.run(['llvm-mc-21','--disassemble','--triple=aarch64','--mattr=+lse,+rcpc,+v8.3a,+crc,+aes,+sha2'],input=txt,capture_output=True,text=True)
a=st; regs={}
for l in r.stdout.split('\n'):
    l=l.split('//')[0].strip()
    if not l or l.startswith('.text'): continue
    note=''
    m=re.match(r'adrp\s+(x\d+),\s*#(-?\d+)',l)
    if m: regs[m.group(1)]=(a&~0xfff)+int(m.group(2))
    m=re.match(r'add\s+(x\d+),\s*(x\d+),\s*#(0x[0-9a-f]+|\d+)',l)
    if m and m.group(2) in regs:
        t=regs[m.group(2)]+int(m.group(3),0); s=cstr(t); note='  ; %x "%s"'%(t,s) if s else '  ; %x'%t
    m=re.match(r'ldr\s+(x\d+),\s*\[(x\d+),\s*#(\d+)\]',l)
    if m and m.group(2) in regs:
        note='  ; got %x'%(regs[m.group(2)]+int(m.group(3)))
    print('%x: %s%s'%(a,l,note)); a+=4
