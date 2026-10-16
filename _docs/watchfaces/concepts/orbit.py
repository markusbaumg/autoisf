import math, sys, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

S = 2               # supersample
W = 450 * S
C = W / 2
OUT = os.path.dirname(os.path.abspath(__file__))
FN = "/System/Library/Fonts/SFNSRounded.ttf"
FC = "/System/Library/Fonts/SFCompactRounded.ttf"

def font(sz, w=None, path=FN):
    f = ImageFont.truetype(path, int(sz * S))
    if w:
        try: f.set_variation_by_axes([w])
        except Exception: pass
    return f

def hexc(h, a=255):
    h = h.lstrip('#'); return (int(h[0:2],16), int(h[2:4],16), int(h[4:6],16), a)

# values
BG, ARROW, DELTA, AGE = "9.4", "→", "+0.1", "1'"
IOB, COB, LOOP, BASAL, TT, TIME = "2.53", "0", "0'", "0.00", "5.6", "00:40"
BOLUS = "0.5"

GREEN = "#3EE07A"; CYAN = "#4FC3F7"; AMBER = "#FFB74D"; VIOLET = "#B388FF"
PINK = "#FF7AB6"; TEAL = "#3DDBD9"; BGCOL = "#7CF2A6"  # in-range mint

def polar(r, deg):
    a = math.radians(deg)
    return C + r * S * math.cos(a), C + r * S * math.sin(a)

def text_c(d, xy, s, f, fill, anchor="mm"):
    d.text(xy, s, font=f, fill=fill, anchor=anchor)

def glow_layer(draw_fn, radius):
    lay = Image.new("RGBA", (W, W), (0,0,0,0))
    draw_fn(ImageDraw.Draw(lay))
    return lay.filter(ImageFilter.GaussianBlur(radius * S))

def bg_series():
    # 3 h of 5-min BG in mmol/L ending at 9.4 flat, then 1 h prediction
    pts = []
    for i in range(36):
        t = i / 35
        v = 6.6 + 3.0 * (1 / (1 + math.exp(-(t - 0.45) * 9))) - 0.2 * math.sin(t * 7)
        pts.append(v)
    pts[-1] = 9.4
    pred = [9.4 - 0.06 * i - 0.004 * i * i for i in range(1, 13)]
    return pts, pred

def render(ambient):
    img = Image.new("RGBA", (W, W), (0,0,0,255))
    if not ambient:
        # radial gradient background: deep navy center to black
        grad = Image.new("RGBA", (W, W), (0,0,0,255))
        gd = ImageDraw.Draw(grad)
        for r in range(int(C), 0, -4):
            k = 1 - r / C
            col = (int(6 + 14 * k), int(10 + 18 * k), int(20 + 34 * k), 255)
            gd.ellipse([C - r, C - r, C + r, C + r], fill=col)
        img = grad.filter(ImageFilter.GaussianBlur(10 * S))
    d = ImageDraw.Draw(img)

    ORB = 168  # orbit radius
    # orbit track
    trk = (70, 70, 70, 255) if ambient else (90, 110, 140, 90)
    d.ellipse([C-ORB*S, C-ORB*S, C+ORB*S, C+ORB*S], outline=trk, width=int(1.5*S))
    if not ambient:
        # BG-in-range arc segment on the orbit (fraction of last 6h in range ~ 82%)
        for k, a in [(0, 255)]:
            pass
        # faint seconds-free minute marker: small comet at 40 min position
        lay = Image.new("RGBA", (W, W), (0,0,0,0)); ld = ImageDraw.Draw(lay)
        a_end = -90 + 40 * 6
        for i in range(40):
            ld.arc([C-ORB*S, C-ORB*S, C+ORB*S, C+ORB*S], a_end - 40 + i, a_end - 39 + i, fill=(200, 220, 255, int(6 * i)), width=int(2.5*S))
        img.alpha_composite(lay); d = ImageDraw.Draw(img)
        # minute tick as a small comet on the orbit (00:40 -> 240 deg from 12)
        ang = -90 + 40 * 6
        x, y = polar(ORB, ang)

    # time (top inside)
    tcol = (150,150,150,255) if ambient else (220,228,240,255)
    text_c(d, (C, C - 108*S), TIME, font(30, 500), tcol)

    # hero BG + arrow
    bgcol = (235,235,235,255) if ambient else hexc(BGCOL)
    fbg = font(104, 700)
    if not ambient:
        gl = glow_layer(lambda g: text_c(g, (C - 14*S, C - 22*S), BG, fbg, hexc(BGCOL, 150)), 14)
        img.alpha_composite(gl); d = ImageDraw.Draw(img)
    text_c(d, (C - 14*S, C - 22*S), BG, fbg, bgcol)
    bb = d.textbbox((C - 14*S, C - 22*S), BG, font=fbg, anchor="mm")
    text_c(d, (bb[2] + 22*S, C - 22*S), ARROW, font(46, 600), bgcol)

    # delta + age row
    sub = (140,140,140,255) if ambient else (170,185,205,255)
    text_c(d, (C, C + 38*S), f"{DELTA}   ·   {AGE}", font(22, 500), sub)

    # sparkline (interactive only; ambient: thin gray)
    pts, pred = bg_series()
    x0, x1 = C - 72*S, C + 34*S
    y_mid = C + 86*S; scale = 6*S
    def yv(v): return y_mid - (v - 8.0) * scale
    n = len(pts)
    xy = [(x0 + (x1 - x0) * i / (n - 1), yv(v)) for i, v in enumerate(pts)]
    # range band
    if not ambient:
        lay = Image.new("RGBA", (W, W), (0,0,0,0))
        ImageDraw.Draw(lay).rounded_rectangle([x0 - 8*S, yv(10.0), x1 + 44*S, yv(6.0)], radius=8*S, fill=(60, 220, 140, 22))
        img.alpha_composite(lay); d = ImageDraw.Draw(img)
    lc = (120,120,120,255) if ambient else hexc(BGCOL, 255)
    if not ambient:
        gl = glow_layer(lambda g: g.line(xy, fill=hexc(BGCOL, 180), width=int(4*S), joint="curve"), 5)
        img.alpha_composite(gl); d = ImageDraw.Draw(img)
    d.line(xy, fill=lc, width=int(2.5*S), joint="curve")
    step = (x1 - x0) / (n - 1)
    if not ambient:
        for i, v in enumerate(pred):
            px = x1 + step * (i + 1); r = 1.6*S
            d.ellipse([px-r, yv(v)-r, px+r, yv(v)+r], fill=hexc(VIOLET, 200))
    ex, ey = xy[-1]; r = 4.5*S
    d.ellipse([ex-r, ey-r, ex+r, ey+r], fill=(230,230,230,255) if ambient else (255,255,255,255))

    # planets
    planets = [
        # angle, value, label, color
        (-38, LOOP, "LOOP", GREEN),
        (0, IOB, "IOB", CYAN),
        (38, BASAL, "U/h", TEAL),
        (142, COB, "COB g", AMBER),
        (218, TT, "TT", PINK),
    ]
    PR = 33
    for ang, val, lab, col in planets:
        x, y = polar(ORB, ang); r = PR * S
        if ambient:
            d.ellipse([x-r, y-r, x+r, y+r], fill=(0,0,0,255), outline=(110,110,110,255), width=int(1.5*S))
            text_c(d, (x, y - 5*S), val, font(19, 600), (200,200,200,255))
            text_c(d, (x, y + 15*S), lab, font(10.5, 600), (120,120,120,255))
            continue
        gl = glow_layer(lambda g: g.ellipse([x-r, y-r, x+r, y+r], fill=hexc(col, 110)), 9)
        img.alpha_composite(gl); d = ImageDraw.Draw(img)
        # sphere-ish gradient fill
        base = hexc(col)
        for i in range(int(r), 0, -2):
            k = i / r
            c = tuple(int(14 + (base[j] * 0.28) * (1 - k * 0.55)) for j in range(3)) + (255,)
            ox, oy = (1 - k) * -7*S, (1 - k) * -9*S
            d.ellipse([x-i+ox, y-i+oy, x+i+ox, y+i+oy], fill=c)
        d.ellipse([x-r, y-r, x+r, y+r], outline=hexc(col, 235), width=int(2*S))
        text_c(d, (x, y - 5*S), val, font(19, 650), (245,248,252,255))
        text_c(d, (x, y + 15*S), lab, font(10.5, 600), hexc(col, 230))

    # loop planet status dot (fresh)
    # bolus planet (left, 180 deg) - interactive only, distinct double ring
    bx, by = polar(ORB, 180); br = 37*S
    if not ambient:
        gl = glow_layer(lambda g: g.ellipse([bx-br, by-br, bx+br, by+br], fill=hexc(VIOLET, 120)), 11)
        img.alpha_composite(gl); d = ImageDraw.Draw(img)
        for i in range(int(br), 0, -2):
            k = i / br
            c = (int(40 + 60 * (1 - k)), int(22 + 30 * (1 - k)), int(80 + 90 * (1 - k)), 255)
            d.ellipse([bx-i, by-i, bx+i, by+i], fill=c)
        d.ellipse([bx-br, by-br, bx+br, by+br], outline=hexc(VIOLET), width=int(2.5*S))
        rr = br + 7*S
        for a0 in range(0, 360, 20):   # dashed outer ring = "action"
            d.arc([bx-rr, by-rr, bx+rr, by+rr], a0, a0 + 11, fill=hexc(VIOLET, 200), width=int(1.6*S))
        text_c(d, (bx, by - 6*S), f"+{BOLUS}", font(20, 700), (255,255,255,255))
        text_c(d, (bx, by + 15*S), "U · 2×", font(10.5, 650), hexc(VIOLET, 255))

    # round mask
    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, W-1, W-1], fill=255)
    out = Image.new("RGBA", (W, W), (0,0,0,255))
    out.paste(img, (0,0), mask)
    out = out.resize((450, 450), Image.LANCZOS).convert("RGB")
    if ambient:
        # emulate low-bit: quantize to few gray levels
        out = out.convert("L").point(lambda v: (v // 64) * 85 if v > 30 else 0).convert("RGB")
    return out

a = render(False); a.save(os.path.join(OUT, "orbit_interactive.png"))
b = render(True); b.save(os.path.join(OUT, "orbit_ambient.png"))
side = Image.new("RGB", (930, 450), (30,30,30)); side.paste(a, (0,0)); side.paste(b, (480,0))
side.save(os.path.join(OUT, "orbit_side_by_side.png"))
print("ok")
