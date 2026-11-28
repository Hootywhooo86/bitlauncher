"""Railway at dusk, hand-composed pixel art in layers, 128bit palette (hue-shifted ramps)."""
import math, random, json, sys
from PIL import Image
W, H = 256, 160
out = sys.argv[1]
def c(h): h = h.lstrip('#'); return tuple(int(h[i:i+2], 16) for i in (0, 2, 4)) + (255,)
P = {k: c(v) for k, v in {
 'n950':'#0B0B16','n900':'#141428','n800':'#1E1E3A','n700':'#2C2A52','n500':'#4A4775','n400':'#6E6A9A','n300':'#9A97B8','n200':'#C8C5DE','n100':'#F4F1FF',
 'v700':'#2E1F66','v500':'#5B45B5','v300':'#A78BFA','v100':'#E2D8FF',
 'e700':'#5A1F3A','e500':'#C0543A','e300':'#FF9F1C','e100':'#FFE08A',
 'l800':'#14303A','l600':'#2F6239','l500':'#3F7A4A','l400':'#6FAF4E','l200':'#C9E27A',
 'm800':'#2E1A24','m600':'#5A3A24','m500':'#8A5A3A','m300':'#D29A62',
 'red':'#E5484D','pink':'#F472B6','gold':'#FFD34D','out':'#05050B'}.items()}
rnd = random.Random(128)
layers = {n: Image.new('RGBA', (W, H), (0, 0, 0, 0)) for n in ('sky', 'far', 'mid', 'near')}
def px(l, x, y, col):
    if 0 <= x < W and 0 <= y < H: layers[l].putpixel((x, y), col)
def rect(l, x0, y0, x1, y1, col):
    for y in range(y0, y1):
        for x in range(x0, x1): px(l, x, y, col)
HOR = 98  # horizon row
# --- sky: banded dusk with two-row dithered seams ---
bands = [(0, 'n800'), (22, 'n700'), (40, 'v700'), (54, 'e700'), (66, 'e500'), (80, 'e300'), (92, 'e100')]
for i, (y0, k) in enumerate(bands):
    y1 = bands[i + 1][0] if i + 1 < len(bands) else HOR
    rect('sky', 0, y0, W, y1, P[k])
    if i + 1 < len(bands):
        nk = bands[i + 1][1]
        for x in range(W):
            if x % 2 == 0: px('sky', x, y1 - 1, P[nk])
            if (x + 1) % 4 == 0: px('sky', x, y1 - 2, P[nk])
# stars in the top band
for _ in range(40):
    x, y = rnd.randrange(W), rnd.randrange(0, 30)
    px('sky', x, y, P['n200'] if rnd.random() < .7 else P['n100'])
# setting sun: half disc on the horizon, warm core, ember rim
sx, sy, sr = 183, 76, 10
for y in range(sy - sr, sy + 1):
    for x in range(sx - sr, sx + sr + 1):
        d = math.hypot(x - sx, y - sy)
        if d <= sr: px('sky', x, y, P['e100'] if d < sr - 2 else P['gold'])
for y in range(sy, sy + sr + 1):  # the lower half too: the mountains cover what they cover
    for x in range(sx - sr, sx + sr + 1):
        d = math.hypot(x - sx, y - sy)
        if d <= sr: px('sky', x, y, P['e100'] if d < sr - 2 else P['gold'])
# a soft glow ring around the sun, dithered
for y in range(sy - sr - 6, sy + sr + 6):
    for x in range(sx - sr - 6, sx + sr + 7):
        d = math.hypot(x - sx, y - sy)
        if sr < d <= sr + 5 and (x + y) % 2 == 0: px('sky', x, y, P['e100'] if d < sr + 3 else P['e300'])
# lit cloud streaks: pink tops, violet bellies
for (cx, cy, w) in [(30, 44, 46), (110, 34, 38), (214, 52, 40), (140, 66, 26), (52, 72, 22)]:
    for x in range(cx, cx + w):
        t = (x - cx) / w
        bump = int(3 * math.sin(math.pi * t) + 1.5 * math.sin(t * 9))
        top = cy - max(0, bump)
        px('sky', x, top, P['e100'])            # sunlit rim
        for y in range(top + 1, cy + 2): px('sky', x, y, P['pink'])
        px('sky', x, cy + 2, P['v300'])
        if 0.1 < t < 0.9: px('sky', x, cy + 3, P['v500'])
# --- far: mountains, cool and pale with distance, lit on the sun side, snow caps ---
peaks = [(18, 58), (52, 66), (86, 54), (122, 70), (160, 62), (204, 52), (238, 64)]
for x in range(W):
    top = HOR
    for (pxk, py) in peaks:
        top = min(top, py + abs(x - pxk) * 0.9)
    top = int(top)
    for y in range(top, HOR):
        # which peak owns this column: the sun is to the right, so right-hand faces are lit
        pk, py = min(peaks, key=lambda q: q[1] + abs(x - q[0]) * 0.9)
        sunny = x >= pk
        col = P['n400'] if sunny else P['n500']
        if not sunny and y - top > 6: col = P['v700'] if (x + y) % 2 else P['n500']
        if sunny and y - top < 2: col = P['e300'] if (x + y) % 3 == 0 else P['n300']
        if y > HOR - 10 and (x + y) % 2 == 0: col = P['n500']  # haze at the foot
        px('far', x, y, col)
    for (pk, py) in peaks:  # snow on the high ones
        if py < 60 and abs(x - pk) < 6:
            for y in range(int(py + abs(x - pk) * 0.9), int(py + 6 - abs(x - pk) * 0.3)):
                px('far', x, y, P['n200'] if x <= pk else P['n300'])
# haze band at the mountains' feet
for x in range(W):
    if x % 2 == 0: px('far', x, HOR - 1, P['e300'])
# --- mid: rolling hills, grain elevator, poles and wires ---
for x in range(W):
    top = int(HOR - 6 - 4 * math.sin(x / 19) - 2 * math.sin(x / 7.3))
    for y in range(top, H):
        col = P['l800']
        if y - top < 1: col = P['e500']  # sun rim on the crest
        elif y - top < 3: col = P['l600']
        px('mid', x, y, col)
# field rows on the hills, catching the last light
for y in range(HOR - 2, TR_TOP := 118):
    for x in range(W):
        # long furrows that get closer together with distance
        gap = 3 if y < HOR + 6 else 5
        if layers['mid'].getpixel((x, y))[3] and y % gap == 0 and (x + y * 3) % 9 < 6:
            px('mid', x, y, P['l600'])
# a line of pines along the crest
for tx in range(4, W, 7):
    if 64 < tx < 98 or rnd.random() < .35: continue
    top = int(HOR - 6 - 4 * math.sin(tx / 19) - 2 * math.sin(tx / 7.3))
    hgt = rnd.randint(6, 10)
    for k in range(hgt):
        half = max(0, (k + 1) // 3)
        for dx in range(-half, half + 1):
            px('mid', tx + dx, top - hgt + k, P['l800'] if dx < 0 else P['l600'])
    px('mid', tx, top - hgt - 1, P['l800'])
# a farmhouse with a lit window
fx, fy = 132, HOR - 2
rect('mid', fx, fy - 6, fx + 9, fy, P['m600'])
rect('mid', fx + 5, fy - 6, fx + 9, fy, P['m500'])
for k in range(4): rect('mid', fx - 1 + k, fy - 7 - k, fx + 10 - k, fy - 6 - k, P['e700'] if k % 2 else P['e500'])
px('mid', fx + 2, fy - 4, P['gold']); px('mid', fx + 3, fy - 4, P['gold'])
# grain elevator: a railway town landmark
ex, ey = 70, HOR - 34
rect('mid', ex, ey, ex + 12, HOR - 4, P['m600'])
rect('mid', ex + 8, ey, ex + 12, HOR - 4, P['m500'])  # sun side
rect('mid', ex - 2, ey - 3, ex + 14, ey, P['m800'])
rect('mid', ex + 12, ey + 10, ex + 22, HOR - 4, P['m600'])
rect('mid', ex + 18, ey + 10, ex + 22, HOR - 4, P['m500'])
rect('mid', ex + 11, ey + 8, ex + 23, ey + 10, P['m800'])
for wy in range(ey + 4, HOR - 8, 6): px('mid', ex + 4, wy, P['gold']); px('mid', ex + 5, wy, P['gold'])
px('mid', ex + 16, ey + 16, P['gold'])
# poles with sagging wires
poles = list(range(8, W, 36))
for p in poles:
    rect('mid', p, HOR - 22, p + 1, HOR + 2, P['m800'])
    rect('mid', p - 3, HOR - 21, p + 4, HOR - 20, P['m800'])
for a, b in zip(poles, poles[1:]):
    for x in range(a, b + 1):
        t = (x - a) / (b - a)
        sag = int(3 * math.sin(math.pi * t))
        px('mid', x, HOR - 21 + sag, P['n900'])
        px('mid', x, HOR - 18 + sag, P['n900'])
# --- near: ballast, ties, rails, foreground grass with flowers, a signal ---
TR = 122  # rail row
rect('near', 0, TR - 4, W, TR + 6, P['n500'])
for x in range(W):
    for y in range(TR - 4, TR + 6):
        if (x * 7 + y * 13) % 5 == 0: px('near', x, y, P['n400'])
        elif (x * 3 + y * 11) % 7 == 0: px('near', x, y, P['n700'])
for x in range(0, W, 6):  # ties
    rect('near', x, TR + 1, x + 4, TR + 4, P['m600'])
    rect('near', x, TR + 1, x + 4, TR + 2, P['m500'])
for ry in (TR - 1, TR + 3):  # rails, lit on top
    rect('near', 0, ry, W, ry + 1, P['n300'])
    rect('near', 0, ry - 1, W, ry, P['n100'])
# grass in front, hue-shifting toward the camera
for y in range(TR + 6, H):
    for x in range(W):
        depth = (y - TR - 6) / (H - TR - 6)
        col = P['l600'] if depth < .25 else P['l500'] if depth < .6 else P['l600']
        if (x + y) % 2 == 0 and depth < .25: col = P['l500']
        px('near', x, y, col)
for _ in range(260):  # blades
    x, y = rnd.randrange(W), rnd.randrange(TR + 7, H)
    px('near', x, y, P['l400']); px('near', x, y - 1, P['l400'] if rnd.random() < .5 else P['l200'])
for _ in range(40):  # flowers
    x, y = rnd.randrange(W), rnd.randrange(TR + 10, H)
    col = rnd.choice([P['gold'], P['pink'], P['v100']])
    px('near', x, y, col); px('near', x, y + 1, P['l600'])
# the grass edge along the ballast
for x in range(W):
    if rnd.random() < .5: px('near', x, TR + 6, P['l400'])
# signal post
sx0 = 214
rect('near', sx0, TR - 30, sx0 + 2, TR - 4, P['n900'])
rect('near', sx0 - 2, TR - 36, sx0 + 4, TR - 26, P['n900'])
px('near', sx0, TR - 34, P['red']); px('near', sx0 + 1, TR - 34, P['red'])
px('near', sx0, TR - 30, P['l400']); px('near', sx0 + 1, TR - 30, P['l400'])
# save layers, a combined preview, and SVGs (one path per colour) for Figma
for n, im in layers.items(): im.save(f'{out}/railway-{n}.png')
comp = Image.new('RGBA', (W, H), P['n950'])
for n in ('sky', 'far', 'mid', 'near'): comp.alpha_composite(layers[n])
comp.save(f'{out}/railway.png')
comp.resize((W * 5, H * 5), Image.NEAREST).save(f'{out}/railway-x5.png')
def svg(im, name):
    byc = {}
    w, h = im.size
    for y in range(h):
        x = 0
        while x < w:
            p = im.getpixel((x, y))
            if p[3] == 0: x += 1; continue
            x1 = x
            while x1 < w and im.getpixel((x1, y)) == p: x1 += 1
            byc.setdefault(p[:3], []).append(f'M{x} {y}h{x1 - x}v1h-{x1 - x}z')
            x = x1
    parts = [f'<path fill="#{r:02X}{g:02X}{b:02X}" d="{"".join(d)}"/>' for (r, g, b), d in byc.items()]
    return f'<svg xmlns="http://www.w3.org/2000/svg" width="{w}" height="{h}" viewBox="0 0 {w} {h}" shape-rendering="crispEdges"><title>{name}</title>{"".join(parts)}</svg>'
for n, im in layers.items():
    open(f'{out}/railway-{n}.svg', 'w').write(svg(im, n))
print('ok', {n: len(open(f'{out}/railway-{n}.svg').read()) for n in layers})
