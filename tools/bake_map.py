"""bake_map.py <Resources/data dir> <outdir>
Extracts UI/worldMap(.Big).anb.yc + global palette from the user's own game data and writes
 worldmap_small.png (256x144), worldmap_big.png (512x288), locators_small.json, locators_big.json
 (locators are map-centre relative, y UP: px = W/2 + x, py = H/2 - y)."""
import sys,os,json,tempfile
sys.path.insert(0,os.path.dirname(os.path.abspath(__file__)))
import ycpak, anb_worldmap, anb_sprites
def main(data,out):
    os.makedirs(out,exist_ok=True)
    tmp=tempfile.mkdtemp(prefix='minamap_')
    for pak,name in (('global.pak.yc','UI/worldMap'),('global_startup.pak.yc','palettes/global.pal')):
        b,ents=ycpak.load(os.path.join(data,pak))
        for nm,sz,d in ents:
            if nm.startswith(name):
                p=os.path.join(tmp,nm.replace('/','_')); open(p,'wb').write(b[d:d+sz])
    pal=os.path.join(tmp,'palettes_global.pal.yc')
    for tag,f in (('small','UI_worldMap.anb.yc'),('big','UI_worldMapBig.anb.yc')):
        p=os.path.join(tmp,f)
        size,_=anb_worldmap.build(p,pal,os.path.join(out,'worldmap_%s.png'%tag))
        sp={s['name']:s['locators'] for s in anb_sprites.sprites(p) if 'MinaPos' in s['locators']}
        json.dump(dict(size=size,locators={k:v['MinaPos'] for k,v in sp.items()}),open(os.path.join(out,'locators_%s.json'%tag),'w'),indent=1)
        print(tag,size,len(sp))
if __name__=='__main__': main(sys.argv[1],sys.argv[2])
