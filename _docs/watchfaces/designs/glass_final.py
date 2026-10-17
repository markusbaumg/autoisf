"""AAPS Glass final mockups (Galaxy Watch5 Pro, 450x450 round). Coordinates are 450-space px.
Constants mirror glass_spec.md."""
import math, os, random
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops

OUT = os.path.dirname(os.path.abspath(__file__))
S = 3
W = 450 * S
C = W // 2
AVN = "/System/Library/Fonts/Avenir Next.ttc"
ARR = "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"

def _idx():
    n = {}
    for i in range(12):
        try: n[ImageFont.truetype(AVN, 20, index=i).getname()[1]] = i
        except Exception: break
    return n
N = _idx()
BOLD = N.get("Bold", 0); DEMI = N.get("Demi Bold", 2); MED = N.get("Medium", 5)
REG = N.get("Regular", 7); ULT = N.get("Ultra Light", 8)
def F(size, idx): return ImageFont.truetype(AVN, int(size * S), index=idx)
def P(*v): return [x * S for x in v]
def hx(h, a=255): h = h.lstrip("#"); return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)
def lerp(a, b, t): return a + (b - a) * t
def mix(c1, c2, t): return tuple(int(lerp(a, b, t)) for a, b in zip(c1, c2))

# ---- palette (spec) ----
BG_TOP, BG_MID, BG_BOT = hx("#141A33"), hx("#0B0F22"), hx("#04050B")
RANGE = {"in": hx("#78E6BE"), "high": hx("#FFB840"), "low": hx("#FF5C5C"), "stale": hx("#8A93A8")}
LOOP_OK, LOOP_WARN, LOOP_BAD = hx("#40D68C"), hx("#FFB840"), hx("#FF5C5C")
TXT1, TXT2, TXT3, LBL = hx("#EEF1F7"), hx("#9AA6C4"), hx("#6F7A99"), hx("#8E99B8")
DELTA_COL = hx("#C8D2E6")
WARN, ALERT = hx("#FFB840"), hx("#FF5C5C")
PRED = hx("#AA82FF"); BALL_LO, BALL_HI = hx("#28468F"), hx("#7AAFFF")
TT_COL = hx("#E8D28A")
A_TXT1, A_TXT2, A_TXT3, A_LINE = hx("#E0E0E0"), hx("#9A9A9A"), hx("#6E6E6E"), hx("#3C3C3C")

LOW, HIGH = 3.9, 10.0

def series(start, end, k, last, seed):
    random.seed(seed)
    v = [start + (end - start) / (1 + math.exp(-(i / 35 - 0.55) * k)) + random.uniform(-0.1, 0.1) for i in range(36)]
    v[-1] = last
    return v

STATES = {
    # reference values from the user
    "in": dict(bg="9.4", val=9.4, arrow="→", delta="+0.1", age=1, iob="2.53", loop=0, cob=0, tt="5.6",
               basal="0.00", pct=100, phone_batt=80, disc=None,
               hist=series(7.0, 9.6, 9, 9.4, 4), pred=[9.4 - 0.05 * k - 0.004 * k * k for k in range(1, 13)]),
    "high": dict(bg="11.8", val=11.8, arrow="↗", delta="+0.6", age=2, iob="4.12", loop=3, cob=24, tt=None,
                 basal="1.35", pct=100, phone_batt=18, disc=None,
                 hist=series(6.5, 11.6, 10, 11.8, 7), pred=[11.8 + 0.08 * k - 0.012 * k * k for k in range(1, 13)]),
    "low": dict(bg="3.4", val=3.4, arrow="↓", delta="-0.4", age=1, iob="0.21", loop=1, cob=0, tt="8.0",
                basal="0.00", pct=90, phone_batt=60, disc=None,
                hist=series(6.8, 3.6, 8, 3.4, 11), pred=[3.4 - 0.05 * k + 0.012 * k * k for k in range(1, 13)]),
    "stale": dict(bg="9.4", val=9.4, arrow="→", delta="+0.1", age=17, iob="2.53", loop=17, cob=0, tt="5.6",
                  basal="0.00", pct=100, phone_batt=80, disc=14,
                  hist=series(7.0, 9.6, 9, 9.4, 4)[3:] + [None] * 3, pred=[]),
}
TIME, DATE = "00:40", "Fr 17. Okt"

# ---- layout (450 px space) ----
TIME_Y, DATE_Y = 58, 92
HERO = (92, 110, 358, 240); HERO_R = 30
BG_C = (184, 176)
CHIP = (312, 152, 22)          # r=22, glyph 30 px
META_Y = 200                   # "+0.1 · 1'" 18 px
GRAPH = (84, 256, 366, 312)
PILL_Y0, PILL_Y1 = 324, 366
LOOP_P = (106, PILL_Y0, 219, PILL_Y1); IOB_P = (231, PILL_Y0, 344, PILL_Y1)
FOOT_Y = 390
BALL = (46, 176, 30)           # halo r=32, armed ring r=34

def D(im):
    d = ImageDraw.Draw(im); im.load(); d.draw = Image.core.draw(im.im, 1); return d

def glow(base, fn, radius):
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0)); fn(ImageDraw.Draw(layer))
    return Image.alpha_composite(base, layer.filter(ImageFilter.GaussianBlur(radius * S)))

def glass(base, box, r, alpha=22, edge=60):
    x0, y0, x1, y1 = P(*box)
    mask = Image.new("L", base.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([x0, y0, x1, y1], radius=r * S, fill=255)
    base = Image.composite(base.filter(ImageFilter.GaussianBlur(14 * S)), base, mask)
    grad = Image.new("RGBA", base.size, (0, 0, 0, 0)); gd = ImageDraw.Draw(grad)
    for y in range(y0, y1):
        t = (y - y0) / max(1, y1 - y0)
        gd.line([(x0, y), (x1, y)], fill=(255, 255, 255, int(lerp(alpha * 1.6, alpha * 0.5, t))))
    grad.putalpha(ImageChops.multiply(grad.getchannel("A"), mask))
    base = Image.alpha_composite(base, grad)
    d = D(base)
    d.rounded_rectangle([x0, y0, x1, y1], radius=r * S, outline=(255, 255, 255, edge), width=S)
    d.line([x0 + r * S, y0 + S, x1 - r * S, y0 + S], fill=(255, 255, 255, edge + 40), width=S)
    return base

def outline_box(d, box, r, col):
    d.rounded_rectangle(P(*box), radius=r * S, outline=col, width=S)

def by(v):
    lo, hi = 3.0, 12.0
    v = max(lo, min(hi, v))
    return (GRAPH[3] - (v - lo) / (hi - lo) * (GRAPH[3] - GRAPH[1])) * S

def gx(i, n):
    return (GRAPH[0] + i / (n - 1) * (GRAPH[2] - GRAPH[0])) * S

def runs_line(d, y, runs, font):
    ws = [d.textlength(s, font=font) for s, _ in runs]
    x = C - sum(ws) / 2
    for (s, col), w in zip(runs, ws):
        d.text((x, y), s, font=font, fill=col, anchor="lm"); x += w

def ball(img, armed, progress=0.65):
    bx, byy, br = [v * S for v in BALL]
    lo, hi = (hx("#8A5A10"), hx("#FFC864")) if armed else (BALL_LO, BALL_HI)
    halo = hx("#FFB840", 120) if armed else hx("#5A96FF", 100)
    img = glow(img, lambda g: g.ellipse([bx - br - 2 * S, byy - br - 2 * S, bx + br + 2 * S, byy + br + 2 * S], fill=halo), 7)
    sph = Image.new("RGBA", img.size, (0, 0, 0, 0)); sd = ImageDraw.Draw(sph)
    for k in range(br, 0, -S):
        t = 1 - k / br
        off = int(t * 8 * S)
        sd.ellipse([bx - k - off // 2, byy - k - off, bx + k - off // 2, byy + k - off], fill=mix(lo, hi, t ** 0.8)[:3] + (235,))
    m = Image.new("L", img.size, 0); ImageDraw.Draw(m).ellipse([bx - br, byy - br, bx + br, byy + br], fill=255)
    sph.putalpha(ImageChops.multiply(sph.getchannel("A"), m))
    img = Image.alpha_composite(img, sph)
    hl = Image.new("RGBA", img.size, (0, 0, 0, 0))
    ImageDraw.Draw(hl).ellipse([bx - 17 * S, byy - 26 * S, bx + 5 * S, byy - 13 * S], fill=(255, 255, 255, 75))
    img = Image.alpha_composite(img, hl.filter(ImageFilter.GaussianBlur(3 * S)))
    d = D(img)
    d.ellipse([bx - br, byy - br, bx + br, byy + br], outline=(255, 255, 255, 150), width=S)
    if armed:
        rr = 34 * S
        d.arc([bx - rr, byy - rr, bx + rr, byy + rr], 0, 360, fill=hx("#FFB840", 50), width=2 * S)
        d.arc([bx - rr, byy - rr, bx + rr, byy + rr], -90, -90 + 360 * progress, fill=hx("#FFB840"), width=3 * S)
    d.text((bx, byy - 3 * S), "+0.5", font=F(18, BOLD), fill=hx("#FFFFFF"), anchor="mm")
    d.text((bx, byy + 13 * S), "U", font=F(12, DEMI), fill=hx("#FFF0D0") if armed else hx("#D7E6FF"), anchor="mm")
    return img

def render(mode, key="in", lowbit=False):
    st = STATES[key]
    amb = mode == "ambient"
    stale = st["age"] >= 12
    rk = "stale" if stale else ("high" if st["val"] > HIGH else "low" if st["val"] < LOW else "in")
    rng = RANGE[rk]
    img = Image.new("RGBA", (W, W), (0, 0, 0, 255))
    d = D(img)
    if not amb:
        for y in range(W):
            t = y / W
            col = mix(BG_TOP, BG_MID, t / 0.55) if t < 0.55 else mix(BG_MID, BG_BOT, (t - 0.55) / 0.45)
            d.line([(0, y), (W, y)], fill=col)
        # aurora: range blob + core blob (signature), violet + blue accents
        img = glow(img, lambda g: g.ellipse(P(130, 100, 320, 250), fill=rng[:3] + (150,)), 40)
        img = glow(img, lambda g: g.ellipse(P(150, 120, 260, 220), fill=rng[:3] + (120,)), 26)
        img = glow(img, lambda g: g.ellipse(P(240, 220, 410, 340), fill=hx("#5A46C8", 85)), 44)
        img = glow(img, lambda g: g.ellipse(P(40, 230, 180, 330), fill=hx("#2A5AC8", 60)), 44)
        d = D(img)
        d.ellipse([3 * S, 3 * S, W - 3 * S, W - 3 * S], outline=(255, 255, 255, 22), width=S)
    d = D(img)
    d.text((C, TIME_Y * S), TIME, font=F(42, ULT if amb else REG), fill=A_TXT1 if amb else TXT1, anchor="mm")
    d.text((C, DATE_Y * S), DATE, font=F(18, MED), fill=A_TXT2 if amb else TXT2, anchor="mm")

    # hero card
    if amb: outline_box(d, HERO, HERO_R, A_LINE)
    else: img = glass(img, HERO, HERO_R)
    d = D(img)
    bgf = F(84 if len(st["bg"]) <= 3 else 74, REG if amb else DEMI)
    bx, byy = BG_C[0] * S, BG_C[1] * S
    if not amb and not stale:
        img = glow(img, lambda g: g.text((bx, byy), st["bg"], font=bgf, fill=rng[:3] + (150,), anchor="mm"), 6)
        d = D(img)
    bgcol = (A_TXT1 if amb else TXT2) if stale else (A_TXT1 if amb else hx("#FFFFFF"))
    d.text((bx, byy), st["bg"], font=bgf, fill=bgcol, anchor="mm")
    if stale:
        bb = d.textbbox((bx, byy), st["bg"], font=bgf, anchor="mm")
        d.line([bb[0] - 4 * S, byy + 2 * S, bb[2] + 4 * S, byy + 2 * S], fill=bgcol, width=3 * S if not amb else 2 * S)
    cx, cy, cr = [v * S for v in CHIP]
    af = ImageFont.truetype(ARR, 30 * S)
    if amb:
        d.ellipse([cx - cr, cy - cr, cx + cr, cy + cr], outline=A_TXT3, width=S)
        if not stale: d.text((cx, cy + S), st["arrow"], font=af, fill=A_TXT1, anchor="mm")
    else:
        d.ellipse([cx - cr, cy - cr, cx + cr, cy + cr], fill=rng[:3] + (40,), outline=rng[:3] + (210,), width=2 * S)
        if not stale: d.text((cx, cy + S), st["arrow"], font=af, fill=rng, anchor="mm")
    age = st["age"]
    age_col = ALERT if age >= 12 else WARN if age >= 6 else TXT2
    if amb: age_col = A_TXT1 if age >= 6 else A_TXT2
    mf = F(18, MED)
    runs = [] if stale else [(st["delta"], A_TXT2 if amb else DELTA_COL), (" · ", A_TXT3 if amb else TXT3)]
    runs.append((f"{age}'", age_col))
    ws = [d.textlength(s, font=mf) for s, _ in runs]
    x = cx - sum(ws) / 2
    for (s, col), w in zip(runs, ws):
        d.text((x, META_Y * S), s, font=mf, fill=col, anchor="lm"); x += w

    # sparkline
    gx0, gy0, gx1, gy1 = P(*GRAPH)
    hist = [v for v in st["hist"]]
    nh, ntot = 36, 48
    if not amb:
        d.rounded_rectangle([gx0, by(HIGH), gx1, by(LOW)], radius=8 * S, fill=rng[:3] + (24,))
    if not lowbit:
        for lv in (LOW, HIGH):
            y = by(lv)
            for x in range(gx0, gx1, 8 * S):
                d.line([x, y, x + 3 * S, y], fill=A_LINE if amb else (255, 255, 255, 46), width=S)
        nx = gx(nh - 1, ntot)
        for y in range(gy0, gy1, 6 * S):
            d.line([nx, y, nx, y + 2 * S], fill=A_LINE if amb else (255, 255, 255, 60), width=S)
    pts = [(gx(i, ntot), by(v)) for i, v in enumerate(hist) if v is not None]
    if amb:
        d.line(pts, fill=hx("#B4B4B4"), width=int(1.5 * S), joint="curve")
    else:
        img = glow(img, lambda g: g.line(pts, fill=rng[:3] + (180,), width=4 * S, joint="curve"), 4)
        d = D(img)
        for k in range(len(pts) - 1):
            d.line([pts[k], pts[k + 1]], fill=mix(hx("#5AAAFF"), rng, k / len(pts)), width=int(2.5 * S))
        for k, v in enumerate(st["pred"]):
            x, y = gx(nh + k, ntot), by(v); rr = 1.6 * S
            d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=PRED[:3] + (210,))
    px, py = pts[-1]
    if amb:
        d.ellipse([px - 3 * S, py - 3 * S, px + 3 * S, py + 3 * S], fill=(0, 0, 0), outline=A_TXT1, width=S)
    else:
        if not stale:
            img = glow(img, lambda g: g.ellipse([px - 7 * S, py - 7 * S, px + 7 * S, py + 7 * S], fill=rng[:3] + (220,)), 5)
        d = D(img)
        d.ellipse([px - 4 * S, py - 4 * S, px + 4 * S, py + 4 * S], fill=(255, 255, 255) if not stale else TXT3, outline=rng, width=2 * S)

    # pills
    lm = st["loop"]
    loop_col = LOOP_OK if lm < 6 else LOOP_WARN if lm < 15 else LOOP_BAD
    pf, pl = F(20, DEMI), F(12, DEMI)
    for box, kind in ((LOOP_P, "loop"), (IOB_P, "iob")):
        if amb: outline_box(d, box, 21, A_LINE)
        else: img = glass(img, box, 21, alpha=18, edge=55)
        d = D(img)
        x0, y0, x1, y1 = P(*box); my = (y0 + y1) / 2
        if kind == "loop":
            dx = x0 + 20 * S
            if amb:
                d.ellipse([dx - 5 * S, my - 5 * S, dx + 5 * S, my + 5 * S], outline=A_TXT1 if lowbit else loop_col, width=S if lowbit else 2 * S)
            else:
                img = glow(img, lambda g: g.ellipse([dx - 9 * S, my - 9 * S, dx + 9 * S, my + 9 * S], fill=loop_col[:3] + (190,)), 4)
                d = D(img)
                d.ellipse([dx - 5 * S, my - 5 * S, dx + 5 * S, my + 5 * S], fill=loop_col)
            lv = f"{lm}'" + ("!" if (lowbit and lm >= 15) else "")
            d.text((dx + 13 * S, my), lv, font=pf, fill=A_TXT1 if amb else (loop_col if lm >= 15 else TXT1), anchor="lm")
            if lm < 10: d.text((x1 - 14 * S, my + S), "LOOP", font=pl, fill=A_TXT3 if amb else LBL, anchor="rm")
        else:
            d.text((x0 + 16 * S, my), st["iob"], font=pf, fill=A_TXT1 if amb else TXT1, anchor="lm")
            d.text((x1 - 14 * S, my + S), "IOB", font=pl, fill=A_TXT3 if amb else LBL, anchor="rm")
    d = D(img)

    # footer
    ff = F(18, MED)
    sep = ("  ·  ", A_TXT3 if amb else TXT3)
    if st["disc"] is not None:
        runs = [(f"Telefon getrennt · {st['disc']}'", A_TXT1 if amb else ALERT)]
    else:
        items = []
        if st["tt"]: items.append((f"TT {st['tt']}", A_TXT2 if amb else TT_COL))
        if st["cob"] > 0: items.append((f"COB {st['cob']} g", A_TXT2 if amb else TXT2))
        items.append((f"{st['basal']} U/h", A_TXT2 if amb else TXT2))
        if st["pct"] != 100: items.append((f"{st['pct']}%", A_TXT2 if amb else TXT2))
        if st["phone_batt"] < 20:
            items.append((f"Tel {st['phone_batt']}%", A_TXT1 if amb else (ALERT if st["phone_batt"] < 10 else WARN)))
        runs = []
        for i, it in enumerate(items):
            if i: runs.append(sep)
            runs.append(it)
    runs_line(d, FOOT_Y * S, runs, ff)

    if mode in ("interactive", "armed"):
        img = ball(img, armed=(mode == "armed"))

    m = Image.new("L", img.size, 0); ImageDraw.Draw(m).ellipse([0, 0, W - 1, W - 1], fill=255)
    out = Image.new("RGB", img.size, (0, 0, 0)); out.paste(img.convert("RGB"), (0, 0), m)
    return out.resize((450, 450), Image.LANCZOS)

if __name__ == "__main__":
    jobs = [("interactive", "in", "glass_final_interactive"), ("ambient", "in", "glass_final_ambient"),
            ("armed", "in", "glass_final_armed"), ("off", "in", "glass_final_bolus_off"),
            ("off", "high", "glass_final_high"), ("off", "low", "glass_final_low"),
            ("off", "stale", "glass_final_stale"), ("ambient", "stale", "glass_final_ambient_stale")]
    ims = {}
    for mode, key, name in jobs:
        ims[name] = render(mode, key); ims[name].save(os.path.join(OUT, name + ".png"))
    labels = [("glass_final_interactive", "interactive, bolus on"), ("glass_final_armed", "armed (1st tap)"),
              ("glass_final_bolus_off", "bolus off (default)"), ("glass_final_ambient", "ambient"),
              ("glass_final_high", "high + COB + phone 18%"), ("glass_final_low", "low, TT, profile 90%"),
              ("glass_final_stale", "stale 17' + disconnected"), ("glass_final_ambient_stale", "ambient, stale")]
    sheet = Image.new("RGB", (1850, 990), (40, 40, 44)); sd = ImageDraw.Draw(sheet)
    lf = ImageFont.truetype(AVN, 18, index=MED)
    for i, (k, lab) in enumerate(labels):
        x, y = 10 + (i % 4) * 460, 10 + (i // 4) * 490
        sheet.paste(ims[k], (x, y)); sd.text((x + 225, y + 468), lab, font=lf, fill=(200, 200, 205), anchor="mm")
    sheet.save(os.path.join(OUT, "glass_final_sheet.png"))
    a = ims["glass_final_ambient"].convert("L"); px = list(a.tobytes())
    print("ambient lit >24:", round(sum(1 for p in px if p > 24) / len(px) * 100, 2), "%")
    im = ims["glass_final_interactive"]
    print("aurora px behind hero (150,130):", im.getpixel((150, 130)), "(270,225):", im.getpixel((270, 225)))
