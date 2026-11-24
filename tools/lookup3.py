M=0xffffffff
def rot(x,k): return ((x<<k)|(x>>(32-k)))&M
def hashlittle(s,initval=0):
    n=len(s); a=b=c=(0xdeadbeef+n+initval)&M
    i=0
    while n-i>12:
        a=(a+int.from_bytes(s[i:i+4],'little'))&M; b=(b+int.from_bytes(s[i+4:i+8],'little'))&M; c=(c+int.from_bytes(s[i+8:i+12],'little'))&M
        a=(a-c)&M;a^=rot(c,4);c=(c+b)&M
        b=(b-a)&M;b^=rot(a,6);a=(a+c)&M
        c=(c-b)&M;c^=rot(b,8);b=(b+a)&M
        a=(a-c)&M;a^=rot(c,16);c=(c+b)&M
        b=(b-a)&M;b^=rot(a,19);a=(a+c)&M
        c=(c-b)&M;c^=rot(b,4);b=(b+a)&M
        i+=12
    t=s[i:]+b'\0'*12
    if n-i==0: return c
    a=(a+int.from_bytes(t[0:4],'little'))&M; b=(b+int.from_bytes(t[4:8],'little'))&M; c=(c+int.from_bytes(t[8:12],'little'))&M
    c^=b;c=(c-rot(b,14))&M
    a^=c;a=(a-rot(c,11))&M
    b^=a;b=(b-rot(a,25))&M
    c^=b;c=(c-rot(b,16))&M
    a^=c;a=(a-rot(c,4))&M
    b^=a;b=(b-rot(a,14))&M
    c^=b;c=(c-rot(b,24))&M
    return c
if __name__=='__main__':
    for n in [b'MinaPos',b'CompletePos',b'minaMarker']:
        print(n,hex(hashlittle(n)))
