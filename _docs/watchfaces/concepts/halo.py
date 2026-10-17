import math, sys, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageChops

OUT = os.path.dirname(os.path.abspath(__file__))
S = 4                      # supersample
W = 450 * S
C = W / 2
FONT = "/System/Library/Fonts/SFNSRounded.ttf"
FONT_N = "/System/Library/Fonts/SFNS.ttf"

def font(sz, weight="Regular", path=FONT):
    f = ImageFont.truetype(path, int(sz * S))
    try: f.set_variation_by_name(weight)
    except Exception: pass
    return f

# data
BG, DELTA, ARROW = 9.4, "+0.1", "→"
IOB, IOB_MAX = 2.53, 6.0
LOOP_MIN, BASAL, TT, TIME = 0, "0.00 U/h", "5.6", "00:40"
LOW, HIGH, SMIN, SMAX = 3.9, 10.0, 2.2, 16.7
# 3 h history (mmol) for the ring trace
HIST = [11.8,11.6,11.5,11.3,11.2,11.0,10.9,10.8,10.6,10.5,10.3,10.1,10.0,9.9,9.8,9.7,9.6,9.5,9.5,9.4,9.4,9.4,9.4,9.4]

GREEN = (61, 220, 151); AMBER = (255, 181, 71); RED = (255, 90, 110)
CYAN = (90, 200, 250); VIOLET = (150, 120, 255); GREY = (120, 128, 140)

R_RING = 140 * S; TH_RING = 13 * S
R_IOB = 207 * S; TH_IOB = 5 * S
A0, A1 = 135, 405          # PIL degrees (0 = 3 o'clock, clockwise); gap at bottom

def val2ang(v):
    t = (min(max(v, SMIN), SMAX) - SMIN) / (SMAX - SMIN)
    return A0 + t * (A1 - A0)

def color_for(v):
    return RED if v < LOW else AMBER if v > HIGH else GREEN

def bbox(r): return (C - r, C - r, C + r, C + r)

def pt(ang, r):
    a = math.radians(ang); return (C + r * math.cos(a), C + r * math.sin(a))

def arc(d, r, a, b, col, w):
    d.arc(bbox(r), a, b, fill=col, width=int(w))
    for ang in (a, b):           # round caps
        x, y = pt(ang, r - w / 2); d.ellipse((x - w/2, y - w/2, x + w/2, y + w/2), fill=col)

def glow(img, draw_fn, radius, strength=1):
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0)); draw_fn(ImageDraw.Draw(layer))
    g = layer.filter(ImageFilter.GaussianBlur(radius * S))
    for _ in range(strength): img.alpha_composite(g)
    img.alpha_composite(layer)

def text_c(d, xy, s, f, fill, anchor="mm"):
    d.text(xy, s, font=f, fill=fill, anchor=anchor)

def lerp(c1, c2, t): return tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))

def render(mode):
    amb = mode == "ambient"; armed = mode == "armed"
    img = Image.new("RGBA", (W, W), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)
    bc = color_for(BG); vang = val2ang(BG)

    if not amb:
        # soft radial backdrop tinted by range colour
        bgl = Image.new("RGBA", (W, W), (0, 0, 0, 0)); bd = ImageDraw.Draw(bgl)
        for i in range(40, 0, -1):
            r = R_RING * i / 40 * 1.05
            a = int(26 * (1 - i / 40) ** 1.4)
            bd.ellipse(bbox(r), fill=bc + (a,))
        img.alpha_composite(bgl.filter(ImageFilter.GaussianBlur(10 * S)))
        d = ImageDraw.Draw(img)

    # --- main ring track
    if amb:
        arc(d, R_RING, A0, A1, (70, 70, 70), 2 * S)
        # range ticks only
        for v in (LOW, HIGH):
            a = val2ang(v); p1, p2 = pt(a, R_RING + 6*S), pt(a, R_RING - 10*S)
            d.line([p1, p2], fill=(110, 110, 110), width=2 * S)
    else:
        arc(d, R_RING, A0, A1, (30, 34, 40), TH_RING)
        # target band tint
        d.arc(bbox(R_RING), val2ang(LOW), val2ang(HIGH), fill=(28, 62, 48), width=int(TH_RING))
        # 3h history trace as gradient arc from oldest to now (dim -> bright)
        a_old = val2ang(HIST[0])
        def draw_fill(dd):
            lo, hi = sorted((A0, vang))
            n = 90
            for i in range(n):
                t = i / n
                aa = lo + (hi - lo) * t; ab = lo + (hi - lo) * (i + 1.2) / n
                col = lerp((40, 110, 190), bc, t ** 0.8)
                dd.arc(bbox(R_RING), aa, ab, fill=col + (255,), width=int(TH_RING))
            x, y = pt(A0, R_RING - TH_RING / 2); w = TH_RING
            dd.ellipse((x - w/2, y - w/2, x + w/2, y + w/2), fill=(40, 110, 190, 255))
        glow(img, draw_fill, 7)
        d = ImageDraw.Draw(img)
        # range threshold ticks
        for v in (LOW, HIGH):
            a = val2ang(v); p1, p2 = pt(a, R_RING + 6*S), pt(a, R_RING - TH_RING - 5*S)
            d.line([p1, p2], fill=(0, 0, 0), width=3 * S)
        # 3 h comet tail just outside the ring: where BG came from (fades with age)
        a_old = val2ang(HIST[0]); n = 60
        for i in range(n):
            t = i / n
            aa = a_old + (vang - a_old) * t; ab = a_old + (vang - a_old) * (i + 1.3) / n
            lo, hi = sorted((aa, ab))
            d.arc(bbox(R_RING + 12 * S), lo, hi, fill=lerp((0, 0, 0), (170, 180, 195), t ** 1.5), width=int(3 * S))
    # value knob
    x, y = pt(vang, R_RING - TH_RING / 2)
    if amb:
        r = 7 * S; d.ellipse((x - r, y - r, x + r, y + r), outline=(220, 220, 220), width=2 * S)
    else:
        def knob(dd):
            r = 12 * S; dd.ellipse((x - r, y - r, x + r, y + r), fill=(255, 255, 255, 255))
            r2 = 6 * S; dd.ellipse((x - r2, y - r2, x + r2, y + r2), fill=bc + (255,))
        glow(img, knob, 6, 2); d = ImageDraw.Draw(img)

    # --- outer IOB arc (right side, centred at 3 o'clock, grows upward from 4:30)
    I0, I1 = 50, -50
    frac = min(IOB / IOB_MAX, 1)
    if amb:
        d.arc(bbox(R_IOB), I1, I0, fill=(50, 50, 50), width=1 * S)
        d.arc(bbox(R_IOB), I0 - (I0 - I1) * frac, I0, fill=(140, 140, 140), width=2 * S)
    else:
        arc(d, R_IOB, I1, I0, (26, 28, 36), TH_IOB)
        def iob(dd): arc(dd, R_IOB, I0 - (I0 - I1) * frac, I0, CYAN + (255,), TH_IOB)
        glow(img, iob, 4); d = ImageDraw.Draw(img)
    ix = C + 176 * S
    text_c(d, (ix, C - 14 * S), "IOB", font(13, "Semibold"), (90, 96, 110) if not amb else (110, 110, 110))
    text_c(d, (ix, C + 8 * S), f"{IOB:.2f}", font(22, "Medium"), (215, 240, 255) if not amb else (190, 190, 190))

    # --- time (top, inside ring)
    text_c(d, (C, C - 78 * S), TIME, font(30, "Medium"), (235, 235, 240) if not amb else (200, 200, 200))

    # --- hero BG
    hf = font(104, "Light" if amb else "Semibold")
    if amb:
        d.text((C, C + 4 * S), f"{BG:.1f}", font=hf, fill=(0, 0, 0), anchor="mm", stroke_width=2 * S, stroke_fill=(230, 230, 230))
    else:
        def hero(dd): dd.text((C, C + 4 * S), f"{BG:.1f}", font=hf, fill=(255, 255, 255, 255), anchor="mm")
        glow(img, hero, 9); d = ImageDraw.Draw(img)
        d.text((C, C + 4 * S), f"{BG:.1f}", font=hf, fill=(255, 255, 255), anchor="mm")
    # arrow + delta
    sub = (180, 186, 196) if not amb else (170, 170, 170)
    af = font(28, "Semibold", FONT_N); df = font(22, "Medium")
    text_c(d, (C - 30 * S, C + 66 * S), ARROW, af, bc if not amb else (200, 200, 200))
    text_c(d, (C + 18 * S, C + 66 * S), DELTA, df, sub)

    # --- loop pill in the bottom gap
    py = C + 150 * S; pw, ph = 74 * S, 28 * S
    lc = GREEN if LOOP_MIN <= 14 else RED
    if amb:
        d.rounded_rectangle((C - pw/2, py - ph/2, C + pw/2, py + ph/2), radius=ph/2, outline=(110, 110, 110), width=2 * S)
        d.ellipse((C - 24*S, py - 4*S, C - 16*S, py + 4*S), outline=(200, 200, 200), width=2 * S)
        text_c(d, (C + 8 * S, py), f"{LOOP_MIN}'", font(17, "Semibold"), (200, 200, 200))
    else:
        d.rounded_rectangle((C - pw/2, py - ph/2, C + pw/2, py + ph/2), radius=ph/2, fill=(18, 40, 30), outline=(40, 90, 64), width=1 * S)
        def ld(dd): dd.ellipse((C - 25*S, py - 5*S, C - 15*S, py + 5*S), fill=lc + (255,))
        glow(img, ld, 4); d = ImageDraw.Draw(img)
        text_c(d, (C + 8 * S, py), f"{LOOP_MIN}'", font(17, "Semibold"), (210, 245, 225))
    # secondary line (calm)
    if not amb:
        f2 = font(14, "Medium")
        text_c(d, (C, py + 32 * S), f"TT {TT}   ·   {BASAL}", f2, (110, 116, 128))

    # --- bolus ball at 9 o'clock
    bx, by, br = 48 * S, C, 30 * S
    if amb:
        pass  # hidden in ambient (no tap targets); uncomment for outline variant
    else:
        ring_col = (255, 200, 90) if armed else (120, 140, 255)
        def ball(dd):
            dd.ellipse((bx - br, by - br, bx + br, by + br), fill=(20, 22, 40, 255) if not armed else (60, 42, 10, 255))
            dd.ellipse((bx - br, by - br, bx + br, by + br), outline=ring_col + (255,), width=int(2.5 * S))
        glow(img, ball, 5 if not armed else 9, 1 if not armed else 2); d = ImageDraw.Draw(img)
        # inner shading
        sh = Image.new("RGBA", img.size, (0, 0, 0, 0)); sd = ImageDraw.Draw(sh)
        sd.ellipse((bx - br*0.7, by - br*0.95, bx + br*0.5, by - br*0.1), fill=(255, 255, 255, 22))
        img.alpha_composite(sh.filter(ImageFilter.GaussianBlur(3 * S))); d = ImageDraw.Draw(img)
        text_c(d, (bx, by - 5 * S), "+0.5", font(15, "Bold"), (240, 240, 255) if not armed else (255, 230, 170))
        text_c(d, (bx, by + 12 * S), "U" if not armed else "again", font(11 if not armed else 10, "Semibold"), (150, 160, 220) if not armed else (255, 200, 90))

    img = img.convert("RGB").resize((450, 450), Image.LANCZOS)
    # round mask (watch is round)
    m = Image.new("L", (450, 450), 0); ImageDraw.Draw(m).ellipse((0, 0, 449, 449), fill=255)
    out = Image.new("RGB", (450, 450), (0, 0, 0)); out.paste(img, (0, 0), m)
    p = os.path.join(OUT, f"halo_{mode}.png"); out.save(p); print(p)
    return out

ims = [render(m) for m in ("interactive", "armed", "ambient")]
sheet = Image.new("RGB", (450 * 3 + 40, 470), (24, 24, 28))
for i, im in enumerate(ims): sheet.paste(im, (10 + i * 460, 10))
sheet.save(os.path.join(OUT, "halo_sheet.png"))
