import json, os, shutil
import sys
# usage: stage_inv.py <inventory-map.json> <ui dir from extract_ui.py> <output dir>   (output goes to files/mapassets/ui/inv/)
MAP, U, O = sys.argv[1], sys.argv[2].rstrip('/\\') + '/', sys.argv[3].rstrip('/\\') + '/'
shutil.rmtree(O, ignore_errors=True); os.makedirs(O)
j = json.load(open(MAP))
def flat(path):
    n = path.replace('sprites/', '').replace('/', '_')
    shutil.copy(U + path, O + n)
    return n
out = {'weapons': [], 'sidearms': [], 'trinkets': [], 'fields': {}}
for w in j['weapons']:
    out['weapons'].append({'s': w['saveString'], 'name': w['name'], 'icon': flat(w['icon'])})
for s in j['sidearms']:
    out['sidearms'].append({'s': s['saveString'], 'name': s['name'], 'icon': flat(s['icon'])})
for t in j['trinkets']:
    out['trinkets'].append({'id': t['itemId'], 'name': t['name'], 'desc': t.get('desc', ''), 'icon': flat(t['icon'])})
for k, v in j['fields'].items():
    out['fields'][k] = v['off']
for extra in ['sprites/UI_HUD_HUD/boneCounter_icon.png', 'sprites/UI_HUD_HUD/bonestoneCounter_icon.png', 'sprites/UI_HUD_vialIcon/vial_f0.png', 'sprites/UI_HUD_vialIcon/vialEmpty.png']:
    if os.path.exists(U + extra): flat(extra)
json.dump(out, open(O + 'inv.json', 'w'), indent=0)
print(len(out['weapons']), len(out['sidearms']), len(out['trinkets']), sorted(os.listdir(O))[:5])
print({k: out['fields'][k] for k in out['fields'] if k in ('fHealth','sWeaponName','sSubWeaponName','sMoney','sSubWeaponCount','trinketEquipped','trinketCollected','sVialCount','sPlayerStatLevel','fPlayTime','sDeathCount')})
