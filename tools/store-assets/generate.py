#!/usr/bin/env python3
"""Play Store assets generator: framed screenshots (en/nb/de), 512x512 icon, 1024x500 feature graphics.

Inputs: raw Robolectric renders from StoreScreenshotsTest (1080x2280 PNGs in RAW/<lang>/).
  ./gradlew testDebugUnitTest --tests '*StoreScreenshotsTest*' -Pstore.shots=/workspace/store-assets-v2/raw -Pstore.name=Subsonar
  python3 tools/store-assets/generate.py --name Subsonar --raw /workspace/store-assets-v2/raw --out /workspace/store-assets-v2
A rename is a one-line rerun: pass another --name (and re-render the raw shots with -Pstore.name=... for the in-app wordmark).
The icon is drawn from the app's own vector drawables (tools/brand/vd2svg.py), so the store icon always matches the launcher icon.
Palette = ui/theme/Theme.kt (Brand)."""
import argparse, os, sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.environ.get('SUBSONAR_REPO') or os.environ.get('SUBPILOT_REPO') or (os.path.abspath(os.path.join(HERE, '../..')) if os.path.isdir(os.path.join(HERE, '../../app')) else '/workspace/subpilot')
sys.path.insert(0, os.path.join(REPO, 'tools/brand'))
from vd2svg import render  # noqa: E402

DRAW = os.path.join(REPO, 'app/src/main/res/drawable')
FONT = '/usr/share/fonts/truetype/sand-box/google/Inter/Inter-VariableFont_opsz,wght.ttf'
FJORD, FJORD_DEEP, SNOW, CLOUDBERRY, POLAR = (0x1E, 0x55, 0x68), (0x14, 0x3E, 0x4D), (0xF7, 0xF9, 0xFB), (0xF2, 0xA5, 0x41), (0x0F, 0x14, 0x17)

TEXT = {
    'en': [
        ('01_empty', 'Find your subscriptions in a bank export', 'Pick a CSV from your online bank. No bank login needed.'),
        ('02_import_review', 'You decide what to keep', 'Likely subscriptions first, bills kept apart.'),
        ('03_list', 'Every renewal in one calm view', "Yearly total, this week's renewals and price hikes."),
        ('04_price_history', 'See every price hike', 'A timeline of each change and what it costs per year.'),
        ('05_insights', 'Know where the money goes', 'Spending by category, in your own currency.'),
        ('06_appearance', 'Your colours, your icons', '31 line icons and emoji, with smart defaults.'),
        ('07_settings', 'No account. No ads. No internet.', 'Nothing leaves your phone. 19 languages.'),
        ('08_list_dark', 'Calm in the dark, too', 'Light and dark themes in a Nordic palette.'),
    ],
    'nb': [
        ('01_empty', 'Finn abonnementene dine i en bankeksport', 'Velg en CSV fra nettbanken. Ingen bankinnlogging.'),
        ('02_import_review', 'Du bestemmer hva som beholdes', 'Sannsynlige abonnementer først, regninger for seg.'),
        ('03_list', 'Alle fornyelser i én rolig oversikt', 'Årlig sum, ukens fornyelser og prisøkninger.'),
        ('04_price_history', 'Se hver prisøkning', 'En tidslinje over hver endring og hva den koster i året.'),
        ('05_insights', 'Se hvor pengene går', 'Forbruk per kategori, i din egen valuta.'),
        ('06_appearance', 'Dine farger, dine ikoner', '31 strekikoner og emoji, med smarte standardvalg.'),
        ('07_settings', 'Ingen konto. Ingen reklame. Uten internett.', 'Ingenting forlater telefonen. 19 språk.'),
        ('08_list_dark', 'Rolig i mørket også', 'Lyst og mørkt tema i nordiske farger.'),
    ],
    'de': [
        ('01_empty', 'Finde deine Abos im Kontoauszug', 'CSV aus dem Onlinebanking wählen. Ohne Bank-Login.'),
        ('02_import_review', 'Du entscheidest, was bleibt', 'Wahrscheinliche Abos zuerst, Rechnungen getrennt.'),
        ('03_list', 'Alle Verlängerungen auf einen Blick', 'Jahressumme, Verlängerungen der Woche, Preiserhöhungen.'),
        ('04_price_history', 'Jede Preiserhöhung im Blick', 'Eine Zeitleiste jeder Änderung und was sie pro Jahr kostet.'),
        ('05_insights', 'Wissen, wohin das Geld geht', 'Ausgaben nach Kategorie, in deiner Währung.'),
        ('06_appearance', 'Deine Farben, deine Symbole', '31 Liniensymbole und Emoji, mit klugen Vorgaben.'),
        ('07_settings', 'Kein Konto. Keine Werbung. Kein Internet.', 'Nichts verlässt dein Handy. 19 Sprachen.'),
        ('08_list_dark', 'Auch im Dunkeln ruhig', 'Helles und dunkles Design in nordischen Farben.'),
    ],
}
FEATURE = {
    'en': 'Find subscriptions in your bank export.\nNothing leaves your phone.',
    'nb': 'Finn abonnementer i bankeksporten.\nIngenting forlater telefonen.',
    'de': 'Finde Abos in deinem Kontoauszug.\nNichts verlässt dein Handy.',
}


def font(size, weight=400):
    f = ImageFont.truetype(FONT, size)
    try:
        f.set_variation_by_axes([min(32, max(14, size / 3)), weight])
    except Exception:
        pass
    return f


def wrap(d, text, f, width):
    words, lines, cur = text.split(), [], ''
    for w in words:
        t = (cur + ' ' + w).strip()
        if d.textlength(t, font=f) <= width or not cur: cur = t
        else: lines.append(cur); cur = w
    if cur: lines.append(cur)
    return lines


def fit(d, text, width, size, weight, max_lines):
    """Largest font size (<= size) at which text wraps into max_lines within width."""
    while size > 20:
        f = font(size, weight)
        lines = wrap(d, text, f, width)
        if len(lines) <= max_lines and all(d.textlength(l, font=f) <= width for l in lines): return f, lines
        size -= 2
    return font(size, weight), wrap(d, text, font(size, weight), width)


def gradient(w, h, top=FJORD, bottom=FJORD_DEEP):
    g = Image.new('RGB', (1, h))
    for y in range(h):
        t = y / max(1, h - 1)
        g.putpixel((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return g.resize((w, h)).convert('RGBA')


def ridges(img, base_y, scale=1.0):
    """Low mountain ridges like the icon background."""
    w, h = img.size
    ov = Image.new('RGBA', img.size, (0, 0, 0, 0)); d = ImageDraw.Draw(ov)
    def pts(spec, y0):
        return [(x * w, y0 + dy * scale) for x, dy in spec] + [(w, h), (0, h)]
    d.polygon(pts([(0, 0), (.17, -60), (.28, -28), (.46, -100), (.61, -40), (.74, -78), (1, -10)], base_y), fill=(0x19, 0x4A, 0x5B, 255))
    d.polygon(pts([(0, 40), (.22, 0), (.41, 30), (.65, -20), (.85, 20), (1, 0)], base_y + 70 * scale), fill=(0x12, 0x38, 0x46, 255))
    return Image.alpha_composite(img, ov)


def app_icon(px, full_bleed=True):
    """Launcher icon (background + foreground) at px; full_bleed = square 72dp area (Play icon)."""
    big = round(px * 108 / 72)
    im = Image.alpha_composite(render(os.path.join(DRAW, 'ic_launcher_background.xml'), big), render(os.path.join(DRAW, 'ic_launcher_foreground.xml'), big))
    o = (big - px) // 2
    return im.crop((o, o, o + px, o + px))


def rounded(im, radius):
    m = Image.new('L', im.size, 0); ImageDraw.Draw(m).rounded_rectangle([0, 0, im.size[0] - 1, im.size[1] - 1], radius=radius, fill=255)
    out = Image.new('RGBA', im.size, (0, 0, 0, 0)); out.paste(im, (0, 0), m); return out


def status_bar(shot):
    """Adds a phone status bar (72 px at 1080 wide) in the colour of the screen's top edge."""
    w, h = shot.size; sb = 72
    top = shot.getpixel((w // 2, 2))[:3]
    out = Image.new('RGBA', (w, h + sb), top + (255,)); out.paste(shot, (0, sb))
    d = ImageDraw.Draw(out)
    lum = 0.299 * top[0] + 0.587 * top[1] + 0.114 * top[2]
    fg = (30, 34, 36, 255) if lum > 128 else (235, 238, 240, 255)
    d.text((54, 36), '09:41', font=font(34, 600), fill=fg, anchor='lm')
    # signal bars + battery (no wifi glyph: the app has no internet access anyway)
    x = w - 180
    for i in range(4):
        d.rounded_rectangle([x + i * 14, 48 - (i + 1) * 6, x + i * 14 + 9, 48], radius=2, fill=fg)
    bx = w - 110
    d.rounded_rectangle([bx, 24, bx + 54, 48], radius=6, outline=fg, width=3)
    d.rounded_rectangle([bx + 6, 30, bx + 40, 42], radius=3, fill=fg)
    d.rounded_rectangle([bx + 57, 31, bx + 61, 41], radius=2, fill=fg)
    return out


def phone(shot, width):
    """Phone frame around a screenshot (with status bar) scaled to `width` outer px."""
    bezel = round(width * 0.028)
    sw = width - 2 * bezel
    shot = status_bar(shot.convert('RGBA'))
    sh = round(shot.size[1] * sw / shot.size[0])
    screen = rounded(shot.resize((sw, sh), Image.LANCZOS), round(sw * 0.075))
    W, H = width, sh + 2 * bezel
    body = Image.new('RGBA', (W, H), (0, 0, 0, 0)); d = ImageDraw.Draw(body)
    d.rounded_rectangle([0, 0, W - 1, H - 1], radius=round(W * 0.1), fill=(16, 22, 25, 255))
    d.rounded_rectangle([2, 2, W - 3, H - 3], radius=round(W * 0.1) - 2, outline=(70, 82, 88, 255), width=2)
    body.alpha_composite(screen, (bezel, bezel))
    # punch-hole camera
    cx, cy, r = W // 2, bezel + round(36 * sw / 1080), round(13 * sw / 1080)
    d = ImageDraw.Draw(body); d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(10, 12, 14, 255))
    return body


def shadowed(canvas, obj, pos, blur=30, offset=(0, 24), alpha=120):
    sh = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
    m = obj.split()[3].point(lambda a: alpha if a > 0 else 0)
    sh.paste((0, 0, 0, 255), (pos[0] + offset[0], pos[1] + offset[1]), m)
    canvas = Image.alpha_composite(canvas, sh.filter(ImageFilter.GaussianBlur(blur)))
    canvas.alpha_composite(obj, pos)
    return canvas


def wordmark(canvas, name, xy, size, color=SNOW, center=False):
    d = ImageDraw.Draw(canvas)
    f = font(size, 650)
    mark = rounded(app_icon(round(size * 1.35)), round(size * 0.38))
    tw = d.textlength(name, font=f)
    total = mark.size[0] + size * 0.45 + tw
    x = xy[0] - total / 2 if center else xy[0]
    canvas.alpha_composite(mark, (round(x), round(xy[1])))
    d.text((x + mark.size[0] + size * 0.45, xy[1] + mark.size[1] / 2), name, font=f, fill=color, anchor='lm')


def screenshot(raw_dir, key, headline, sub, name):
    W, H = 1080, 1920
    c = ridges(gradient(W, H), 1540, 1.6)
    # soft cloudberry glow behind the phone
    glow = Image.new('RGBA', (W, H), (0, 0, 0, 0)); ImageDraw.Draw(glow).ellipse([140, 760, 940, 1560], fill=CLOUDBERRY + (46,))
    c = Image.alpha_composite(c, glow.filter(ImageFilter.GaussianBlur(120)))
    wordmark(c, name, (W / 2, 64), 46, center=True)
    d = ImageDraw.Draw(c)
    fh, hl = fit(d, headline, W - 150, 74, 700, 2)
    y = 196
    for line in hl:
        d.text((W / 2, y), line, font=fh, fill=SNOW, anchor='ma'); y += round(fh.size * 1.16)
    fs, sl = fit(d, sub, W - 170, 38, 420, 2)
    y += 26
    for line in sl:
        d.text((W / 2, y), line, font=fs, fill=(0xD6, 0xE4, 0xEC, 255), anchor='ma'); y += round(fs.size * 1.3)
    shot = Image.open(os.path.join(raw_dir, key + '.png'))
    top = max(y + 30, 540)  # same phone position on every image unless the text needs more room
    width = 640
    p = phone(shot, width)
    if top + p.size[1] > H - 40:  # keep the whole phone on the canvas
        width = int(width * (H - 40 - top) / p.size[1]); p = phone(shot, width)
    return shadowed(c, p, ((W - p.size[0]) // 2, top)).convert('RGB')


def feature_graphic(raw_dir, name, tagline):
    W, H = 1024, 500
    c = ridges(gradient(W, H), 400, 0.8)
    glow = Image.new('RGBA', (W, H), (0, 0, 0, 0)); ImageDraw.Draw(glow).ellipse([600, 40, 1000, 440], fill=CLOUDBERRY + (50,))
    c = Image.alpha_composite(c, glow.filter(ImageFilter.GaussianBlur(80)))
    d = ImageDraw.Draw(c)
    mark = rounded(app_icon(96), 26)
    c = shadowed(c, mark, (64, 62), blur=14, offset=(0, 8), alpha=90)
    d = ImageDraw.Draw(c)
    fn, _ = fit(d, name, 540, 84, 700, 1)
    d.text((60, 258), name, font=fn, fill=SNOW, anchor='ls')
    y = 300
    lines = tagline.split('\n')
    size = min(fit(d, l, 560, 30, 450, 1)[0].size for l in lines)
    ft = font(size, 450)
    for l in lines:
        d.text((64, y), l, font=ft, fill=(0xD6, 0xE4, 0xEC, 255)); y += round(size * 1.45)
    # phone, cropped at the bottom edge
    p = phone(Image.open(os.path.join(raw_dir, '03_list.png')), 300)
    p = p.crop((0, 0, p.size[0], min(p.size[1], H - 40 + 60)))
    c = shadowed(c, p, (660, 40), blur=18, offset=(0, 12), alpha=110)
    return c.convert('RGB')


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--name', default='Subsonar', help='Product name shown in the wordmark and feature graphic')
    ap.add_argument('--raw', default='/workspace/store-assets-v2/raw')
    ap.add_argument('--out', default='/workspace/store-assets-v2')
    ap.add_argument('--langs', default='en,nb,de')
    a = ap.parse_args()
    os.makedirs(a.out, exist_ok=True)
    app_icon(512).convert('RGB').save(os.path.join(a.out, 'icon_512.png'))
    for lang in a.langs.split(','):
        raw = os.path.join(a.raw, lang); od = os.path.join(a.out, lang); os.makedirs(od, exist_ok=True)
        for i, (key, h, s) in enumerate(TEXT[lang], 1):
            screenshot(raw, key, h, s, a.name).save(os.path.join(od, f'{key}.png'))
        feature_graphic(raw, a.name, FEATURE[lang]).save(os.path.join(od, 'feature_graphic_1024x500.png'))
        print(lang, 'ok')
    import shutil
    shutil.copy(os.path.join(a.out, 'en', 'feature_graphic_1024x500.png'), os.path.join(a.out, 'feature_graphic_1024x500.png'))


if __name__ == '__main__':
    main()
