"""Concept "Glass" mockup for the AAPS Kusi watch face (450x450 round)."""
import math, os, random
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops

OUT = os.path.dirname(os.path.abspath(__file__))
S = 3                       # supersampling
W = 450 * S
C = W // 2

AVN = "/System/Library/Fonts/Avenir Next.ttc"
# Avenir Next.ttc indices: 0 Bold, 2 DemiBold, 5 Medium, 7 Regular, 8 UltraLight (probe below)
def font(size, idx):
    return ImageFont.truetype(AVN, int(size * S), index=idx)

def find_idx():
    names = {}
    for i in range(12):
        try:
            f = ImageFont.truetype(AVN, 20, index=i)
            names[f.getname()[1]] = i
        except Exception:
            break
    return names
N = find_idx()
BOLD = N.get("Bold", 0); DEMI = N.get("Demi Bold", 2); MED = N.get("Medium", 5)
REG = N.get("Regular", 7); ULT = N.get("Ultra Light", 8)

# realistic data ---------------------------------------------------------
BG, ARROW, DELTA, AGE = "9.4", "→", "+0.1", "1'"
IOB, LOOP_MIN, BASAL, TT, TIME = "2.53", 0, "0.00", "5.6", "00:40"
LOW, HIGH = 3.9, 10.0
random.seed(4)
# 3 h of 5-min BG (36 points) ending at 9.4, mild rise then plateau
bgs = []
v = 7.1
for i in range(36):
    t = i / 35
    target = 7.0 + 2.6 * (1 / (1 + math.exp(-(t - 0.55) * 9)))
    v = target + random.uniform(-0.12, 0.12)
    bgs.append(v)
bgs[-1] = 9.4
preds = [9.4 - 0.05 * k - 0.004 * k * k for k in range(1, 13)]   # 1 h prediction, gently falling
smbs = [22, 25, 28, 31]     # indices where SMBs happened

def D(im):
    """ImageDraw on an RGBA image that alpha-blends fills instead of replacing pixels."""
    d = ImageDraw.Draw(im)
    im.load()
    d.draw = Image.core.draw(im.im, 1)
    return d

def lerp(a, b, t): return a + (b - a) * t
def mix(c1, c2, t): return tuple(int(lerp(a, b, t)) for a, b in zip(c1, c2))

GREEN = (64, 214, 140); AMBER = (255, 184, 64); RED = (255, 92, 92)
INRANGE = (120, 230, 190)
BLUE = (110, 170, 255); VIOLET = (170, 130, 255)

def rounded(d, box, r, **kw):
    d.rounded_rectangle([int(x) for x in box], radius=int(r), **kw)

def text_c(d, xy, s, f, fill, anchor="mm"):
    d.text((int(xy[0]), int(xy[1])), s, font=f, fill=fill, anchor=anchor)

def glow(base, draw_fn, radius):
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    draw_fn(ImageDraw.Draw(layer))
    layer = layer.filter(ImageFilter.GaussianBlur(radius * S))
    return Image.alpha_composite(base, layer)

def glass_card(base, box, r, tint=(255, 255, 255), alpha=22, edge=48):
    """Frosted pill: blurred copy of background beneath, light fill, hairline top highlight."""
    x0, y0, x1, y1 = [int(v) for v in box]
    mask = Image.new("L", base.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([x0, y0, x1, y1], radius=int(r), fill=255)
    blurred = base.filter(ImageFilter.GaussianBlur(14 * S))
    base = Image.composite(blurred, base, mask)
    ov = Image.new("RGBA", base.size, (0, 0, 0, 0))
    od = ImageDraw.Draw(ov)
    # vertical gradient fill inside card
    grad = Image.new("RGBA", base.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(grad)
    for y in range(y0, y1):
        t = (y - y0) / max(1, (y1 - y0))
        a = int(lerp(alpha * 1.5, alpha * 0.6, t))
        gd.line([(x0, y), (x1, y)], fill=tint + (a,))
    grad.putalpha(ImageChops.multiply(grad.getchannel("A"), mask))
    base = Image.alpha_composite(base, grad)
    od.rounded_rectangle([x0, y0, x1, y1], radius=int(r), outline=(255, 255, 255, edge), width=max(1, S))
    return Image.alpha_composite(base, ov)

def round_mask(img, bg=(0, 0, 0)):
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).ellipse([0, 0, W - 1, W - 1], fill=255)
    out = Image.new("RGB", img.size, bg)
    out.paste(img.convert("RGB"), (0, 0), m)
    return out

# layout (in 450-space, multiplied by S) -------------------------------
def P(*v): return [x * S for x in v]

HERO = P(108, 118, 372, 240)          # glass hero card
GRAPH = P(92, 254, 368, 320)          # sparkline area
PILL_Y = 336
LOOP_P = P(118, PILL_Y, 222, PILL_Y + 40)
IOB_P = P(232, PILL_Y, 340, PILL_Y + 40)
BALL = (56 * S, 187 * S, 34 * S)       # bolus ball centre-x, centre-y, radius

def bg_y(v, box):
    lo, hi = 2.5, 13.0
    return box[3] - (v - lo) / (hi - lo) * (box[3] - box[1])

def graph_x(i, n_total, box):
    return box[0] + i / (n_total - 1) * (box[2] - box[0])

def render(ambient=False):
    img = Image.new("RGBA", (W, W), (0, 0, 0, 255))
    d = D(img)
    if not ambient:
        # radial dark gradient: deep indigo centre-top -> black edge
        for r in range(C, 0, -S):
            t = r / C
            col = mix((22, 28, 52), (3, 4, 10), t ** 1.4)
            d.ellipse([C - r, int(C * 0.82) - r, C + r, int(C * 0.82) + r], fill=col + (255,))
        # soft colour blobs (aurora) behind the hero, tinted by BG state (in range = teal)
        img = glow(img, lambda g: g.ellipse(P(150, 110, 330, 250), fill=(40, 170, 140, 70)), 40)
        img = glow(img, lambda g: g.ellipse(P(250, 200, 400, 330), fill=(90, 70, 200, 60)), 45)

    # ---- outer ring: 12 h dial ticks + minute progress arc --------------
    d = D(img)
    R = 214 * S
    for k in range(60):
        a = math.radians(k * 6 - 90)
        major = k % 5 == 0
        r0 = R - (9 if major else 4) * S
        col = (255, 255, 255, 110 if major else 40) if not ambient else (140, 140, 140, 255 if major else 0)
        if ambient and not major:
            continue
        d.line([C + r0 * math.cos(a), C + r0 * math.sin(a), C + R * math.cos(a), C + R * math.sin(a)],
               fill=col, width=(2 if major else 1) * S)
    if not ambient:
        # minute progress arc (40 of 60) as a gradient-ish glowing stroke
        arc_box = [C - (R + 6 * S), C - (R + 6 * S), C + (R + 6 * S), C + (R + 6 * S)]
        def arc(g):
            g.arc(arc_box, -90, -90 + 240, fill=(120, 230, 190, 120), width=3 * S)
        img = glow(img, arc, 4)
        d = D(img)
        steps = 80
        for s in range(steps):
            a0 = -90 + 240 * s / steps
            col = mix((80, 160, 255), (120, 235, 190), s / steps)
            d.arc(arc_box, a0, a0 + 240 / steps + 0.6, fill=col + (210,), width=2 * S)

    # ---- time (top) -------------------------------------------------------
    d = D(img)
    text_c(d, (C, 66 * S), TIME, font(40, ULT if ambient else REG), (235, 238, 245) if not ambient else (200, 200, 200))
    # tiny TT chip under time (secondary, calm)
    tt_f = font(13, MED)
    if not ambient:
        rounded(d, P(196, 88, 254, 106), 9 * S, fill=(255, 255, 255, 18), outline=(255, 255, 255, 40), width=S)
        text_c(d, (C, 97 * S), f"TT {TT}", tt_f, (180, 190, 215))
    else:
        text_c(d, (C, 97 * S), f"TT {TT}", tt_f, (120, 120, 120))

    # ---- hero card ---------------------------------------------------------
    if not ambient:
        img = glass_card(img, HERO, 30 * S, alpha=20, edge=55)
    else:
        d = D(img)
        rounded(d, HERO, 30 * S, outline=(70, 70, 70), width=S)
    d = D(img)
    bg_f = font(78, DEMI if not ambient else ULT)
    bx, by = 222 * S, 182 * S
    if not ambient:
        img = glow(img, lambda g: g.text((bx, by), BG, font=bg_f, fill=INRANGE + (150,), anchor="mm"), 10)
        d = D(img)
    d.text((bx, by), BG, font=bg_f, fill=(255, 255, 255) if not ambient else (230, 230, 230), anchor="mm")
    # arrow in a small circle, right of BG
    ax, ay, ar = 322 * S, 166 * S, 17 * S
    arrow_f = ImageFont.truetype("/System/Library/Fonts/Supplemental/Arial Unicode.ttf", 22 * S) \
        if os.path.exists("/System/Library/Fonts/Supplemental/Arial Unicode.ttf") else font(22, BOLD)
    if not ambient:
        d.ellipse([ax - ar, ay - ar, ax + ar, ay + ar], fill=INRANGE + (40,), outline=INRANGE + (200,), width=S * 2)
        d.text((ax, ay + S), ARROW, font=arrow_f, fill=INRANGE, anchor="mm")
    else:
        d.ellipse([ax - ar, ay - ar, ax + ar, ay + ar], outline=(150, 150, 150), width=S)
        d.text((ax, ay + S), ARROW, font=arrow_f, fill=(200, 200, 200), anchor="mm")
    sub_f = font(15, MED)
    d.text((322 * S, 201 * S), DELTA, font=sub_f, fill=(200, 210, 230) if not ambient else (150, 150, 150), anchor="mm")
    d.text((C, 226 * S), f"mmol/L · {AGE}", font=font(12, REG),
           fill=(130, 140, 165) if not ambient else (100, 100, 100), anchor="mm")

    # ---- sparkline ---------------------------------------------------------
    gx0, gy0, gx1, gy1 = GRAPH
    n_hist, n_pred = len(bgs), len(preds)
    ntot = n_hist + n_pred
    # target band
    if not ambient:
        rounded(d, [gx0, bg_y(HIGH, GRAPH), gx1, bg_y(LOW, GRAPH)], 8 * S, fill=(120, 230, 190, 16))
    for lv in (LOW, HIGH):
        y = bg_y(lv, GRAPH)
        for x in range(int(gx0), int(gx1), 8 * S):
            d.line([x, y, x + 3 * S, y], fill=(255, 255, 255, 50) if not ambient else (70, 70, 70), width=S)
    # "now" marker
    nx = graph_x(n_hist - 1, ntot, GRAPH)
    d.line([nx, gy0 + 4 * S, nx, gy1], fill=(255, 255, 255, 45) if not ambient else (60, 60, 60), width=S)
    pts = [(graph_x(i, ntot, GRAPH), bg_y(v, GRAPH)) for i, v in enumerate(bgs)]
    if not ambient:
        # area fill under curve
        area = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ad = ImageDraw.Draw(area)
        poly = pts + [(pts[-1][0], gy1), (pts[0][0], gy1)]
        ad.polygon(poly, fill=(120, 230, 190, 255))
        fade = Image.new("L", img.size, 0)
        fd = ImageDraw.Draw(fade)
        for y in range(int(gy0), int(gy1)):
            fd.line([(0, y), (W, y)], fill=int(lerp(10, 70, (y - gy0) / (gy1 - gy0)) * (1 - (y - gy0) / (gy1 - gy0)) + 5))
        area.putalpha(ImageChops.multiply(area.getchannel("A"), fade))
        img = Image.alpha_composite(img, area)
        img = glow(img, lambda g: g.line(pts, fill=INRANGE + (200,), width=4 * S, joint="curve"), 4)
        d = D(img)
        for k in range(len(pts) - 1):
            col = mix((90, 170, 255), INRANGE, k / len(pts))
            d.line([pts[k], pts[k + 1]], fill=col + (255,), width=int(2.5 * S))
    else:
        d.line(pts, fill=(200, 200, 200), width=S * 2, joint="curve")
    # predictions as dots
    for k, v in enumerate(preds):
        x = graph_x(n_hist + k, ntot, GRAPH); y = bg_y(v, GRAPH)
        rr = 1.6 * S
        d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=(VIOLET + (200,)) if not ambient else (110, 110, 110))
    # current point
    cx_, cy_ = pts[-1]
    if not ambient:
        img = glow(img, lambda g: g.ellipse([cx_ - 7 * S, cy_ - 7 * S, cx_ + 7 * S, cy_ + 7 * S], fill=INRANGE + (220,)), 5)
        d = D(img)
        d.ellipse([cx_ - 4 * S, cy_ - 4 * S, cx_ + 4 * S, cy_ + 4 * S], fill=(255, 255, 255), outline=INRANGE, width=S * 2)
    else:
        d.ellipse([cx_ - 3 * S, cy_ - 3 * S, cx_ + 3 * S, cy_ + 3 * S], outline=(220, 220, 220), width=S)
    # SMB ticks along the bottom
    if not ambient:
        for i in smbs:
            x = graph_x(i, ntot, GRAPH)
            d.polygon([(x, gy1 - 1 * S), (x - 3 * S, gy1 + 5 * S), (x + 3 * S, gy1 + 5 * S)], fill=BLUE + (220,))
    # time labels
    lab_f = font(10, MED)
    labc = (110, 120, 145) if not ambient else (80, 80, 80)
    d.text((gx0 + 2 * S, gy0 - 2 * S), "-3h", font=lab_f, fill=labc, anchor="ls")
    d.text((nx, gy0 - 2 * S), "now", font=lab_f, fill=labc, anchor="ms")

    # ---- pills -------------------------------------------------------------
    loop_col = GREEN if LOOP_MIN < 6 else AMBER if LOOP_MIN <= 14 else RED
    pf = font(17, DEMI); pl = font(10, MED)
    for box, kind in ((LOOP_P, "loop"), (IOB_P, "iob")):
        if not ambient:
            img = glass_card(img, box, 20 * S, alpha=18, edge=50)
        d = D(img)
        if ambient:
            rounded(d, box, 20 * S, outline=(70, 70, 70), width=S)
        x0, y0, x1, y1 = box
        cy = (y0 + y1) / 2
        if kind == "loop":
            dx = x0 + 22 * S
            if not ambient:
                img = glow(img, lambda g: g.ellipse([dx - 9 * S, cy - 9 * S, dx + 9 * S, cy + 9 * S], fill=loop_col + (200,)), 4)
                d = D(img)
                d.ellipse([dx - 5 * S, cy - 5 * S, dx + 5 * S, cy + 5 * S], fill=loop_col)
            else:
                d.ellipse([dx - 5 * S, cy - 5 * S, dx + 5 * S, cy + 5 * S], outline=(180, 180, 180), width=S)
            d.text((dx + 14 * S, cy), f"{LOOP_MIN}'", font=pf, fill=(240, 244, 250) if not ambient else (200, 200, 200), anchor="lm")
            d.text((x1 - 14 * S, cy), "LOOP", font=pl, fill=(130, 140, 165) if not ambient else (100, 100, 100), anchor="rm")
        else:
            d.text((x0 + 16 * S, cy), IOB, font=pf, fill=(240, 244, 250) if not ambient else (200, 200, 200), anchor="lm")
            d.text((x1 - 14 * S, cy), "IOB U", font=pl, fill=(130, 140, 165) if not ambient else (100, 100, 100), anchor="rm")
    d = D(img)
    # basal as a whisper line under the pills
    d.text((C, 396 * S), f"basal {BASAL} U/h", font=font(11, MED),
           fill=(100, 110, 135) if not ambient else (70, 70, 70), anchor="mm")

    # ---- bolus ball (left, interactive only; outline hint in ambient = none) -
    if not ambient:
        bx_, by_, br = BALL
        img = glow(img, lambda g: g.ellipse([bx_ - br - 4 * S, by_ - br - 4 * S, bx_ + br + 4 * S, by_ + br + 4 * S],
                                            fill=(90, 150, 255, 110)), 9)
        # sphere gradient
        sph = Image.new("RGBA", img.size, (0, 0, 0, 0))
        sd = ImageDraw.Draw(sph)
        for k in range(br, 0, -S):
            t = 1 - k / br
            col = mix((40, 70, 150), (120, 175, 255), t ** 0.8)
            off = int(t * 8 * S)
            sd.ellipse([bx_ - k - off // 2, by_ - k - off, bx_ + k - off // 2, by_ + k - off], fill=col + (255,))
        m = Image.new("L", img.size, 0)
        ImageDraw.Draw(m).ellipse([bx_ - br, by_ - br, bx_ + br, by_ + br], fill=255)
        sph.putalpha(ImageChops.multiply(sph.getchannel("A"), m))
        img = Image.alpha_composite(img, sph)
        d = D(img)
        d.ellipse([bx_ - br, by_ - br, bx_ + br, by_ + br], outline=(200, 225, 255, 150), width=S)
        # specular highlight
        hl = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(hl).ellipse([bx_ - 18 * S, by_ - 28 * S, bx_ + 6 * S, by_ - 14 * S], fill=(255, 255, 255, 70))
        img = Image.alpha_composite(img, hl.filter(ImageFilter.GaussianBlur(3 * S)))
        d = D(img)
        d.text((bx_, by_ - 5 * S), "+0.5", font=font(15, BOLD), fill=(255, 255, 255), anchor="mm")
        d.text((bx_, by_ + 12 * S), "U", font=font(10, DEMI), fill=(215, 230, 255), anchor="mm")
        d.text((bx_, by_ + br + 12 * S), "2× tap", font=font(9, MED), fill=(110, 125, 160), anchor="mm")

    out = round_mask(img).resize((450, 450), Image.LANCZOS)
    return out

if __name__ == "__main__":
    print("fonts", N)
    render(False).save(os.path.join(OUT, "glass_interactive.png"))
    render(True).save(os.path.join(OUT, "glass_ambient.png"))
    a = Image.open(os.path.join(OUT, "glass_interactive.png"))
    b = Image.open(os.path.join(OUT, "glass_ambient.png"))
    sheet = Image.new("RGB", (930, 470), (40, 40, 44))
    sheet.paste(a, (10, 10)); sheet.paste(b, (470, 10))
    sheet.save(os.path.join(OUT, "glass_sheet.png"))
    print("ok")
