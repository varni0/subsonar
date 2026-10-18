#!/usr/bin/env python3
"""Converts simple Android VectorDrawable XML files (path/group elements) to SVG so they can be rendered with cairosvg.
Used by the store-asset generator (icon, line icons) so the Play Store icon is drawn from the same source as the app icon."""
import sys, xml.etree.ElementTree as ET
A = '{http://schemas.android.com/apk/res/android}'

def col(c):
    if not c or c.startswith('@') or c.startswith('?'): return None, 1.0
    c = c.lstrip('#')
    if len(c) == 8: a = int(c[:2], 16) / 255; c = c[2:]
    elif len(c) == 6: a = 1.0
    elif len(c) == 3: a = 1.0; c = ''.join(x * 2 for x in c)
    else: return None, 1.0
    return '#' + c, a

def convert(path, tint=None, size=None):
    root = ET.parse(path).getroot()
    vw, vh = root.get(A + 'viewportWidth'), root.get(A + 'viewportHeight')
    out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {vw} {vh}" width="{size or vw}" height="{size or vh}">']
    def walk(el):
        for ch in el:
            if ch.tag == 'group':
                tr = []
                tx, ty = ch.get(A + 'translateX'), ch.get(A + 'translateY')
                if tx or ty: tr.append(f'translate({tx or 0},{ty or 0})')
                if ch.get(A + 'rotation'):
                    tr.append(f'rotate({ch.get(A + "rotation")},{ch.get(A + "pivotX") or 0},{ch.get(A + "pivotY") or 0})')
                sx, sy = ch.get(A + 'scaleX'), ch.get(A + 'scaleY')
                if sx or sy:
                    px, py = float(ch.get(A + 'pivotX') or 0), float(ch.get(A + 'pivotY') or 0)
                    tr.append(f'translate({px},{py}) scale({sx or 1},{sy or 1}) translate({-px},{-py})')
                out.append(f'<g transform="{" ".join(tr)}">'); walk(ch); out.append('</g>')
            elif ch.tag == 'path':
                fc, fa = col(ch.get(A + 'fillColor'))
                sc, sa = col(ch.get(A + 'strokeColor'))
                if tint:
                    if fc and fa > 0: fc = tint
                    if sc and sa > 0: sc = tint
                fa *= float(ch.get(A + 'fillAlpha') or 1); sa *= float(ch.get(A + 'strokeAlpha') or 1)
                attrs = [f'd="{ch.get(A + "pathData")}"', f'fill="{fc or "none"}"', f'fill-opacity="{fa}"']
                if ch.get(A + 'fillType') == 'evenOdd': attrs.append('fill-rule="evenodd"')
                if sc:
                    attrs += [f'stroke="{sc}"', f'stroke-opacity="{sa}"', f'stroke-width="{ch.get(A + "strokeWidth") or 1}"',
                              f'stroke-linecap="{ch.get(A + "strokeLineCap") or "butt"}"', f'stroke-linejoin="{ch.get(A + "strokeLineJoin") or "miter"}"']
                out.append(f'<path {" ".join(attrs)}/>')
    walk(root)
    out.append('</svg>')
    return '\n'.join(out)

def render(path, px, tint=None):
    """Returns a PIL RGBA image of the drawable at px x px."""
    import io, cairosvg
    from PIL import Image
    png = cairosvg.svg2png(bytestring=convert(path, tint).encode(), output_width=px, output_height=px)
    return Image.open(io.BytesIO(png)).convert('RGBA')

if __name__ == '__main__':
    print(convert(sys.argv[1]))
