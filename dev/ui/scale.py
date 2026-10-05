import sys,glob
from PIL import Image
for p in glob.glob('out/*/*.png'):
    if p.endswith('_x4.png'): continue
    im=Image.open(p).convert('RGBA'); im.resize((im.width*4,im.height*4),Image.NEAREST).save(p[:-4]+'_x4.png')
