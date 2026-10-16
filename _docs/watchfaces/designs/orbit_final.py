"""AAPS Orbit FINAL mockups (450x450 round, Galaxy Watch5 Pro). Pillow only."""
import math, os
from PIL import Image, ImageDraw, ImageFont, ImageFilter

OUT = os.path.dirname(os.path.abspath(__file__))
S = 4
W = 450 * S
C = W / 2
FONT = "/System/Library/Fonts/SFNSRounded.ttf"


def font(sz, weight="Regular"):
    f = ImageFont.truetype(FONT, int(sz * S))
    try:
        f.set_variation_by_name(weight)
    except Exception:
        pass
    return f


def hx(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


LOW, HIGH = 3.9, 10.0
HIST = [6.6, 6.7, 6.9, 7.0, 7.3, 7.6, 7.9, 8.3, 8.6, 8.9, 9.1, 9.2,
        9.3, 9.3, 9.4, 9.4, 9.5, 9.4, 9.4, 9.3, 9.4, 9.4, 9.4, 9.4]
PRED = [9.4, 9.35, 9.3, 9.2, 9.1, 9.0]

BASE = dict(bg="9.4", arrow="→", delta="+0.1", age=1, iob="2.53", cob=0, basal="0.00",
            tbr=True, tt="5.6", profile=100, loop_min=0, loop_state=None,
            time="00:40", minute=40, date="Fr 17. Okt", bolus="0.5",
            phone_ok=True, warn=None)

# ---- palette ----
BG_IN, BG_HI, BG_LO = "#3DDC97", "#FFB547", "#FF5A6E"
IOB_C, TBR_C, AMBER, TT_C, RED = "#5AC8FA", "#3DDBD9", "#FFB547", "#B388FF", "#FF5A6E"
NEUTRAL_RING, DIM_TXT, LBL_NEUTRAL = "#5A6B85", "#7D8796", "#9AA6B8"
BALL_FILL = "#0E131B"
VAL_TXT = "#EEF2F7"
TRACK = "#2A3446"
TIME_C, DATE_C, SUB_C = "#E6ECF4", "#A9B4C4", "#AAB6C6"
BOLUS_C, BOLUS_ARMED_FILL = "#7888FF", "#5B63F5"
PRED_C = "#B388FF"

ORB = 168
BALL_R = 34
BOL_H = 33          # half side of bolus "button" squircle
BOL_CR = 16         # corner radius


def bg_col(v):
    return BG_LO if v < LOW else BG_HI if v > HIGH else BG_IN


def loop_col(m):
    return BG_IN if m <= 5 else AMBER if m < 15 else RED


def P(r, deg, ox=0, oy=0):
    a = math.radians(deg - 90)
    return C + ox * S + r * S * math.cos(a), C + oy * S + r * S * math.sin(a)


def tc(d, xy, s, f, fill, anchor="mm"):
    d.text(xy, s, font=f, fill=fill, anchor=anchor)


def glow(img, fn, rad):
    lay = Image.new("RGBA", img.size, (0, 0, 0, 0))
    fn(ImageDraw.Draw(lay))
    img.alpha_composite(lay.filter(ImageFilter.GaussianBlur(rad * S)))


def halfang(txt, f, r, pad):
    return math.degrees((f.getlength(txt) / S + 2 * pad) / 2 / r)


def squircle_path(x, y, h, cr, n=12):
    """Clockwise perimeter starting at top-centre."""
    pts = [(x, y - h)]
    corners = [(x + h - cr, y - h + cr, -90), (x + h - cr, y + h - cr, 0),
               (x - h + cr, y + h - cr, 90), (x - h + cr, y - h + cr, 180)]
    for cx, cy, a0 in corners:
        for i in range(n + 1):
            a = math.radians(a0 + 90 * i / n)
            pts.append((cx + cr * math.cos(a), cy + cr * math.sin(a)))
    pts.append((x, y - h))
    return pts


def partial(pts, frac):
    segs = [math.dist(pts[i], pts[i + 1]) for i in range(len(pts) - 1)]
    tot, out, acc = sum(segs) * frac, [pts[0]], 0
    for i, s in enumerate(segs):
        if acc + s >= tot:
            t = (tot - acc) / s if s else 0
            out.append((pts[i][0] + (pts[i + 1][0] - pts[i][0]) * t, pts[i][1] + (pts[i + 1][1] - pts[i][1]) * t))
            return out
        out.append(pts[i + 1]); acc += s
    return out


def drop(d, x, y, h, fill):
    """Teardrop glyph, h tall, centred on (x,y)."""
    r = h * 0.36
    cy = y + h / 2 - r
    pts = [(x, y - h / 2)]
    for i in range(0, 181, 10):
        a = math.radians(-20 + i * (220 / 180))  # sweep under
        pts.append((x + r * math.cos(a), cy + r * math.sin(a)))
    d.polygon([(x, y - h / 2), (x + r * 0.94, cy - r * 0.34)] +
              [(x + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a))) for a in range(-20, 201, 8)] +
              [(x - r * 0.94, cy - r * 0.34)], fill=fill)


def render(mode, **ov):
    v = dict(BASE); v.update(ov)
    amb = mode == "ambient"
    bolus_on = mode in ("interactive", "armed")
    armed = mode == "armed"
    ox, oy = (v.get("shift", (0, 0)) if amb else (0, 0))
    Pp = lambda r, deg: P(r, deg, ox, oy)
    CX, CY = C + ox * S, C + oy * S
    img = Image.new("RGBA", (W, W), (0, 0, 0, 255))
    d = ImageDraw.Draw(img)

    bgv = float(v["bg"])
    stale_warn = v["age"] >= 12
    stale = v["age"] > 15
    bgc = "#7D8796" if stale_warn else bg_col(bgv)

    if not amb and not stale_warn:
        glow(img, lambda g: g.ellipse([CX - 120 * S, CY - 110 * S, CX + 120 * S, CY + 90 * S],
                                      fill=hx(bgc, 34)), 60)
        d = ImageDraw.Draw(img)

    # ---- orbit track with gaps ----
    f_time, f_date = font(30, "Semibold"), font(18, "Medium")
    date_txt = v["date"] + (f"  ·  {v['warn']}" if v["warn"] else "")
    gap_t = halfang(v["time"], f_time, ORB, 10)
    gap_d = halfang(date_txt, f_date, ORB, 10)
    box = [CX - ORB * S, CY - ORB * S, CX + ORB * S, CY + ORB * S]
    trk = hx("#4A4A4A") if amb else hx(TRACK)
    for a0, a1 in [(gap_t, 180 - gap_d), (180 + gap_d, 360 - gap_t)]:
        d.arc(box, a0 - 90, a1 - 90, fill=trk, width=int((1 if amb else 1.5) * S))

    # ball layout (fixed; never moves between modes)
    loop_txt, loop_c = f"{v['loop_min']}'", loop_col(v["loop_min"])
    if v["loop_state"]:
        loop_txt, loop_c = v["loop_state"], RED
    basal_c = (AMBER if float(v["basal"]) == 0 else TBR_C) if v["tbr"] else None
    tt_active = v["tt"] is not None
    balls = [
        (45, loop_txt, "LOOP", loop_c),
        (90, v["iob"], "IOB", IOB_C),
        (135, v["basal"], "TBR" if v["tbr"] else "BASAL", basal_c),
        (225, f"{v['cob']}g", "COB", AMBER if v["cob"] > 0 else "dim"),
        (315, v["tt"] if tt_active else f"{v['profile']}%",
         ("TT" if v["profile"] == 100 else f"TT {v['profile']}%") if tt_active else "Profil",
         TT_C if tt_active else "dim"),
    ]

    # ---- minute moon (interactive only) ----
    mdeg = v["minute"] * 6
    occluder = None
    if not amb:
        bal_half = math.degrees(math.asin(BALL_R / ORB)) + 1.5
        blockers = [(b[0], bal_half) for b in balls]
        if bolus_on:
            blockers.append((270, math.degrees(math.atan(BOL_H * 1.1 / ORB)) + 1.5))
        in_gap = (mdeg % 360) < gap_t + 2 or (mdeg % 360) > 360 - gap_t - 2 or abs(mdeg - 180) < gap_d + 2
        for bd, bh in blockers:
            diff = (mdeg - bd + 540) % 360 - 180
            if abs(diff) < bh:
                occluder = (bd, diff, bh)
        if not in_gap and occluder is None:
            mx, my = Pp(ORB, mdeg)
            mr = 4.5 * S
            d.ellipse([mx - mr, my - mr, mx + mr, my + mr], fill=hx("#F2F6FC"))

    # ---- time + date (+ conditional warnings) ----
    tx, ty = Pp(ORB, 0)
    time_col = hx("#BDBDBD") if amb else hx(TIME_C)
    tc(d, (tx, ty), v["time"], f_time, time_col)
    if not v["phone_ok"]:
        fh = font(15, "Semibold"); hc = hx("#BDBDBD") if amb else hx(RED)
        wh = fh.getlength("Handy"); xs = tx - (wh + 14 * S) / 2; yh = ty + 30 * S
        k = 4.5 * S
        d.line([(xs, yh - k), (xs + 2 * k, yh + k)], fill=hc, width=int(2 * S))
        d.line([(xs, yh + k), (xs + 2 * k, yh - k)], fill=hc, width=int(2 * S))
        d.text((xs + 14 * S, yh), "Handy", font=fh, fill=hc, anchor="lm")
    dx, dy = Pp(ORB, 180)
    if v["warn"]:
        f1 = f_date
        w1 = f1.getlength(v["date"] + "  ·  ")
        wt = f1.getlength(date_txt)
        x0 = dx - wt / 2
        d.text((x0, dy), v["date"] + "  ·  ", font=f1, fill=hx("#8E8E8E") if amb else hx(DATE_C), anchor="lm")
        d.text((x0 + w1, dy), v["warn"], font=f1, fill=hx("#BDBDBD") if amb else hx(AMBER), anchor="lm")
    else:
        tc(d, (dx, dy), v["date"], f_date, hx("#8E8E8E") if amb else hx(DATE_C))

    # ---- hero BG ----
    f_bg = font(108, "Light" if amb else "Bold")
    bx0, by0 = CX - 16 * S, CY - 34 * S
    if not amb and not stale_warn:
        glow(img, lambda g: tc(g, (bx0, by0), v["bg"], f_bg, hx(bgc, 120)), 12)
        d = ImageDraw.Draw(img)
    col = hx("#D6D6D6") if amb else hx(bgc)
    tc(d, (bx0, by0), v["bg"], f_bg, col)
    bb = d.textbbox((bx0, by0), v["bg"], font=f_bg, anchor="mm")
    tc(d, (bb[2] + 24 * S, by0 + 2 * S), v["arrow"], font(44, "Regular" if amb else "Semibold"), col)
    if stale:
        yy = by0 + 4 * S
        d.line([(bb[0] - 6 * S, yy), (bb[2] + 46 * S, yy)], fill=col, width=int((1.5 if amb else 3) * S))

    # ---- delta row / stale pill ----
    ry = CY + 26 * S
    if stale_warn:
        f_p = font(19, "Semibold")
        txt = (f"– – {v['age']}' alt") if amb else f"⚠ {v['age']}' alt"
        if amb:
            tc(d, (CX, ry), txt, f_p, hx("#BDBDBD"))
        else:
            w = f_p.getlength(txt) / S
            d.rounded_rectangle([CX - (w / 2 + 14) * S, ry - 15 * S, CX + (w / 2 + 14) * S, ry + 15 * S],
                                radius=15 * S, fill=hx("#3A1018"), outline=hx(RED), width=int(1.5 * S))
            tc(d, (CX, ry), txt, f_p, hx("#FFD6DC"))
    else:
        f_sub = font(21, "Medium")
        subc = hx("#9A9A9A") if amb else hx(SUB_C)
        agec = subc if amb or v["age"] <= 5 else hx(AMBER)
        left = f"{v['delta']}  ·  "
        wl, wa = f_sub.getlength(left), f_sub.getlength(f"{v['age']}'")
        x0 = CX - (wl + wa) / 2
        d.text((x0, ry), left, font=f_sub, fill=subc, anchor="lm")
        d.text((x0 + wl, ry), f"{v['age']}'", font=f_sub, fill=agec, anchor="lm")

    # ---- sparkline ----
    x0, x1 = CX - 74 * S, CX + 66 * S
    ytop, ybot = CY + 50 * S, CY + 96 * S
    lo, hi = 2.5, 14.0

    def yv(val):
        return ybot - (min(max(val, lo), hi) - lo) / (hi - lo) * (ybot - ytop)
    hist = HIST[:-3] if stale_warn else HIST
    n = len(HIST)
    xp = x1 - 26 * S
    step = (xp - x0) / (n - 1)
    xy = [(x0 + i * step, yv(val)) for i, val in enumerate(hist)]
    if not amb:
        for yy in (yv(HIGH), yv(LOW)):
            for xx in range(int(x0), int(x1), int(6 * S)):
                d.line([(xx, yy), (xx + 2 * S, yy)], fill=hx(BG_IN, 120), width=int(1 * S))
        lc = bgc
        if not stale_warn:
            glow(img, lambda g: g.line(xy, fill=hx(lc, 150), width=int(4 * S), joint="curve"), 4)
            d = ImageDraw.Draw(img)
        d.line(xy, fill=hx(lc), width=int(2.5 * S), joint="curve")
        if not stale_warn:
            for i, pv in enumerate(PRED[1:], 1):
                px = xp + i * (26 * S / (len(PRED) - 1))
                r = 2.2 * S
                d.ellipse([px - r, yv(pv) - r, px + r, yv(pv) + r], fill=hx(PRED_C, 220))
    else:
        d.line(xy, fill=hx("#7A7A7A"), width=int(1.5 * S), joint="curve")
    ex, ey = xy[-1]
    r = 4 * S
    if amb or stale_warn:
        d.ellipse([ex - r, ey - r, ex + r, ey + r], outline=hx("#C8C8C8"), width=int(1.2 * S))
    else:
        d.ellipse([ex - r, ey - r, ex + r, ey + r], fill=hx("#FFFFFF"))

    # ---- status balls ----
    f_val, f_lbl = font(22, "Semibold"), font(14, "Semibold")
    for deg, val, lab, rc in balls:
        x, y = Pp(ORB, deg)
        rr = BALL_R * S
        fv = f_val if f_val.getlength(val) / S < 56 else font(17, "Bold")
        if amb:
            d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=hx("#000000"),
                      outline=hx("#6E6E6E"), width=int(1.2 * S))
            tc(d, (x, y - 7 * S), val, font(22, "Regular") if fv is f_val else font(17, "Medium"), hx("#C8C8C8"))
            tc(d, (x, y + 15 * S), lab, f_lbl, hx("#9A9A9A"))
            continue
        dim = rc == "dim"
        ring = hx("#3A4250") if dim else hx(rc) if rc else hx(NEUTRAL_RING)
        d.ellipse([x - rr, y - rr, x + rr, y + rr], fill=hx(BALL_FILL), outline=ring, width=int(2 * S))
        if rc and not dim:
            lay = Image.new("RGBA", (W, W), (0, 0, 0, 0))
            ImageDraw.Draw(lay).ellipse([x - rr * 0.7, y - rr * 0.92, x + rr * 0.7, y - rr * 0.1], fill=hx(rc, 26))
            img.alpha_composite(lay.filter(ImageFilter.GaussianBlur(3 * S)))
            d = ImageDraw.Draw(img)
        tc(d, (x, y - 7 * S), val, fv, hx(DIM_TXT) if dim else hx(VAL_TXT))
        lc = hx(DIM_TXT) if dim else hx(rc, 235) if rc else hx(LBL_NEUTRAL)
        tc(d, (x, y + 15 * S), lab, f_lbl, lc)
        # moon passing behind this ball: light the rim
        if occluder and occluder[0] == deg:
            _, diff, bh = occluder
            ha = deg + diff / bh * 90          # clockwise-from-12 on the ball rim
            ro = rr + 1 * S
            d.arc([x - ro, y - ro, x + ro, y + ro], ha - 90 - 22, ha - 90 + 22, fill=hx("#FFFFFF", 220),
                  width=int(1.6 * S))

    # ---- bolus button at 9 o'clock ----
    if bolus_on and not amb:
        x, y = Pp(ORB, 270)
        h, cr = BOL_H * S, BOL_CR * S
        rect = [x - h, y - h, x + h, y + h]
        if armed:
            glow(img, lambda g: g.rounded_rectangle(rect, radius=cr, fill=hx(BOLUS_ARMED_FILL, 150)), 8)
            d = ImageDraw.Draw(img)
            d.rounded_rectangle(rect, radius=cr, fill=hx(BOLUS_ARMED_FILL))
            path = squircle_path(x, y, h + 0.5 * S, cr)
            d.line(path, fill=hx("#FFFFFF", 60), width=int(3.5 * S), joint="curve")
            rem = 0.6  # 60 % of the 1.5 s window left
            d.line(partial(path, rem), fill=hx("#FFFFFF"), width=int(3.5 * S), joint="curve")
            tc(d, (x, y - 8 * S), f"{v['bolus']} U", font(20, "Bold"), hx("#FFFFFF"))
            tc(d, (x, y + 13 * S), "nochmal", font(14, "Semibold"), hx("#FFFFFF"))
        else:
            d.rounded_rectangle(rect, radius=cr, fill=hx(BALL_FILL), outline=hx(BOLUS_C, 180), width=int(2 * S))
            drop(d, x, y - 12 * S, 15 * S, hx(BOLUS_C, 230))
            tc(d, (x, y + 11 * S), f"{v['bolus']} U", font(19, "Bold"), hx("#E8EBFF"))

    mask = Image.new("L", (W, W), 0)
    ImageDraw.Draw(mask).ellipse([0, 0, W - 1, W - 1], fill=255)
    out = Image.new("RGBA", (W, W), (0, 0, 0, 255))
    out.paste(img, (0, 0), mask)
    return out.resize((450, 450), Image.LANCZOS).convert("RGB")


jobs = [
    ("orbit_final_interactive.png", "interactive", {}),
    ("orbit_final_ambient.png", "ambient", {}),
    ("orbit_final_armed.png", "armed", {}),
    ("orbit_final_bolus_off.png", "bolus_off", {}),
    ("orbit_final_stale.png", "bolus_off", dict(age=17, loop_min=23, phone_ok=False, time="00:52",
                                                 minute=52, warn="Akku 15%")),
    ("orbit_final_stale_ambient.png", "ambient", dict(age=17, loop_state="PAUSE", phone_ok=False,
                                                      time="00:52", minute=52, shift=(2, -2))),
]
ims = []
for fn, m, ov in jobs:
    im = render(m, **ov)
    im.save(os.path.join(OUT, fn))
    ims.append((fn, im))
cols = 3
sheet = Image.new("RGB", (cols * 466 + 16, 2 * 490 + 16), (24, 24, 28))
sd = ImageDraw.Draw(sheet)
lf = ImageFont.truetype(FONT, 16)
for i, (fn, im) in enumerate(ims):
    cx, cy = 16 + (i % cols) * 466, 16 + (i // cols) * 490
    sheet.paste(im, (cx, cy))
    sd.text((cx + 225, cy + 466), fn.replace("orbit_final_", "").replace(".png", ""), font=lf,
            fill=(200, 200, 210), anchor="mm")
sheet.save(os.path.join(OUT, "orbit_final_sheet.png"))
print("ok")
