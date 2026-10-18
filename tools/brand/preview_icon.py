import sys; sys.path.insert(0, '/workspace/subpilot/tools/brand')
from vd2svg import render
from PIL import Image, ImageDraw
R = '/workspace/subpilot/app/src/main/res/drawable/'
def icon(px, shape='circle', mono=False):
    big = px * 108 // 72
    if mono:
        bg = Image.new('RGBA', (big, big), (220, 230, 240, 255))
        fg = render(R + 'ic_launcher_monochrome.xml', big, tint='#2B4A5A')
    else:
        bg = render(R + 'ic_launcher_background.xml', big); fg = render(R + 'ic_launcher_foreground.xml', big)
    im = Image.alpha_composite(bg, fg)
    o = (big - px) // 2
    im = im.crop((o, o, o + px, o + px))
    m = Image.new('L', (px, px), 0); d = ImageDraw.Draw(m)
    if shape == 'circle': d.ellipse([0, 0, px - 1, px - 1], fill=255)
    else: d.rounded_rectangle([0, 0, px - 1, px - 1], radius=px // 4, fill=255)
    out = Image.new('RGBA', (px, px), (0, 0, 0, 0)); out.paste(im, (0, 0), m); return out
if __name__ == '__main__':
    sheet = Image.new('RGBA', (900, 340), (245, 245, 245, 255))
    x = 20
    for px, sh, mono in [(256, 'circle', False), (192, 'square', False), (96, 'circle', False), (48, 'circle', False), (32, 'square', False), (192, 'circle', True)]:
        sheet.alpha_composite(icon(px, sh, mono), (x, 20)); x += px + 20
    sheet.save('/tmp/icon_preview.png')
