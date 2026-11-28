"""64x64 pixel icons for bitlauncher's own UI: 1px dark outline, light from the top left, hue-shifted shading."""
import math, sys
from PIL import Image
out = sys.argv[1]
S = 64
def c(h): h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4)) + (255,)
O = c('#05050B')
R = {  # ramps: shadow, base, light, highlight
 'violet': [c('#2E1F66'), c('#5B45B5'), c('#A78BFA'), c('#E2D8FF')],
 'steel':  [c('#2C2A52'), c('#4A4775'), c('#9A97B8'), c('#F4F1FF')],
 'ember':  [c('#5A1F3A'), c('#C0543A'), c('#FF9F1C'), c('#FFE08A')],
 'teal':   [c('#123F4A'), c('#1F8A7E'), c('#2DD4BF'), c('#B8F5DC')],
 'sky':    [c('#1D2E6B'), c('#3C6FC4'), c('#86ADE6'), c('#D8ECF7')],
 'gold':   [c('#5A1F3A'), c('#FF9F1C'), c('#FFD34D'), c('#FFE08A')],
 'earth':  [c('#2E1A24'), c('#8A5A3A'), c('#D29A62'), c('#FFE08A')],
}
class Icon:
    def __init__(self): self.im = Image.new('RGBA', (S, S), (0, 0, 0, 0)); self.mask = [[None] * S for _ in range(S)]
    def put(self, x, y, col):
        if 0 <= x < S and 0 <= y < S: self.im.putpixel((x, y), col); self.mask[y][x] = 1
    def shade(self, x, y, ramp, cx, cy, r):
        # light from top-left: brighter towards it, darker away
        # Soft round light from the top left: mostly the base tone, a lit cap, a shadowed rim,
        # and a checker dither where two tones meet so there are no hard stripes.
        d = math.hypot(x - (cx - r * 0.45), y - (cy - r * 0.45)) / (r * 1.6 + 1)
        k = R[ramp]
        edges = [(0.28, 3), (0.62, 2), (0.95, 1)]
        tone = 0
        for lim, t in edges:
            if d < lim: tone = t; break
        for lim, t in edges:
            if abs(d - lim) < 0.05 and (x + y) % 2 == 0:
                tone = max(0, t - 1) if d < lim else t
        return k[tone]
    def disc(self, cx, cy, r, ramp, hole=0):
        for y in range(int(cy - r - 1), int(cy + r + 2)):
            for x in range(int(cx - r - 1), int(cx + r + 2)):
                d = math.hypot(x - cx, y - cy)
                if hole and d < hole: continue
                if d <= r: self.put(x, y, self.shade(x, y, ramp, cx, cy, r))
    def box(self, x0, y0, x1, y1, ramp):
        cx, cy, r = (x0 + x1) / 2, (y0 + y1) / 2, max(x1 - x0, y1 - y0) / 2
        for y in range(y0, y1):
            for x in range(x0, x1): self.put(x, y, self.shade(x, y, ramp, cx, cy, r))
    def line(self, x0, y0, x1, y1, col, w=1):
        n = int(max(abs(x1 - x0), abs(y1 - y0))) + 1
        for i in range(n):
            x = round(x0 + (x1 - x0) * i / max(n - 1, 1)); y = round(y0 + (y1 - y0) * i / max(n - 1, 1))
            for dx in range(w):
                for dy in range(w): self.put(x + dx, y + dy, col)
    def outline(self):
        m = [row[:] for row in self.mask]
        for y in range(S):
            for x in range(S):
                if m[y][x]: continue
                if any(0 <= x + dx < S and 0 <= y + dy < S and m[y + dy][x + dx] for dx, dy in ((1,0),(-1,0),(0,1),(0,-1))):
                    self.im.putpixel((x, y), O)
        return self
icons = {}
# settings: a gear
g = Icon()
for k in range(8):
    a = k * math.pi / 4
    cx, cy = 32 + math.cos(a) * 21, 32 + math.sin(a) * 21
    g.box(int(cx - 5), int(cy - 5), int(cx + 5), int(cy + 5), 'steel')
g.disc(32, 32, 20, 'steel', hole=8)
g.disc(32, 32, 5, 'violet')
icons['settings'] = g.outline()
# search: magnifier with a glass shine
s = Icon()
s.line(41, 41, 55, 55, R['earth'][1], 5); s.line(42, 41, 55, 54, R['earth'][2], 2)
s.disc(27, 27, 18, 'steel', hole=13)
s.disc(27, 27, 13, 'sky')
for (x, y) in [(20, 18), (21, 18), (19, 19), (19, 20), (18, 21), (18, 22)]: s.put(x, y, R['sky'][3])
icons['search'] = s.outline()
# folder: tab, back, front flap
f = Icon()
f.box(6, 12, 28, 20, 'gold')
f.box(6, 18, 58, 50, 'earth')
f.box(6, 24, 58, 54, 'gold')
f.line(8, 26, 56, 26, R['gold'][3])
icons['folder'] = f.outline()
# notifications: a bell with a clapper and a dot
b = Icon()
for y in range(12, 46):
    # a dome that widens into a flared lip
    t = (y - 12) / 34
    half = int(10 * math.sqrt(min(1, t * 2.2)) + (14 * (t - 0.7) / 0.3 if t > 0.7 else 0) + 2)
    for x in range(32 - half, 32 + half): b.put(x, y, b.shade(x, y, 'gold', 32, 28, 20))
b.box(8, 44, 56, 48, 'gold')
b.disc(32, 12, 3, 'gold')
b.disc(32, 52, 4, 'earth')
b.disc(50, 14, 7, 'ember')
icons['notifications'] = b.outline()
# lock: shackle and body with a keyhole
l = Icon()
for y in range(8, 32):
    for x in range(16, 48):
        d = math.hypot(x - 32, y - 24)
        if 11 <= d <= 16 and y <= 30: l.put(x, y, l.shade(x, y, 'steel', 32, 20, 16))
l.box(12, 28, 52, 58, 'violet')
l.disc(32, 40, 4, 'steel'); l.box(31, 42, 34, 50, 'steel')
for x in range(13, 51): l.put(x, 29, R['violet'][3])
icons['lock'] = l.outline()
# camera: body, lens, flash
cm = Icon()
cm.box(22, 12, 38, 18, 'steel')
cm.box(6, 16, 58, 52, 'steel')
cm.disc(32, 34, 14, 'violet'); cm.disc(32, 34, 8, 'sky'); cm.put(28, 30, R['sky'][3]); cm.put(29, 30, R['sky'][3])
cm.box(46, 20, 54, 26, 'gold')
icons['camera'] = cm.outline()
# flashlight: barrel, head, beam
fl = Icon()
fl.box(26, 30, 38, 60, 'steel')
for y in range(14, 30):
    half = 6 + (30 - y) // 2
    for x in range(32 - half, 32 + half): fl.put(x, y, fl.shade(x, y, 'steel', 32, 22, 12))
fl.box(22, 10, 42, 15, 'gold')
fl.box(29, 38, 35, 44, 'violet')
for k, (x, y) in enumerate([(16, 4), (24, 2), (32, 1), (40, 2), (48, 4)]): fl.line(32, 9, x, y, R['gold'][3 if k % 2 else 2])
icons['flashlight'] = fl.outline()
# weather: sun behind a cloud
w = Icon()
for k in range(8):
    a = k * math.pi / 4
    w.line(round(24 + math.cos(a) * 15), round(24 + math.sin(a) * 15), round(24 + math.cos(a) * 21), round(24 + math.sin(a) * 21), R['gold'][2], 2)
w.disc(24, 24, 12, 'gold')
for (cx, cy, r) in [(30, 44, 9), (42, 38, 12), (52, 46, 8)]: w.disc(cx, cy, r, 'steel')
w.box(24, 44, 58, 54, 'steel')
icons['weather'] = w.outline()
for n, ic in icons.items(): ic.im.save(f'{out}/icon-{n}.png')
sheet = Image.new('RGBA', (len(icons) * 72 + 8, 80), c('#0B0B16'))
for i, ic in enumerate(icons.values()): sheet.alpha_composite(ic.im, (8 + i * 72, 8))
sheet.resize((sheet.width * 3, sheet.height * 3), Image.NEAREST).save(f'{out}/icons-sheet.png')
def svg(im, name):
    byc = {}
    for y in range(S):
        x = 0
        while x < S:
            p = im.getpixel((x, y))
            if p[3] == 0: x += 1; continue
            x1 = x
            while x1 < S and im.getpixel((x1, y)) == p: x1 += 1
            byc.setdefault(p[:3], []).append(f'M{x} {y}h{x1 - x}v1h-{x1 - x}z'); x = x1
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{S}" height="{S}" viewBox="0 0 {S} {S}" shape-rendering="crispEdges">' + ''.join(f'<path fill="#{r:02X}{g:02X}{b:02X}" d="{"".join(d)}"/>' for (r, g, b), d in byc.items()) + '</svg>'
for n, ic in icons.items(): open(f'{out}/icon-{n}.svg', 'w').write(svg(ic.im, n))
print(list(icons))
