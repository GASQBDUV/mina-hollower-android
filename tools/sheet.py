import sys,glob
from PIL import Image
fs=sorted(glob.glob(sys.argv[1]+'/*.png'))
x=y=0;rowh=0;W=int(sys.argv[3]) if len(sys.argv)>3 else 900;items=[]
for f in fs:
    im=Image.open(f)
    if x+im.width>W: x=0;y+=rowh+4;rowh=0
    items.append((im,x,y)); x+=im.width+4; rowh=max(rowh,im.height)
sheet=Image.new('RGBA',(W,y+rowh+4),(40,40,60,255))
for im,x,y in items: sheet.alpha_composite(im,(x,y))
sheet=sheet.resize((sheet.width*2,sheet.height*2),Image.NEAREST); sheet.save(sys.argv[2]); print(sheet.size)
