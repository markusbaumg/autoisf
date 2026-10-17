"""AAPS Glass: state-matrix renderer (design system v2).

Reference renderer for glass_spec.md (designsystem). Implements the family FaceState rules of states.md
in Python, then draws the Glass face for every catalogue state. Output: glass_states_*.png.

Run:  uv run --with pillow python3 glass_states.py
"""
import copy, math, os, random
from PIL import Image, ImageDraw, ImageFilter, ImageFont, ImageChops

OUT = os.path.dirname(os.path.abspath(__file__))
AVN = "/System/Library/Fonts/Avenir Next.ttc"
UNI = "/System/Library/Fonts/Supplemental/Arial Unicode.ttf"
SS = 2  # supersampling


def _idx():
    n = {}
    for i in range(12):
        try:
            n[ImageFont.truetype(AVN, 20, index=i).getname()[1]] = i
        except Exception:
            break
    return n


N = _idx()
BOLD = N.get("Bold", 0); DEMI = N.get("Demi Bold", 2); MED = N.get("Medium", 5)
REG = N.get("Regular", 7); ULT = N.get("Ultra Light", 8)
_fc = {}


def font(px, idx):
    k = (int(px), idx)
    if k not in _fc:
        _fc[k] = ImageFont.truetype(AVN, max(1, int(px)), index=idx)
    return _fc[k]


def ufont(px):
    k = (int(px), "u")
    if k not in _fc:
        _fc[k] = ImageFont.truetype(UNI, max(1, int(px)))
    return _fc[k]


def hx(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def A(c, a):
    return c[:3] + (int(a),)


def lerp(a, b, t): return a + (b - a) * t


def mix(c1, c2, t): return tuple(int(lerp(a, b, t)) for a, b in zip(c1, c2))


# ---------------------------------------------------------------- family tokens (states.md §3)
T = dict(
    r_in=hx("#3DDC97"), r_high=hx("#FFB547"), r_low=hx("#FF5A6E"), r_stale=hx("#8A93A8"),
    warn=hx("#FFB547"), alert=hx("#FF5A6E"), ins=hx("#5AC8FA"), tbr=hx("#3DDBD9"), tt=hx("#B388FF"),
    ball=hx("#7888FF"), ball_arm=hx("#5B63F5"), loop_ok=hx("#40D68C"),
    txt1=hx("#EEF1F7"), txt2=hx("#9AA6C4"), txt3=hx("#6F7A99"), lbl=hx("#8E99B8"), delta=hx("#C8D2E6"),
    a1=hx("#E0E0E0"), a2=hx("#9A9A9A"), a3=hx("#6E6E6E"), aL=hx("#3C3C3C"), white=hx("#FFFFFF"),
    pred=hx("#AA82FF"),
)
RANGE_COL = dict(IN=T["r_in"], HIGH=T["r_high"], VERY_HIGH=T["r_high"], LOW=T["r_low"], URGENT_LOW=T["r_low"],
                 STALE=T["r_stale"])
BG_TOP, BG_MID, BG_BOT = hx("#141A33"), hx("#0B0F22"), hx("#04050B")

# ---------------------------------------------------------------- strings (de / en / fr)
STR = {
    "de": dict(waiting="Warte auf AAPS", lost="Telefon getrennt · {}", nodata="Keine Daten · {}",
               noprof="Kein Profil", loopoff="Loop aus", loopage="Loop {}", open="offen", lgs="LGS",
               res="Reservoir {}", tel="Tel {} %", cob="KH", low="TIEF", high="HOCH", hours="{} Std", days="{} T",
               week="KW {}", aus="aus", nobg="Kein BG", sec_low="{} TIEF {}", sec_stale="{} · Keine Daten {}",
               again="nochmal", again_s="2×", unit="U"),
    "en": dict(waiting="Waiting for AAPS", lost="Phone disconnected · {}", nodata="No data · {}",
               noprof="No profile", loopoff="Loop off", loopage="Loop {}", open="open", lgs="LGS",
               res="Reservoir {}", tel="Phone {}%", cob="COB", low="LOW", high="HIGH", hours="{} h", days="{} d",
               week="W{}", aus="off", nobg="No BG", sec_low="{} LOW {}", sec_stale="{} · No data {}",
               again="again", again_s="2×", unit="U"),
    "fr": dict(waiting="En attente d'AAPS", lost="Téléphone déconnecté · {}", nodata="Aucune donnée · {}",
               noprof="Aucun profil", loopoff="Boucle arrêtée", loopage="Boucle {}", open="ouverte", lgs="LGS",
               res="Réservoir {}", tel="Tél {} %", cob="GA", low="BAS", high="HAUT", hours="{} h", days="{} j",
               week="S{}", aus="arrêt", nobg="Pas de glycémie", sec_low="{} BAS {}",
               sec_stale="{} · Aucune donnée {}", again="encore", again_s="2×", unit="U"),
}


def unit_space(s, L):
    """de/fr typography: a (narrow no-break) space between number and unit. Decimal separator untouched."""
    import re
    if L == "en" or s is None:
        return s
    return re.sub(r"(\d|\))(U/h|U|%|g)(?![A-Za-z])", r"\1 \2", s)


def fmt_age(m, L):
    if m is None:
        return "--"
    if m >= 48 * 60:
        return STR[L]["days"].format(m // 1440)
    if m >= 60:
        return STR[L]["hours"].format(m // 60)
    return f"{m}′"


def display(s):
    if s is None:
        return "--"
    t = s.strip()
    return "--" if t in ("", "IOB", "-.--", "--g", "-.--U/h", "no status", "??", "--") else t


# ---------------------------------------------------------------- FaceState (states.md §1, §4.2)
def face_state(st):
    f = {}
    age = st["age"]
    f["bgAge"] = "NONE" if age is None else "FRESH" if age < 6 else "AGING" if age < 12 else "STALE" if age < 30 else "VERY"
    stale = f["bgAge"] in ("STALE", "VERY", "NONE")
    dm = st.get("delta_mgdl")
    tr = st["trend"]
    f["fastFall"] = tr in ("DOWN2", "DOWN3") or (dm is not None and dm <= -10)
    sgv, lo, hi = st["sgv"], st["low"] or 70, st["high"] or 180
    if stale:
        rng = "STALE"
    elif sgv < 54 or (sgv < lo and f["fastFall"]):
        rng = "URGENT_LOW"
    elif sgv < lo:
        rng = "LOW"
    elif sgv > 250:
        rng = "VERY_HIGH"
    elif sgv > hi:
        rng = "HIGH"
    else:
        rng = "IN"
    f["range"] = rng
    f["stale"] = stale and f["bgAge"] != "NONE"
    f["sentinel"] = "LOW" if (not stale and sgv <= 39) else "HIGH" if (not stale and sgv >= 400) else None
    la = st["loop_age"]
    if st["flavour"] == "PUMPCONTROL" and la is None:
        lh = "NOAPS"
    elif la is None:
        lh = "NEVER"
    elif st["no_profile"]:
        lh = "NEUTRAL"
    elif st["loop_run"] == "OFF":
        lh = "OFF"
    elif st["loop_mode"] == "OPEN":
        lh = "OPEN"
    else:
        lh = "OK" if la <= 5 else "WARN" if la < 15 else "BAD"
    f["loop"] = lh
    L = st["lang"]
    S_ = STR[L]
    da = st["data_age"]
    reach = st["phone_reach"]
    ws = []
    if st["first_start"]:
        ws.append((1, S_["waiting"], T["txt2"]))
    if not st["first_start"] and not reach and da >= 12:
        ws.append((2, S_["lost"].format(fmt_age(da, L)), T["alert"]))
    if not st["first_start"] and reach and (da >= 12 or f["stale"]):
        ws.append((3, S_["nodata"].format(fmt_age(max(da, age or 0), L)), T["alert"]))
    if not st["first_start"] and f["bgAge"] == "NONE":
        ws.append((3, S_["nobg"], T["warn"]))  # age.none: a BG-less face always carries a reason
    # W3b: a secondary patient's urgent low / very stale escalates to the face-level slot (states.md v2 §4.9)
    for o in st.get("others", []):
        so = sec_state(o)
        nm = o["name"] or "P?"
        nm = nm if len(nm) <= 10 else nm[:10] + "…"
        if so["range"] == "URGENT_LOW":
            ws.append((3.5, S_["sec_low"].format(nm, o["bg"]), T["alert"]))
        elif so["bgAge"] == "VERY":
            ws.append((3.5, S_["sec_stale"].format(nm, fmt_age(o["age"], L)), T["alert"]))
    if st["no_profile"]:
        ws.append((4, S_["noprof"], T["warn"]))
    if lh == "OFF":
        ws.append((5, S_["loopoff"], T["warn"]))
    if lh == "BAD":  # amber 15-29' (openApsStatus = last TBR, may false-alarm), red from 30'
        ws.append((6, S_["loopage"].format(fmt_age(la, L)), T["alert"] if la >= 30 else T["warn"]))
    if st["res_level"] == 2 and display(st["res"]) != "--":
        ws.append((7, S_["res"].format(unit_space(st["res"], L)), T["alert"]))
    if st["batt_level"] == 0 and st["batt"] not in ("0", ""):
        b = int(st["batt"])
        ws.append((8, S_["tel"].format(b), T["alert"] if b < 10 else T["warn"]))
    if st["res_level"] == 1 and display(st["res"]) != "--":
        ws.append((9, S_["res"].format(unit_space(st["res"], L)), T["warn"]))
    if not reach and da < 12:
        ws.append((10, None, T["txt2"]))
    fresh_low = f["bgAge"] in ("FRESH", "AGING") and rng in ("LOW", "URGENT_LOW")
    f["fresh_low"] = fresh_low
    if fresh_low:  # W7-W9 suppressed; W1-W6 always show; the W10 glyph is exempt
        ws = [w for w in ws if w[0] <= 6 or w[0] == 10]
    if st.get("switched"):  # dataset-0-only warnings live in dataset 0's block while switched
        ws = [w for w in ws if w[0] <= 3.5 or w[0] == 10]
    ws.sort(key=lambda w: w[0])
    f["warnings"] = ws
    f["owner"] = ws[0] if ws and ws[0][0] != 10 else None
    # bolus ball verdict (states.md v2 §4.6): HIDDEN beats DISABLED beats IDLE
    want = st.get("ball", "off")
    reason = None
    if want in ("off", None):
        eff = "off"
    elif want == "hidden" or st["first_start"] or st.get("switched"):
        eff = "hidden"
    else:
        if not reach:
            reason = "NO_PHONE"
        elif f["bgAge"] in ("STALE", "VERY", "NONE"):
            reason = "STALE_DATA"
        elif rng == "URGENT_LOW" or (rng == "LOW" and (tr in ("DOWN45", "DOWN", "DOWN2", "DOWN3") or (dm is not None and dm < 0))):
            reason = "LOW_BG"
        elif st["no_profile"]:
            reason = "NO_PROFILE"
        elif lh == "OFF":
            reason = "LOOP_OFF"
        elif st.get("max_bolus"):
            reason = "MAX_BOLUS"
        eff = "disabled" if reason else want
        if reason and want in ("delivering", "delivered", "sent"):
            eff = want  # a request already in flight keeps its own state
    f["ball"], f["ball_reason"] = eff, reason
    return f


def sec_state(o):
    age = o["age"]
    bga = "NONE" if age is None else "FRESH" if age < 6 else "AGING" if age < 12 else "STALE" if age < 30 else "VERY"
    sgv, lo = o["sgv"], o.get("low", 70)
    fast = o["trend"] in ("DOWN2", "DOWN3")
    if bga in ("STALE", "VERY", "NONE"):
        r = "STALE"
    elif sgv < 54 or (sgv < lo and fast):
        r = "URGENT_LOW"
    elif sgv < lo:
        r = "LOW"
    elif sgv > 250:
        r = "VERY_HIGH"
    elif sgv > o.get("high", 180):
        r = "HIGH"
    else:
        r = "IN"
    return dict(range=r, bgAge=bga)


# ---------------------------------------------------------------- sample data
def mmol(v):
    return f"{v / 18.0:.1f}".replace(".", ",")


def series(start, end, k, last, seed, n=36):
    random.seed(seed)
    v = [start + (end - start) / (1 + math.exp(-(i / n - 0.55) * k)) + random.uniform(-2, 2) for i in range(n)]
    v[-1] = last
    return v


PREFS_DEFAULT = dict(date=True, week=False, seconds=False, delta=True, avg=False, ago=True, direction=True, iob=True,
                     detailed_iob=False, cob=True, basal=True, tt=True, bgi=False, uploader=False, rig=False,
                     reservoir=False, predictions=True)

BASE = dict(
    units="mmol", sgv=169, bg="9,4", trend="FLAT", delta="+0,1", delta_mgdl=2, age=1, low=70, high=180,
    hist=series(126, 172, 9, 169, 4), pred=[169 - 0.9 * k - 0.07 * k * k for k in range(1, 13)], gaps=False,
    iob="2,53", iob_detail="(2,10|0,43)", cob="0g", basal="0,85 U/h", tt="--", tt_level=0,
    loop_age=2, loop_run="RUNNING", loop_mode="CLOSED", flavour="FULL", phone_reach=True, data_age=1,
    first_start=False, no_profile=False, res="120U", res_level=0, batt="64", batt_level=1, rig="", bgi="-1,2",
    time="14:07", date="Fr 17. Okt", lang="de", h12=False, ampm="", sec="07", week="42",
    patient="", others=[], switched=False,
    ball="idle", amount="+0,5", progress=0.6, caption=None, max_bolus=False,
)


def S(**kw):
    st = copy.deepcopy(BASE)
    st["prefs"] = dict(PREFS_DEFAULT)
    st["prefs"].update(kw.pop("prefs", {}))
    st.update(kw)
    return st


def other(name, sgv, trend="FLAT", age=2, loop_age=3, units="mmol", stale=False, iob=None, idx=1):
    return dict(name=name, sgv=sgv, bg=(mmol(sgv) if units == "mmol" else str(sgv)), trend=trend, age=age,
                loop_age=loop_age, low=70, high=180, iob=iob, idx=idx)


# ---------------------------------------------------------------- drawing primitives
class Ctx:
    def __init__(self, scale, ss, amb, lowbit, burnin):
        self.s, self.ss, self.K = scale, ss, scale * ss
        self.amb, self.lowbit, self.burnin = amb, lowbit, burnin

    def P(self, *v):
        return [x * self.K for x in v]

    def f(self, px, idx):
        return font(px * self.K, idx)


def D(im):
    d = ImageDraw.Draw(im)
    if im.mode == "RGBA":
        im.load()
        d.draw = Image.core.draw(im.im, 1)  # blend RGBA fills (PIL overwrites otherwise)
    return d


def glow(base, fn, radius_px):
    layer = Image.new("RGBA", base.size, (0, 0, 0, 0))
    fn(ImageDraw.Draw(layer))
    bb = layer.getbbox()
    if not bb:
        return base
    m = int(radius_px * 3)
    x0, y0 = max(0, bb[0] - m), max(0, bb[1] - m)
    x1, y1 = min(base.width, bb[2] + m), min(base.height, bb[3] + m)
    crop = layer.crop((x0, y0, x1, y1)).filter(ImageFilter.GaussianBlur(radius_px))
    full = Image.new("RGBA", base.size, (0, 0, 0, 0))
    full.paste(crop, (x0, y0))
    return Image.alpha_composite(base, full)


def glass(c, base, box, r, alpha=22, edge=60):
    x0, y0, x1, y1 = [int(v) for v in c.P(*box)]
    R = r * c.K
    pad = int(30 * c.K)
    cx0, cy0, cx1, cy1 = max(0, x0 - pad), max(0, y0 - pad), min(base.width, x1 + pad), min(base.height, y1 + pad)
    blurred = base.crop((cx0, cy0, cx1, cy1)).filter(ImageFilter.GaussianBlur(14 * c.K))
    mask = Image.new("L", (cx1 - cx0, cy1 - cy0), 0)
    ImageDraw.Draw(mask).rounded_rectangle([x0 - cx0, y0 - cy0, x1 - cx0, y1 - cy0], radius=R, fill=255)
    region = base.crop((cx0, cy0, cx1, cy1))
    region = Image.composite(blurred, region, mask)
    grad = Image.new("RGBA", region.size, (0, 0, 0, 0))
    gd = ImageDraw.Draw(grad)
    for y in range(y0, y1):
        t = (y - y0) / max(1, y1 - y0)
        gd.line([(x0 - cx0, y - cy0), (x1 - cx0, y - cy0)], fill=(255, 255, 255, int(lerp(alpha * 1.6, alpha * 0.5, t))))
    grad.putalpha(ImageChops.multiply(grad.getchannel("A"), mask))
    region = Image.alpha_composite(region, grad)
    base = base.copy()
    base.paste(region, (cx0, cy0))
    d = D(base)
    d.rounded_rectangle([x0, y0, x1, y1], radius=R, outline=(255, 255, 255, edge), width=max(1, int(c.K)))
    d.line([x0 + R, y0 + c.K, x1 - R, y0 + c.K], fill=(255, 255, 255, min(255, edge + 40)), width=max(1, int(c.K)))
    return base


def rr_points(box, r, step):
    """points along a rounded-rect perimeter (clockwise from top-left straight)."""
    x0, y0, x1, y1 = box
    pts = []
    segs = [((x0 + r, y0), (x1 - r, y0)), ("arc", (x1 - r, y0 + r, -90, 0)), ((x1, y0 + r), (x1, y1 - r)),
            ("arc", (x1 - r, y1 - r, 0, 90)), ((x1 - r, y1), (x0 + r, y1)), ("arc", (x0 + r, y1 - r, 90, 180)),
            ((x0, y1 - r), (x0, y0 + r)), ("arc", (x0 + r, y0 + r, 180, 270))]
    for sg in segs:
        if sg[0] == "arc":
            cx, cy, a0, a1 = sg[1]
            n = max(2, int(r * math.radians(a1 - a0) / step))
            for i in range(n):
                a = math.radians(a0 + (a1 - a0) * i / n)
                pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
        else:
            (ax, ay), (bx, by) = sg
            n = max(1, int(math.hypot(bx - ax, by - ay) / step))
            for i in range(n):
                pts.append((lerp(ax, bx, i / n), lerp(ay, by, i / n)))
    return pts


def dashed_rr(d, box, r, col, w, on, off):
    pts = rr_points(box, r, 1.0)
    period = on + off
    run = []
    for i, p in enumerate(pts):
        if (i % int(period)) < on:
            run.append(p)
        else:
            if len(run) > 1:
                d.line(run, fill=col, width=w)
            run = []
    if len(run) > 1:
        d.line(run, fill=col, width=w)


# text with per-character fallback (Avenir lacks U+2032 prime)
FALLBACK = set("′⌀")


def _segs(s):
    out = []
    for ch in s:
        fb = ch in FALLBACK
        if out and out[-1][1] == fb:
            out[-1][0] += ch
        else:
            out.append([ch, fb])
    return out


def tw(d, s, f):
    return sum(d.textlength(t, font=(ufont(f.size) if fb else f)) for t, fb in _segs(s))


def text(d, xy, s, f, fill, anchor="mm", stroke=0, stroke_fill=None):
    x, y = xy
    w = tw(d, s, f)
    h = anchor[0]
    x = x - w / 2 if h == "m" else x - w if h == "r" else x
    va = anchor[1]
    for t, fb in _segs(s):
        ff = ufont(f.size) if fb else f
        if stroke:
            d.text((x, y), t, font=ff, fill=fill, anchor="l" + va, stroke_width=stroke, stroke_fill=stroke_fill)
        else:
            d.text((x, y), t, font=ff, fill=fill, anchor="l" + va)
        x += d.textlength(t, font=ff)
    return w


def runs_line(d, cx, y, runs, f):
    tot = sum(tw(d, s, f) for s, _ in runs)
    x = cx - tot / 2
    for s, col in runs:
        x += text(d, (x, y), s, f, col, anchor="lm")
    return tot


ANG = dict(FLAT=0, UP45=45, UP=90, DOWN45=-45, DOWN=-90, UP2=90, DOWN2=-90, UP3=90, DOWN3=-90)


def arrow(d, c, cx, cy, trend, col, scale=1.0):
    """face-drawn trend glyph (no font arrows). cx,cy in device px."""
    if trend not in ANG:
        return
    K = c.K * scale
    a = math.radians(ANG[trend])
    ux, uy = math.cos(a), -math.sin(a)
    L = 10 * K
    w = (3.4 if trend in ("UP3", "DOWN3") else 2.6) * K
    if c.burnin or c.lowbit:
        w = min(w, 2 * c.K)
    tail = (cx - ux * L, cy - uy * L)
    tip = (cx + ux * L, cy + uy * L)
    wi = max(1, int(w))

    def cap(p):
        d.ellipse([p[0] - w / 2, p[1] - w / 2, p[0] + w / 2, p[1] + w / 2], fill=col)

    d.line([tail, tip], fill=col, width=wi)
    cap(tail)
    heads = [0] if trend not in ("UP2", "DOWN2", "UP3", "DOWN3") else [0, 7 * K]
    for back in heads:
        hp = (tip[0] - ux * back, tip[1] - uy * back)
        for s in (1, -1):
            ang = a + math.pi - s * math.radians(42)
            q = (hp[0] + math.cos(ang) * 8 * K, hp[1] - math.sin(ang) * 8 * K)
            d.line([hp, q], fill=col, width=wi)
            cap(q)
        cap(hp)


def phone_off_glyph(d, c, cx, cy, col):
    K = c.K
    d.rounded_rectangle([cx - 4 * K, cy - 7 * K, cx + 4 * K, cy + 7 * K], radius=1.6 * K, outline=col, width=max(1, int(1.3 * K)))
    d.line([cx - 7 * K, cy + 8 * K, cx + 7 * K, cy - 8 * K], fill=col, width=max(1, int(1.3 * K)))


def drop_glyph(d, c, cx, cy, col, hollow=False):
    """insulin drop, 8x11 px: identifies the IOB pill without its colour (rule 2)."""
    K = c.K
    r = 3.6 * K
    pts = [(cx, cy - 6.5 * K)]
    for a in range(-30, 211, 10):
        t = math.radians(a)
        pts.append((cx + r * math.cos(t), cy + 1.6 * K + r * math.sin(t)))
    if hollow:
        d.line(pts + [pts[0]], fill=col, width=max(1, int(1.2 * K)))
    else:
        d.polygon(pts, fill=col)


# ---------------------------------------------------------------- the face
REDS = []  # red-budget audit: names of elements drawn in alert / range.low during the last render


def red(name):
    if name not in REDS:
        REDS.append(name)


def layout(st, f, flat=False):
    multi = len(st["others"]) > 0
    Lo = dict(TIME_Y=58, DATE_Y=92, HERO=(92, 110, 358, 240), HERO_R=30, BGC=(184, 176), CHIP=(312, 152, 22),
              META=(312, 200), GRAPH=(84, 256, 366, 312), PILL=(324, 366), PILL_R=21, FOOT_Y=390, FOOT_PX=18,
              CHIPS=None, BALL=(46, 176, 30), PILLS_X=((96, 220), (230, 354)))
    p = st["prefs"]
    if not p["date"]:
        Lo["TIME_Y"] = 74
    if multi:
        Lo.update(GRAPH=(84, 252, 366, 290), PILL=(300, 336), PILL_R=18, FOOT_Y=354, FOOT_PX=16, CHIPS=(368, 406))
        if flat:  # flat tyre: everything up 16 so the blocks end >= 6 px above the chin (design y <= 390)
            Lo.update(GRAPH=(84, 246, 366, 278), PILL=(286, 320), PILL_R=17, FOOT_Y=338, CHIPS=(352, 390))
    if st.get("switched"):
        # hero = dataset 1. No graph for it in the contract -> dataset 0's block moves up into the graph area,
        # dataset-0-only pills are removed (no IOB/loop of patient A next to patient B's BG).
        Lo.update(GRAPH=None, PILL=None, CHIPS=(262, 312), FOOT_Y=390, FOOT_PX=18)
    return Lo


def footer_runs(st, f, c, L):
    """returns list of (text, colour) for the Glass footer = status slot."""
    S_ = STR[L]
    amb = c.amb
    p = st["prefs"]
    own = f["owner"]
    if own and own[0] <= 6:
        return [(own[1], (T["a1"] if amb else own[2]))], True
    runs = []  # (text, col, keep_prio)
    if st.get("switched"):
        return runs, False  # treatments/TT/basal are dataset 0 only -> nothing next to patient B's BG
    if own and own[0] in (7, 8, 9) and not amb:
        runs.append((own[1], own[2], 100))
    if p["tt"] and st["tt_level"] == 2 and display(st["tt"]) != "--":
        runs.append((f"TT {st['tt']}", T["a2"] if amb else T["tt"], 90))
    if not amb:
        if p["cob"]:
            dc = display(st["cob"])
            if dc == "--":
                runs.append((f"{S_['cob']} --", T["txt3"], 40))
            elif not dc.startswith("0g"):
                runs.append((f"{S_['cob']} {unit_space(dc, L)}", T["txt2"], 40))
        if p["basal"] and not st.get("_flat_multi"):  # flat tyre + blocks: basal is the first run dropped
            b = display(st["basal"])
            zero = b.startswith("0%") or b.startswith("0,00") or b.startswith("0.00")
            if b == "--":
                col = T["txt3"]
            elif zero:
                # zero basal during a fresh low is the loop doing its job: neutral, not a second warning colour
                col = T["txt2"] if f["fresh_low"] else T["warn"]
            elif b.endswith("%"):
                col = T["tbr"]
            else:
                col = T["txt2"]
            runs.append((unit_space(b, L) if b != "--" else b, col, 50))
        if p["detailed_iob"]:
            runs.append((st["iob_detail"], T["txt2"], 15))
        if p["bgi"] and display(st["bgi"]) != "--":
            runs.append((f"BGI {st['bgi']}", T["txt2"], 10))
        if p["reservoir"] and st["res_level"] == 0 and display(st["res"]) != "--":
            runs.append((f"Reservoir {unit_space(st['res'], L)}" if L == "de" else f"Res {unit_space(st['res'], L)}",
                         T["txt2"], 20))
        if p["uploader"] and st["batt_level"] == 1 and st["batt"] not in ("0", ""):
            runs.append((S_["tel"].format(st["batt"]), T["txt2"], 25))
        if p["rig"] and st["rig"]:
            runs.append((f"Rig {unit_space(st['rig'], L)}", T["txt2"], 5))
    return runs, False


def render(st, mode="interactive", size=450, shape="round", visible_h=None, shift=(0, 0), rect=None):
    """mode: interactive | ambient | lowbit | burnin | lowbit_burnin.  Returns RGB device image."""
    amb = mode != "interactive"
    lowbit = "lowbit" in mode
    burnin = "burnin" in mode
    diam = size if shape == "round" else min(rect) if rect else size
    sc = diam / 450.0
    ss = 1 if lowbit else SS
    c = Ctx(sc, ss, amb, lowbit, burnin)
    W = int(round(450 * c.K))
    K = c.K
    REDS.clear()
    flat = visible_h is not None
    if flat and st["others"]:
        st = dict(st, _flat_multi=True)
    f = face_state(st)
    L = st["lang"]
    S_ = STR[L]
    p = st["prefs"]
    Lo = layout(st, f, flat)
    FLOOR = 11.0 / sc  # 11 px physical text floor, in design px

    def fl(px):
        return max(px, FLOOR)
    chin = None if visible_h is None else visible_h / sc  # design units
    rk = f["range"]
    rng = RANGE_COL[rk]
    emph = rk in ("VERY_HIGH", "URGENT_LOW")
    stale = rk == "STALE"
    small = sc < 0.95  # 384-416 class: font floor rules
    img = Image.new("RGBA", (W, W), (0, 0, 0, 255))
    d = D(img)
    if lowbit:
        d.fontmode = "1"

    def col(i, a):  # interactive colour i, ambient colour a
        if lowbit and amb:
            return T["white"] if a not in (None, "drop") else None
        return a if amb else i

    # ---- background + aurora
    if not amb:
        for y in range(W):
            t = y / W
            cc = mix(BG_TOP, BG_MID, t / 0.55) if t < 0.55 else mix(BG_MID, BG_BOT, (t - 0.55) / 0.45)
            d.line([(0, y), (W, y)], fill=cc)
        boost = 1.2 if emph else 1.0
        img = glow(img, lambda g: g.ellipse(c.P(130, 100, 320, 250), fill=A(rng, min(255, 150 * boost))), 40 * K)
        img = glow(img, lambda g: g.ellipse(c.P(150, 120, 260, 220), fill=A(rng, min(255, 120 * boost))), 26 * K)
        img = glow(img, lambda g: g.ellipse(c.P(240, 220, 410, 340), fill=hx("#5A46C8", 85)), 44 * K)
        img = glow(img, lambda g: g.ellipse(c.P(40, 230, 180, 330), fill=hx("#2A5AC8", 60)), 44 * K)
        d = D(img)
        if shape == "round":
            d.ellipse([3 * K, 3 * K, W - 3 * K, W - 3 * K], outline=(255, 255, 255, 22), width=max(1, int(K)))

    # ---- time + date
    d = D(img)
    if lowbit:
        d.fontmode = "1"
    tf = c.f(42, ULT if amb else REG)
    tcol = col(T["txt1"], T["a1"])
    tm = st["time"]
    extra = []
    if st["h12"]:
        extra.append((" " + st["ampm"], c.f(17, MED), col(T["txt2"], T["a2"])))
    if p["seconds"] and not amb:
        extra.append((":" + st["sec"], c.f(17, MED), T["txt2"]))
    wt = tw(d, tm, tf) + sum(tw(d, s, ff) for s, ff, _ in extra)
    x = 225 * K - wt / 2
    ty = Lo["TIME_Y"] * K
    x += text(d, (x, ty), tm, tf, tcol, anchor="lm")
    for s, ff, cc in extra:
        x += text(d, (x, ty + 7 * K), s, ff, cc, anchor="lm")
    if p["date"]:
        ds = st["date"] + (f" · {S_['week'].format(st['week'])}" if p["week"] else "")
        text(d, (225 * K, Lo["DATE_Y"] * K), ds, c.f(18, MED), col(T["txt2"], T["a2"]))

    # ---- hero card
    HERO = Lo["HERO"]
    if amb:
        x0, y0, x1, y1 = c.P(*HERO)
        R = Lo["HERO_R"] * K
        lw = max(1, int(K))
        lc = T["white"] if lowbit else None
        if rk in ("HIGH", "VERY_HIGH"):
            dashed_rr(d, (x0, y0, x1, y1), R, lc or (T["a1"] if rk == "VERY_HIGH" else T["a2"]),
                      lw * (2 if rk == "VERY_HIGH" else 1), 6 * K, 5 * K)
        elif rk in ("LOW", "URGENT_LOW"):
            cc = lc or (T["a1"] if rk == "URGENT_LOW" else T["a2"])
            d.rounded_rectangle([x0, y0, x1, y1], radius=R, outline=cc, width=lw)
            g = 5 * K
            d.rounded_rectangle([x0 + g, y0 + g, x1 - g, y1 - g], radius=R - g, outline=cc, width=lw * (2 if rk == "URGENT_LOW" else 1))
        elif not lowbit:
            d.rounded_rectangle([x0, y0, x1, y1], radius=R, outline=T["aL"], width=lw)
    else:
        img = glass(c, img, HERO, Lo["HERO_R"])
        if rk == "URGENT_LOW":  # the urgent step is a shape too: 2 px red card edge (part of the BG accent group)
            D(img).rounded_rectangle(c.P(*HERO), radius=Lo["HERO_R"] * K, outline=A(T["r_low"], 230), width=max(1, int(2 * K)))
    d = D(img)
    if lowbit:
        d.fontmode = "1"

    show_chip = p["direction"]
    # meta line content
    meta = []
    if not st["first_start"]:
        if p["delta"] and not stale and f["bgAge"] != "NONE":
            ds_ = display(st["delta"])
            meta.append((ds_, col(T["txt3"] if ds_ == "--" else T["delta"], T["a2"])))
        age = st["age"]
        show_age = p["ago"] or f["bgAge"] not in ("FRESH",)
        if show_age and f["bgAge"] != "NONE":
            ac = T["alert"] if f["bgAge"] in ("STALE", "VERY") else T["warn"] if f["bgAge"] == "AGING" else T["txt2"]
            if f["owner"] and f["owner"][0] in (2, 3):
                ac = T["txt2"]  # the slot already says "Keine Daten · 17′": strike + grey digits carry stale here
            if ac == T["alert"] and not amb:
                red("meta age")
            aa = T["a1"] if f["bgAge"] != "FRESH" else T["a2"]
            if meta:
                meta.append((" · ", col(T["txt3"], T["a3"])))
            meta.append((fmt_age(age, L), col(ac, aa)))
    if show_chip:
        bgc = Lo["BGC"]
        chip = Lo["CHIP"] if meta else (312, 175, 22)
        metac = Lo["META"]
    else:
        bgc = (225, 166) if meta else (225, 175)
        chip = None
        metac = (225, 220)
    # BG string
    very_old = f["bgAge"] == "VERY" and (st["age"] or 0) >= 60
    if st["first_start"] or f["bgAge"] == "NONE" or very_old:
        bgs = "---"
    else:
        bgs = st["bg"] + ("+" if f["sentinel"] == "HIGH" else "")
    maxw = (170 if show_chip else 230) * K
    px = 84
    wgt = DEMI
    if amb:
        wgt = DEMI if rk == "URGENT_LOW" else REG
    while px > 56 and tw(d, bgs, c.f(px, wgt)) > maxw:
        px -= 2
    bgf = c.f(px, wgt)
    bx, by_ = bgc[0] * K, bgc[1] * K
    is_low = rk in ("LOW", "URGENT_LOW") and bgs != "---"
    has_word = is_low or (f["sentinel"] == "HIGH" and bgs != "---")
    if has_word:
        by_ -= 7 * K  # digits lift 7 px to make room for the hero word (TIEF / HOCH) under them
    # hero patient name: header chip at the top of the card (multi or switched), 14 px txt1 - not in the date row
    if st["patient"] and (st["others"] or st["switched"]):
        nm = st["patient"] if len(st["patient"]) <= 10 else st["patient"][:10] + "…"
        nf = c.f(fl(14), DEMI)
        nw = tw(d, nm, nf)
        ncx, ncy = bgc[0] * K, 128 * K
        if not amb:
            d.rounded_rectangle([ncx - nw / 2 - 9 * K, ncy - 11 * K, ncx + nw / 2 + 9 * K, ncy + 11 * K], radius=11 * K,
                                fill=(255, 255, 255, 34), outline=(255, 255, 255, 70), width=max(1, int(K)))
        text(d, (ncx, ncy), nm, nf, col(T["txt1"], T["a1"]))
        if bgs != "---":
            by_ += 6 * K  # digits drop 6 px under the chip
    glow_ok = not amb and not stale and bgs != "---"
    if glow_ok:
        img = glow(img, lambda g: g.text((bx, by_), bgs, font=bgf, fill=A(rng, 90 if is_low else 150), anchor="mm"), 6 * K)
        d = D(img)
    if stale or bgs == "---":
        bgcol = col(T["txt2"], T["a1"])
    elif is_low:
        bgcol = col(T["r_low"], T["a1"])  # a low is never drawn in white digits (family v2, bg.low)
    else:
        bgcol = col(T["white"], T["a1"])
    if is_low and not amb:
        red("BG group")
    if burnin:
        text(d, (bx, by_), bgs, bgf, (0, 0, 0), stroke=max(1, int(1.5 * K)), stroke_fill=bgcol)
    else:
        text(d, (bx, by_), bgs, bgf, bgcol)
    if f["stale"] and bgs != "---":
        bw = tw(d, bgs, bgf)
        d.line([bx - bw / 2 - 4 * K, by_ + 2 * K, bx + bw / 2 + 4 * K, by_ + 2 * K], fill=bgcol,
               width=max(1, int((2 if amb else 3) * K)))
    # hero word: TIEF (low: red word; urgent low / sentinel: filled red badge), HOCH (sentinel high)
    wy = (bgc[1] + 49) * K
    if is_low:
        word = S_["low"]
        wf = c.f(fl(14), BOLD)
        ww = tw(d, word, wf)
        urgent = rk == "URGENT_LOW"
        if urgent:
            bxw = ww / 2 + 10 * K
            box = [bx - bxw, wy - 11 * K, bx + bxw, wy + 11 * K]
            if not amb:
                d.rounded_rectangle(box, radius=11 * K, fill=T["r_low"])
                text(d, (bx, wy), word, wf, T["white"])
            elif burnin:
                d.rounded_rectangle(box, radius=11 * K, outline=col(None, T["a1"]), width=max(1, int(1.5 * K)))
                text(d, (bx, wy), word, wf, col(None, T["a1"]))
            else:
                d.rounded_rectangle(box, radius=11 * K, fill=col(None, T["a1"]))
                text(d, (bx, wy), word, wf, (0, 0, 0))
        else:
            text(d, (bx, wy), word, wf, col(T["r_low"], T["a1"]))
    elif f["sentinel"] == "HIGH" and bgs != "---":
        text(d, (bx, wy), S_["high"], c.f(fl(14), BOLD), col(T["r_high"], T["a1"]))
    # chip
    if chip:
        cx, cy, cr = [v * K for v in chip]
        empty = stale or st["first_start"] or st["trend"] not in ANG or f["bgAge"] == "NONE"
        if amb:
            d.ellipse([cx - cr, cy - cr, cx + cr, cy + cr], outline=col(None, T["a3"]), width=max(1, int(K)))
        else:
            d.ellipse([cx - cr, cy - cr, cx + cr, cy + cr], fill=A(rng, 40), outline=A(rng, 210), width=max(1, int(2 * K)))
        if not empty:
            arrow(d, c, cx, cy, st["trend"], col(rng, T["a1"]))
    # meta line
    if meta:
        mf = c.f(18, MED)
        runs_line(d, metac[0] * K, metac[1] * K, meta, mf)
    if f["owner"] and f["owner"][0] == 10 or any(w[0] == 10 for w in f["warnings"]):
        if not amb:
            phone_off_glyph(d, c, (chip[0] if chip else 225) * K, 224 * K, T["txt2"])

    # ---- sparkline
    show_graph = Lo["GRAPH"] is not None
    GRAPH = Lo["GRAPH"] or (84, 256, 366, 312)
    GX0, GY0, GX1, GY1 = GRAPH
    gx0, gy0, gx1, gy1 = c.P(*GRAPH)
    lo_mg, hi_mg = 45, 252

    def gy(v):
        v = max(lo_mg, min(hi_mg, v))
        return (GY1 - (v - lo_mg) / (hi_mg - lo_mg) * (GY1 - GY0)) * K

    nh, ntot = 36, 48

    def gxi(i):
        return (GX0 + i / (ntot - 1) * (GX1 - GX0)) * K

    if show_graph:
        if not amb:
            d.rounded_rectangle([gx0, gy(st["high"]), gx1, gy(st["low"])], radius=8 * K, fill=A(rng, 24))
        if not lowbit:
            for lv in (st["low"], st["high"]):
                yy = gy(lv)
                xx = gx0
                while xx < gx1:
                    d.line([xx, yy, xx + 3 * K, yy], fill=T["aL"] if amb else (255, 255, 255, 46), width=max(1, int(K)))
                    xx += 8 * K
            nx = gxi(nh - 1)
            yy = gy0
            while yy < gy1:
                d.line([nx, yy, nx, yy + 2 * K], fill=T["aL"] if amb else (255, 255, 255, 60), width=max(1, int(K)))
                yy += 6 * K
        hist = list(st["hist"]) if st["hist"] else []
        # age shifts history left: points are 5 min apart; data older than age leaves the right side empty
        shift_pts = (st["age"] or 0) // 5 if st["age"] is not None else 0
        pts_all = []
        for i, v in enumerate(hist):
            j = i - shift_pts
            if v is None or j < 0:
                pts_all.append(None)
            else:
                pts_all.append((gxi(j), gy(v)))
        # split into segments at gaps
        segs, cur = [], []
        for pnt in pts_all:
            if pnt is None:
                if len(cur) > 1:
                    segs.append(cur)
                cur = []
            else:
                cur.append(pnt)
        if len(cur) > 1:
            segs.append(cur)
        lastp = cur[-1] if cur else None
        if segs:
            if amb:
                for sg in segs:
                    d.line(sg, fill=col(None, hx("#B4B4B4")), width=max(1, int(1.5 * K)), joint="curve")
            else:
                for sg in segs:
                    img = glow(img, lambda g, sg=sg: g.line(sg, fill=A(rng, 180), width=int(4 * K), joint="curve"), 4 * K)
                d = D(img)
                tot = sum(len(sg) for sg in segs)
                k = 0
                for sg in segs:
                    for a_, b_ in zip(sg, sg[1:]):
                        d.line([a_, b_], fill=mix(hx("#5AAAFF"), rng, k / max(1, tot)), width=int(2.5 * K))
                        k += 1
                    k += 1
                if p["predictions"] and st["pred"] and not stale and st["flavour"] == "FULL" and st["loop_mode"] != "OPEN":
                    for kk, v in enumerate(st["pred"]):
                        xx, yy = gxi(nh + kk), gy(v)
                        rr = 1.6 * K
                        d.ellipse([xx - rr, yy - rr, xx + rr, yy + rr], fill=A(T["pred"], 210))
            if lastp:
                px_, py_ = lastp
                if amb:
                    r3 = 3 * K
                    d.ellipse([px_ - r3, py_ - r3, px_ + r3, py_ + r3], fill=(0, 0, 0), outline=col(None, T["a1"]), width=max(1, int(K)))
                else:
                    if not stale:
                        img = glow(img, lambda g: g.ellipse([px_ - 7 * K, py_ - 7 * K, px_ + 7 * K, py_ + 7 * K], fill=A(rng, 220)), 5 * K)
                    d = D(img)
                    d.ellipse([px_ - 4 * K, py_ - 4 * K, px_ + 4 * K, py_ + 4 * K], fill=T["white"] if not stale else T["txt3"],
                              outline=rng, width=int(2 * K))
        if st["caption"] and not amb:
            cfnt = c.f(16, DEMI)
            mx, my2 = (GX0 + GX1) / 2 * K, (GY0 + GY1) / 2 * K
            hw = tw(d, st["caption"], cfnt) / 2 + 12 * K
            d.rounded_rectangle([mx - hw, my2 - 14 * K, mx + hw, my2 + 14 * K], radius=14 * K, fill=hx("#0B0F22", 230),
                                outline=(255, 255, 255, 60), width=max(1, int(K)))
            text(d, (mx, my2), st["caption"], cfnt, T["txt1"])
    d = D(img)
    if lowbit:
        d.fontmode = "1"

    # ---- pills (removed while switched: they are dataset 0 only)
    PY0, PY1 = Lo["PILL"] or (0, 0)
    pr = Lo["PILL_R"]
    show_loop = f["loop"] != "NOAPS" and Lo["PILL"] is not None
    show_iob = p["iob"] and Lo["PILL"] is not None
    boxes = []
    (lx0, lx1), (ix0, ix1) = Lo["PILLS_X"]
    if show_loop and show_iob:
        boxes = [("loop", (lx0, PY0, lx1, PY1)), ("iob", (ix0, PY0, ix1, PY1))]
    elif show_loop:
        boxes = [("loop", (163, PY0, 287, PY1))]
    elif show_iob:
        boxes = [("iob", (163, PY0, 287, PY1))]
    pvx = 20 if not st["others"] else 18
    plf = c.f(fl(12), DEMI)  # labels are kept at every size (rule 2); floor 11 px physical
    own = f["owner"]
    for kind, box in boxes:
        if amb:
            if not lowbit:
                d.rounded_rectangle(c.P(*box), radius=pr * K, outline=T["aL"], width=max(1, int(K)))
        else:
            img = glass(c, img, box, pr, alpha=18, edge=55)
        d = D(img)
        if lowbit:
            d.fontmode = "1"
        x0, y0, x1, y1 = c.P(*box)
        my = (y0 + y1) / 2

        def fit_value(val, label, vx):
            """value shrinks (20 -> 15 px) before the label is ever dropped."""
            px_ = pvx
            while px_ > 15 and (x1 - 12 * K) - tw(d, label, plf) - 5 * K < vx + tw(d, val, c.f(px_, DEMI)):
                px_ -= 1
            return c.f(px_, DEMI)

        if kind == "loop":
            dx = x0 + 18 * K
            lh = f["loop"]
            la = st["loop_age"]
            label = S_["lgs"] if st["loop_mode"] == "LGS" and lh in ("OK", "WARN", "BAD") else "LOOP"
            dot = dict(OK=("fill", T["loop_ok"]), WARN=("fill", T["warn"]), BAD=("ring", T["r_stale"]),
                       NEVER=("ring", T["r_stale"]), NEUTRAL=("ring", T["r_stale"]), OPEN=("ring", T["loop_ok"]),
                       OFF=("pause", T["warn"]))[lh]
            if st["first_start"]:
                dot = ("ring", T["r_stale"])
            # red budget: while W2 / W3 / W6 own the slot the loop element is neutral grey (slot carries it)
            neutral = own is not None and own[0] in (2, 3, 6)
            if neutral and lh in ("OK", "WARN", "BAD"):
                dot = ("ring", T["r_stale"])
            if lh == "OPEN":
                val, vcol = S_["open"], T["txt2"]
            elif lh == "OFF":
                val, vcol = S_["aus"], T["warn"]
            elif lh == "NEVER" or st["first_start"]:
                val, vcol = "--", T["txt3"]
            else:
                val = fmt_age(la, L)
                vcol = T["txt2"] if (neutral or lh in ("NEUTRAL", "BAD")) else T["warn"] if lh == "WARN" else T["txt1"]
            r5 = 5 * K
            kindd, dc = dot
            if amb:
                # ambient: no hue. OK/NEVER/NEUTRAL/OPEN = thin a2 ring; WARN/OFF = 2 px a1 ring. No '!' suffix.
                strong = lh in ("WARN", "OFF") and not neutral
                rc = col(None, T["a1"] if strong else T["a2"])
                d.ellipse([dx - r5, my - r5, dx + r5, my + r5], outline=rc,
                          width=max(1, int((1 if (lowbit or not strong) else 2) * K)))
                if kindd == "pause":
                    d.line([dx - 1.6 * K, my - 2.5 * K, dx - 1.6 * K, my + 2.5 * K], fill=rc, width=max(1, int(1.2 * K)))
                    d.line([dx + 1.6 * K, my - 2.5 * K, dx + 1.6 * K, my + 2.5 * K], fill=rc, width=max(1, int(1.2 * K)))
            else:
                if kindd == "fill":
                    img = glow(img, lambda g: g.ellipse([dx - 9 * K, my - 9 * K, dx + 9 * K, my + 9 * K], fill=A(dc, 190)), 4 * K)
                    d = D(img)
                    d.ellipse([dx - r5, my - r5, dx + r5, my + r5], fill=dc)
                else:
                    d.ellipse([dx - r5, my - r5, dx + r5, my + r5], outline=dc, width=max(1, int(2 * K)))
                    if kindd == "pause":
                        d.line([dx - 1.6 * K, my - 2.5 * K, dx - 1.6 * K, my + 2.5 * K], fill=dc, width=max(1, int(1.4 * K)))
                        d.line([dx + 1.6 * K, my - 2.5 * K, dx + 1.6 * K, my + 2.5 * K], fill=dc, width=max(1, int(1.4 * K)))
            vx = dx + 12 * K
            pf = fit_value(val, label, vx)
            text(d, (vx, my), val, pf, col(vcol, T["a1"]), anchor="lm")
            text(d, (x1 - 12 * K, my + K), label, plf, col(T["lbl"], T["a3"]), anchor="rm")
        else:
            v = display(st["iob"])
            if st["first_start"]:
                v = "--"
            try:
                num = float(v.replace(",", "."))
            except ValueError:
                num = None
            # family v2: the insulin colour lives on the glyph/label, the value is txt1 (0 / unknown dim)
            vcol = T["txt3"] if (num is None or num == 0) else T["txt1"]
            gx_ = x0 + 18 * K
            drop_glyph(d, c, gx_, my, col(T["ins"], T["a2"]), hollow=amb and burnin)
            vx = gx_ + 11 * K
            pf = fit_value(v, "IOB", vx)
            text(d, (vx, my), v, pf, col(vcol, T["a1"]), anchor="lm")
            text(d, (x1 - 12 * K, my + K), "IOB", plf, col(T["ins"], T["a3"]), anchor="rm")
    d = D(img)
    if lowbit:
        d.fontmode = "1"

    # ---- footer (= status slot)
    runs, is_warning = footer_runs(st, f, c, L)
    if not amb and any(r_[1] == T["alert"] for r_ in runs):
        red("status slot")
    fy = Lo["FOOT_Y"]
    fpx = Lo["FOOT_PX"]
    if chin is not None and fy + 9 > chin - 6:
        fy = chin - 15
    ff = c.f(fl(fpx), MED)
    # usable width: chord at 0.93 R
    dy = abs(fy - 225)
    maxw = 2 * math.sqrt(max(0, (0.93 * 225) ** 2 - dy ** 2)) * K
    if shape != "round":
        maxw = 420 * K
    sep = ("  ·  ", col(T["txt3"], T["a3"]))
    if is_warning:
        line = [(runs[0][0], col(runs[0][1], T["a1"]))]
        while tw(d, line[0][0], ff) > maxw and ff.size > 16 * K:
            ff = c.f(ff.size / K - 1, MED)
    else:
        items = list(runs)

        def build(items):
            out = []
            for i, it in enumerate(items):
                if i:
                    out.append(sep)
                out.append((it[0], col(it[1], T["a2"] if it[1] != T["tt"] else T["a2"])))
            return out

        line = build(items)
        while items and sum(tw(d, s, ff) for s, _ in line) > maxw:
            drop = min(range(len(items)), key=lambda i: items[i][2])
            items.pop(drop)
            line = build(items)
    if line:
        runs_line(d, 225 * K, fy * K, line, ff)

    # ---- secondary patient chips
    if st["others"] and Lo["CHIPS"]:
        cy0, cy1 = Lo["CHIPS"]
        if chin is not None and cy1 > chin - 6:
            dlt = cy1 - (chin - 6)
            cy0 -= dlt
            cy1 -= dlt
        n = len(st["others"])
        wch = 100 if not st["switched"] else (200 if n == 1 else 150)
        xs = [175] if n == 1 else [121, 229]
        if st["switched"]:
            xs = [125] if n == 1 else [72, 228]
        for o, x0_ in zip(st["others"], xs):
            img = draw_chip(c, img, (x0_, cy0, x0_ + wch, cy1), o, L, small, n, big=st["switched"], fl=fl)
        d = D(img)

    # ---- bolus ball (interactive only, never ambient)
    if not amb and f["ball"] not in ("off", "hidden", None):
        img = draw_ball(c, img, Lo["BALL"], dict(st, ball=f["ball"]), L, sc)

    # ---- compose into device
    m = Image.new("L", img.size, 0)
    ImageDraw.Draw(m).ellipse([0, 0, W - 1, W - 1], fill=255)
    face = Image.new("RGB", img.size, (0, 0, 0))
    face.paste(img.convert("RGB"), (0, 0), m)
    face = face.resize((diam, diam), Image.LANCZOS) if ss != 1 else face.resize((diam, diam), Image.NEAREST)
    if lowbit:
        face = face.convert("L").point(lambda v: 255 if v > 90 else 0).convert("RGB")
    if shape == "round":
        dev = Image.new("RGB", (size, size), (0, 0, 0))
        dev.paste(face, (shift[0], shift[1]))
        if visible_h is not None:
            dev = dev.crop((0, 0, size, visible_h))
        return dev
    # square / rect: backdrop full-bleed, face in the inscribed circle
    w_, h_ = rect if rect else (size, size)
    dev = Image.new("RGB", (w_, h_), (0, 0, 0))
    if not amb:
        dd = ImageDraw.Draw(dev)
        for y in range(h_):
            t = y / h_
            cc = mix(BG_TOP, BG_MID, t / 0.55) if t < 0.55 else mix(BG_MID, BG_BOT, (t - 0.55) / 0.45)
            dd.line([(0, y), (w_, y)], fill=cc[:3])
    fm = Image.new("L", (diam, diam), 0)
    ImageDraw.Draw(fm).ellipse([0, 0, diam - 1, diam - 1], fill=255)
    if not amb:
        fm = fm.filter(ImageFilter.GaussianBlur(diam * 0.02))
    dev.paste(face, ((w_ - diam) // 2 + shift[0], (h_ - diam) // 2 + shift[1]), fm)
    return dev


def draw_chip(c, img, box, o, L, small, n, big=False, fl=None):
    """compact secondary patient block (states.md §4.9). big=True: dataset 0's block while switched."""
    K = c.K
    amb = c.amb
    fl = fl or (lambda v: v)
    so = sec_state(o)
    rk, bga = so["range"], so["bgAge"]
    rc = RANGE_COL[rk]
    stale = rk == "STALE"
    low = rk in ("LOW", "URGENT_LOW")
    urgent = rk == "URGENT_LOW"
    rad = 12
    if amb:
        if not c.lowbit or low:
            D(img).rounded_rectangle(c.P(*box), radius=rad * K, outline=(T["white"] if c.lowbit else T["a1"] if low else T["aL"]),
                                     width=max(1, int(K)))
    else:
        img = glass(c, img, box, rad, alpha=16, edge=50)
    d = D(img)
    if c.lowbit:
        d.fontmode = "1"
    x0, y0, x1, y1 = box
    if low:  # doubled outline = low, the same shape code as the hero in ambient (rule 2: not colour only)
        oc = T["white"] if c.lowbit else (T["a1"] if amb else T["r_low"])
        if not amb:
            d.rounded_rectangle(c.P(*box), radius=rad * K, outline=oc, width=max(1, int((2 if urgent else 1.5) * K)))
        g = 3
        d.rounded_rectangle(c.P(x0 + g, y0 + g, x1 - g, y1 - g), radius=(rad - g) * K, outline=oc, width=max(1, int(K)))
        if not amb:
            red("secondary block")

    def cc(i, a):
        if c.lowbit:
            return T["white"]
        return a if amb else i

    name = o["name"] or ("P2" if o.get("idx", 1) == 1 else "P3")
    if len(name) > 10:
        name = name[:10] + "…"
    three_small = small and n >= 2
    nf = c.f(fl(14 if big else 12), DEMI)
    r1 = y0 + (13 if big else 11)
    r2 = y0 + (35 if big else 28)
    age_s = fmt_age(o["age"], L)
    ac = T["alert"] if bga in ("STALE", "VERY") else T["warn"] if bga == "AGING" else T["txt2"]
    text(d, ((x0 + 10) * K, r1 * K), name, nf, cc(T["txt2"], T["a2"]), anchor="lm")
    if urgent:  # filled TIEF marker replaces the age (fresh by definition)
        mf = c.f(fl(10.5 if not big else 12), BOLD)
        mw = tw(d, STR[L]["low"], mf)
        mb = [(x1 - 8) * K - mw - 10 * K, (r1 - 7) * K, (x1 - 8) * K, (r1 + 7) * K]
        if amb and c.burnin:
            d.rounded_rectangle(mb, radius=7 * K, outline=cc(None, T["a1"]), width=max(1, int(K)))
            text(d, ((mb[0] + mb[2]) / 2, r1 * K), STR[L]["low"], mf, cc(None, T["a1"]))
        else:
            d.rounded_rectangle(mb, radius=7 * K, fill=cc(T["r_low"], T["a1"]))
            text(d, ((mb[0] + mb[2]) / 2, r1 * K), STR[L]["low"], mf, (0, 0, 0) if amb else T["white"])
    elif not three_small or bga != "FRESH":
        text(d, ((x1 - 10) * K, r1 * K), age_s, nf, cc(ac, T["a1"] if bga != "FRESH" else T["a2"]), anchor="rm")
    # row 2: BG + arrow + loop
    bf = c.f(24 if big else 18, DEMI)
    bgs = o["bg"] if not (bga == "VERY" and o["age"] >= 60) else "---"
    bcol = cc(T["txt2"] if stale else rc, T["a1"])
    if c.burnin:
        w = text(d, ((x0 + 10) * K, r2 * K), bgs, bf, (0, 0, 0), anchor="lm", stroke=max(1, int(K)), stroke_fill=bcol)
    else:
        w = text(d, ((x0 + 10) * K, r2 * K), bgs, bf, bcol, anchor="lm")
    if stale:
        d.line([(x0 + 8) * K, (r2 + 1) * K, (x0 + 12) * K + w, (r2 + 1) * K], fill=bcol, width=max(1, int(2 * K)))
    else:
        arrow(d, c, (x0 + 10) * K + w + 12 * K, r2 * K, o["trend"], cc(rc, T["a1"]), scale=0.55 if not big else 0.7)
    if (not small or big) and not amb:
        la = o["loop_age"]
        lc = T["r_stale"] if la is None else T["loop_ok"] if la <= 5 else T["warn"] if la < 30 else T["alert"]
        r = 3.5 * K
        dx, dy = (x1 - 13) * K, r2 * K
        if big:
            ls = fmt_age(la, L) if la is not None else "--"
            lw_ = text(d, ((x1 - 10) * K, r2 * K), ls, c.f(16, DEMI), T["txt1"] if la is not None and la <= 5 else lc, anchor="rm")
            dx = (x1 - 10) * K - lw_ - 9 * K
            if o.get("iob") and (x1 - x0) >= 190:
                iw = text(d, (dx - 12 * K, r2 * K), o["iob"], c.f(16, DEMI), T["txt1"], anchor="rm")
                drop_glyph(d, c, dx - 12 * K - iw - 8 * K, r2 * K, T["ins"])
        if la is not None and la >= 15:
            d.ellipse([dx - r, dy - r, dx + r, dy + r], outline=lc, width=max(1, int(1.5 * K)))
        else:
            d.ellipse([dx - r, dy - r, dx + r, dy + r], fill=lc)
    return img


def draw_ball(c, img, BALL, st, L="de", sc=1.0):
    """bolus ball, family v2 shapes: IDLE hollow glass sphere + indigo text; ARMED solid #5B63F5 + white text
    + 'nochmal' + white countdown; SENT solid indigo outline, '…' + static 3/4 ring; DISABLED dashed grey outline
    + diagonal slash, txt3 amount."""
    K = c.K
    state = st["ball"]
    bx, byy, br = [v * K for v in BALL]
    S_ = STR[L]
    amt = st["amount"]
    d = D(img)
    rr = 35 * K
    sub_px = max(11.0 / sc, 11)

    def sphere(lo, hi, a):
        nonlocal img
        sph = Image.new("RGBA", img.size, (0, 0, 0, 0))
        sd = ImageDraw.Draw(sph)
        kk = br
        step = max(1, int(K))
        while kk > 0:
            t = 1 - kk / br
            off = t * 8 * K
            sd.ellipse([bx - kk - off / 2, byy - kk - off, bx + kk - off / 2, byy + kk - off],
                       fill=mix(lo, hi, t ** 0.8)[:3] + (int(a),))
            kk -= step
        m = Image.new("L", img.size, 0)
        ImageDraw.Draw(m).ellipse([bx - br, byy - br, bx + br, byy + br], fill=255)
        sph.putalpha(ImageChops.multiply(sph.getchannel("A"), m))
        img = Image.alpha_composite(img, sph)

    def spec(a):
        nonlocal img
        hl = Image.new("RGBA", img.size, (0, 0, 0, 0))
        ImageDraw.Draw(hl).ellipse([bx - 17 * K, byy - 26 * K, bx + 5 * K, byy - 13 * K], fill=(255, 255, 255, a))
        img = Image.alpha_composite(img, hl.filter(ImageFilter.GaussianBlur(3 * K)))

    if state in ("idle", "delivered"):
        # hollow glass: indigo tint at 22 %, 2 px #7888FF rim, faint specular. Text indigo.
        img = glow(img, lambda g: g.ellipse([bx - br, byy - br, bx + br, byy + br], outline=A(T["ball"], 120),
                                            width=int(4 * K)), 5 * K)
        sphere(hx("#1C2060"), hx("#5560C8"), 56)
        spec(36)
        d = D(img)
        d.ellipse([bx - br, byy - br, bx + br, byy + br], outline=T["ball"], width=max(1, int(2 * K)))
        if state == "delivered":
            d.line([bx - 10 * K, byy, bx - 3 * K, byy + 8 * K, bx + 11 * K, byy - 9 * K], fill=T["ball"], width=int(3 * K), joint="curve")
            return img
        text(d, (bx, byy - 4 * K), amt, c.f(18, BOLD), hx("#AEB6FF"))
        text(d, (bx, byy + 13 * K), S_["unit"], c.f(sub_px, DEMI), T["ball"])
    elif state == "armed":
        img = glow(img, lambda g: g.ellipse([bx - br - 2 * K, byy - br - 2 * K, bx + br + 2 * K, byy + br + 2 * K],
                                            fill=A(T["ball_arm"], 150)), 7 * K)
        sphere(hx("#3A40C8"), hx("#8C93FF"), 245)
        spec(70)
        d = D(img)
        d.ellipse([bx - br, byy - br, bx + br, byy + br], outline=(255, 255, 255, 150), width=max(1, int(K)))
        d.arc([bx - rr, byy - rr, bx + rr, byy + rr], 0, 360, fill=(255, 255, 255, 60), width=int(2 * K))
        d.arc([bx - rr, byy - rr, bx + rr, byy + rr], -90, -90 + 360 * st["progress"], fill=T["white"], width=int(3 * K))
        text(d, (bx, byy - 4 * K), amt, c.f(18, BOLD), T["white"])
        lab = S_["again"] if sc >= 0.92 else S_["again_s"]
        text(d, (bx, byy + 13 * K), lab, c.f(sub_px, DEMI), T["white"])
    elif state in ("sent", "sent_flash", "delivering"):
        sphere(hx("#1C2060"), hx("#5560C8"), 40)
        d = D(img)
        d.ellipse([bx - br, byy - br, bx + br, byy + br], outline=T["white"] if state == "sent_flash" else T["ball"],
                  width=max(1, int((3 if state == "sent_flash" else 2) * K)))
        if state == "delivering":
            d.arc([bx - rr, byy - rr, bx + rr, byy + rr], 0, 360, fill=A(T["ball"], 50), width=int(2 * K))
            d.arc([bx - rr, byy - rr, bx + rr, byy + rr], -90, -90 + 360 * st["progress"], fill=T["ball"], width=int(2 * K))
        else:  # static 3/4 ring: "request pending", visibly not the white countdown
            d.arc([bx - rr, byy - rr, bx + rr, byy + rr], -90, 180, fill=A(T["ball"], 170), width=max(1, int(2 * K)))
        text(d, (bx, byy - 6 * K), "…", c.f(24, BOLD), T["txt1"])
        text(d, (bx, byy + 13 * K), f"{amt} {S_['unit']}", c.f(sub_px, DEMI), T["txt2"])
    elif state == "disabled":
        dashed_rr(d, (bx - br, byy - br, bx + br, byy + br), br, T["txt3"], max(1, int(1.5 * K)), 5 * K, 4 * K)
        q = br * 0.62
        d.line([bx + q, byy - q, bx - q, byy + q], fill=A(T["txt3"], 200), width=max(1, int(1.5 * K)))
        text(d, (bx, byy - 4 * K), amt, c.f(18, BOLD), T["txt3"])
        text(d, (bx, byy + 13 * K), S_["unit"], c.f(sub_px, DEMI), T["txt3"])
    return img


# ---------------------------------------------------------------- sheets
def tile_label(sheet, x, y, w, sid, note):
    sid, note = sid.replace("′", "'"), note.replace("′", "'").replace("→", "->")
    sd = ImageDraw.Draw(sheet)
    sd.text((x + w // 2, y + 8), sid, font=font(17, DEMI), fill=(235, 236, 240), anchor="mt")
    if note:
        words, lines, cur = note.split(), [], ""
        for wd in words:
            t = (cur + " " + wd).strip()
            if sd.textlength(t, font=font(13, MED)) > w - 16:
                lines.append(cur)
                cur = wd
            else:
                cur = t
        lines.append(cur)
        for i, ln in enumerate(lines[:3]):
            sd.text((x + w // 2, y + 32 + i * 17), ln, font=font(13, MED), fill=(160, 163, 175), anchor="mt")


def sheet(name, title, tiles, cols=5, tw_=450, th=450):
    rows = (len(tiles) + cols - 1) // cols
    LBL = 88
    PAD = 14
    Wd = cols * (tw_ + PAD) + PAD
    Hd = 70 + rows * (th + LBL + PAD) + PAD
    sh = Image.new("RGB", (Wd, Hd), (28, 29, 34))
    sd = ImageDraw.Draw(sh)
    sd.text((PAD + 4, 22), title, font=font(26, DEMI), fill=(240, 240, 244), anchor="lm")
    sd.text((PAD + 4, 50), "AAPS Glass · design system v2 · tiles at device pixels, labelled with the states.md id",
            font=font(15, MED), fill=(150, 153, 165), anchor="lm")
    for i, (sid, note, im) in enumerate(tiles):
        x = PAD + (i % cols) * (tw_ + PAD)
        y = 70 + (i // cols) * (th + LBL + PAD)
        if im.size != (tw_, th):
            bgt = Image.new("RGB", (tw_, th), (44, 45, 52))
            bgt.paste(im, ((tw_ - im.width) // 2, (th - im.height) // 2))
            im = bgt
        sh.paste(im, (x, y))
        tile_label(sh, x, y + th, tw_, sid, note)
    path = os.path.join(OUT, name)
    sh.save(path, optimize=True)
    print("wrote", path, sh.size)


def lit_pct(im):
    a = im.convert("L")
    px = a.tobytes()
    return round(sum(1 for p in px if p > 24) / len(px) * 100, 2)


def hist_for(sgv_end, start, seed, k=9):
    return series(start, sgv_end, k, sgv_end, seed)


def main():
    R = render

    def RR(sid, note, st, **kw):
        im = render(st, **kw)
        return (sid, reds_note(note), im)

    # common states
    HIGH = dict(sgv=212, bg=mmol(212), trend="UP45", delta="+0,6", delta_mgdl=11, hist=hist_for(212, 120, 7, 10),
                pred=[212 + 1.4 * k - 0.2 * k * k for k in range(1, 13)], iob="4,12", cob="24g", basal="1,35 U/h", loop_age=3)
    LOW = dict(sgv=61, bg=mmol(61), trend="DOWN45", delta="-0,3", delta_mgdl=-5, hist=hist_for(61, 120, 11, 8),
               pred=[61 - 0.8 * k + 0.2 * k * k for k in range(1, 13)], iob="0,21", basal="0,00 U/h", tt="8,0", tt_level=2)
    LOWF = dict(LOW, trend="FLAT", delta="+0,0", delta_mgdl=0)  # low, not falling
    URG = dict(sgv=50, bg=mmol(50), trend="DOWN2", delta="-0,8", delta_mgdl=-14, hist=hist_for(50, 130, 12, 9),
               pred=[50 - 0.6 * k + 0.25 * k * k for k in range(1, 13)], iob="1,10", basal="0,00 U/h")
    VHI = dict(sgv=275, bg=mmol(275), trend="UP", delta="+1,1", delta_mgdl=20, hist=hist_for(275, 150, 3, 10),
               pred=[275 + 1.0 * k for k in range(1, 13)], iob="5,40", cob="45g", basal="2,10 U/h")

    # ===== 1a data: BG / age / trend / delta
    t = []
    t.append(RR("bg.in_range", "range.in aurora, digits white, BG glow", S()))
    t.append(RR("bg.high", "range.high aurora + chip, KH run", S(**HIGH)))
    t.append(RR("bg.very_high", ">250 mg/dL: high hue + aurora alpha +20 %", S(**VHI)))
    t.append(RR("bg.low", "digits #FF5A6E + red word TIEF; W7-W9 suppressed; basal 0 neutral; ball disabled (falling)", S(**LOW)))
    t.append(RR("bg.urgent_low", "<54 / low+fast fall: filled TIEF badge + 2 px red card edge + aurora +20 %", S(**URG)))
    a_low, a_urg = R(S(**LOWF, ball="off")), R(S(**URG, ball="off"))
    t.append(("bg.low vs bg.urgent_low", "test tile: word vs filled badge + card edge; distinct without comparing hues",
              crop_pair(a_low, a_urg, (80, 104, 370, 246), "bg.low 3,4 (flat)", "bg.urgent_low 2,8 (fast fall)")))
    t.append(RR("bg.sentinel_low", "sgv<=39: phone string + urgent treatment (TIEF badge)", S(**dict(URG, sgv=39, bg="2,2", trend="DOWN", delta="-0,4"))))
    t.append(RR("bg.sentinel_high", "sgv>=400: '22,2+' + HOCH caption (range.high), fitted", S(**dict(VHI, sgv=400, bg="22,2", trend="FLAT", delta="+0,2"))))
    t.append(RR("age.fresh", "1′ in txt2", S(ball="off")))
    t.append(RR("age.aging", "8′ in warn; everything else unchanged", S(age=8, data_age=8, ball="off")))
    t.append(RR("age.stale", "17′: grey aurora, strike, W3 owns slot -> age grey, loop neutral", S(age=17, data_age=17, ball="idle")))
    t.append(RR("age.very_stale", "42′: still struck number, line ends 40′ ago", S(age=42, data_age=42, loop_age=44)))
    t.append(RR("age.very_stale (>=60′)", "BG '---', slot 'Keine Daten · 3 Std'", S(age=180, data_age=180, loop_age=181)))
    t.append(RR("conn.restored_old_data", "after watch reboot: 3 h old store -> age rules from the 1st frame", S(age=185, data_age=185, loop_age=186)))
    t.append(RR("age.none", "no BG timestamp: '---' + slot 'Kein BG' (warn, rank W3)", S(age=None, data_age=3, hist=[])))
    t.append(RR("trend.fast", "DOWN2 in range: double chevron", S(**dict(LOW, sgv=88, bg=mmol(88), trend="DOWN2", delta="-0,7", delta_mgdl=-12, tt_level=0, basal="0,85 U/h"))))
    t.append(RR("trend.none", "'??' / '--' from phone: empty chip", S(trend="NONE")))
    t.append(RR("delta.missing", "deltaMgdl null -> '--' txt3", S(delta="--", delta_mgdl=None)))
    t.append(RR("delta.wide", "'-12,5 · 11′' must fit the meta slot", S(**dict(HIGH, delta="-12,5", trend="DOWN2", age=11, data_age=11, sgv=200, bg=mmol(200)))))
    t.append(("trend.glyphs", "all 9 trend values (see inset)", trend_inset()))
    sheet("glass_states_data_bg.png", "Glass · data states 1/2 · BG, age, trend, delta", t)

    # ===== 1b data: graph / iob / cob / basal / tt
    t = []
    t.append(RR("graph.normal", "3 h history + 1 h prediction", S()))
    t.append(RR("graph.empty", "no entries: band + now line only", S(hist=[], pred=[])))
    gh = series(126, 172, 9, 169, 4)
    for i in range(14, 22):
        gh[i] = None
    t.append(RR("graph.gaps", "gap > 15′ breaks the line", S(hist=gh)))
    t.append(RR("graph.no_predictions", "client/pumpcontrol/open: right side empty", S(pred=[], flavour="CLIENT", loop_mode="UNKNOWN")))
    t.append(RR("graph.timeframe_changed", "double-tap graph: caption '3 Std' for 1,5 s", S(caption="3 Std")))
    t.append(RR("iob.normal", "blue drop glyph + blue label, value txt1", S()))
    t.append(RR("iob.zero", "'0,00' dim txt3", S(iob="0,00")))
    t.append(RR("iob.negative", "'-0,35' txt1, label kept", S(iob="-0,35")))
    t.append(RR("iob.large", "'12,40': value steps down, label kept", S(iob="12,40", **{k: v for k, v in HIGH.items() if k not in ("iob",)})))
    t.append(RR("iob.unknown", "'--' txt3", S(iob="-.--")))
    t.append(RR("cob.values", "'KH 20(30) g' (de unit spacing)", S(cob="20(30)g")))
    t.append(RR("cob.unknown", "'--g' -> 'KH --' txt3", S(cob="--g")))
    t.append(RR("basal.tbr_percent", "'120 %' in tbr #3DDBD9", S(basal="120%")))
    t.append(RR("basal.tbr_zero", "'0 %' in warn (BG in range)", S(basal="0%")))
    t.append(RR("basal.zero_abs", "'0,00 U/h' in warn (BG in range)", S(basal="0,00 U/h")))
    t.append(RR("basal.zero_abs + bg.low", "fresh low: zero basal neutral txt2", S(**dict(LOWF, tt_level=0))))
    t.append(RR("basal.unknown", "'-.--U/h' -> '--' txt3", S(basal="-.--U/h")))
    t.append(RR("tt.active", "'TT 5,6' in tt violet, first run", S(tt="5,6", tt_level=2)))
    t.append(RR("tt.active_range", "'TT 4,4 - 5,0' + KH + basal (drop order)", S(tt="4,4 - 5,0", tt_level=2, cob="12g")))
    t.append(RR("tt.aps_adjusted", "level 1: hidden (default)", S(tt="6,1", tt_level=1)))
    t.append(RR("status.bgi + iob.detailed", "prefs on: low-priority runs, dropped first", S(prefs=dict(bgi=True, detailed_iob=True))))
    sheet("glass_states_data_values.png", "Glass · data states 2/2 · graph, IOB, KH, basal, TT", t)

    # ===== 2 warnings
    t = []
    t.append(RR("conn.first_start", "W1 'Warte auf AAPS', skeleton, ball hidden", S(first_start=True, age=None, data_age=99, loop_age=None, iob="IOB", hist=[], pred=[], trend="NONE", delta="--")))
    t.append(RR("conn.phone_lost_fresh", "W10: glyph under the meta line only; ball disabled", S(phone_reach=False, data_age=3, age=3)))
    t.append(RR("conn.phone_lost_fresh + bg.low", "W10 glyph exempt from low suppression", S(**LOW, phone_reach=False, data_age=3, age=3)))
    t.append(RR("conn.phone_lost_stale", "W2 'Telefon getrennt · 14′'; age grey, loop neutral; ball disabled", S(phone_reach=False, data_age=14, age=14, loop_age=14)))
    t.append(RR("conn.phone_silent (worst case)", "W3 slot red; age grey; loop ring grey", S(data_age=17, age=17, loop_age=18)))
    t.append(RR("conn.no_profile", "W4 'Kein Profil'; IOB/basal '--'; loop grey; ball disabled", S(no_profile=True, iob="", basal="-.--U/h", pred=[])))
    t.append(RR("loop.ok", "filled green dot, '2′'", S()))
    t.append(RR("loop.warn", "8′: amber dot + value", S(loop_age=8)))
    t.append(RR("loop.bad 15-29′", "W6 'Loop 23′' in warn (last TBR, may be a false alarm); pill grey", S(loop_age=23)))
    t.append(RR("loop.bad >=30′", "W6 'Loop 34′' in alert; pill grey", S(loop_age=34)))
    t.append(RR("loop.never", "openApsStatus -1: hollow grey, '--'", S(loop_age=None, flavour="CLIENT", pred=[])))
    t.append(RR("loop.open", "hollow green ring, 'offen' + LOOP, no age alarm", S(loop_mode="OPEN", loop_age=34, pred=[])))
    t.append(RR("loop.lgs", "label LGS instead of LOOP", S(loop_mode="LGS")))
    t.append(RR("loop.off", "W5 'Loop aus'; pause ring, 'aus'; ball disabled (LOOP_OFF)", S(loop_run="OFF", loop_age=4, pred=[])))
    t.append(RR("loop.unknown_mode / flavor.client", "AAPSClient: age-only dot, no predictions", S(flavour="CLIENT", loop_mode="UNKNOWN", loop_age=7, pred=[], ball="off")))
    t.append(RR("loop.no_aps / flavor.pumpcontrol", "loop pill hidden, IOB centred", S(flavour="PUMPCONTROL", loop_age=None, loop_mode="UNKNOWN", pred=[])))
    t.append(RR("pump.res_critical", "W7 'Reservoir 5 U' first run, alert", S(res="5U", res_level=2)))
    t.append(RR("pump.res_low", "W9 'Reservoir 18 U' first run, warn", S(res="18U", res_level=1)))
    t.append(RR("batt.phone_low", "W8 'Tel 18 %' warn", S(batt="18", batt_level=0)))
    t.append(RR("batt.phone_critical", "W8 'Tel 7 %' alert", S(batt="7", batt_level=0)))
    t.append(RR("warn.suppressed_by_low", "fresh low + res critical: W7-W9 hidden", S(**LOW, res="5U", res_level=2)))
    t.append(RR("prio: urgent low + loop.bad (worst case)", "W6 still shows during urgent low (W1-W6 always)", S(**URG, loop_age=34)))
    t.append(RR("loop.bad + bg.low (worst case)", "BG group + slot only", S(**LOW, loop_age=23)))
    t.append(RR("warn.stack", "loop 23′ + res 5 U + Tel 7 %: only W6 shows", S(loop_age=23, res="5U", res_level=2, batt="7", batt_level=0)))
    sheet("glass_states_warnings.png", "Glass · loop, pump, phone and connection warnings (status slot W1-W10) · reds = red-budget audit", t)

    # ===== 3 bolus ball
    t = []
    t.append(RR("ball.off", "pref off (default): ball absent, face symmetric", S(ball="off")))
    t.append(RR("ball.hidden", "wearControl off / amount not allowed: same as off", S(ball="hidden")))
    t.append(RR("ball.idle", "HOLLOW glass sphere, 2 px #7888FF rim, indigo '+0,5' 'U'", S(ball="idle")))
    t.append(RR("ball.idle (1,0 U)", "amount from halo_bolus_amount", S(ball="idle", amount="+1,0")))
    t.append(RR("ball.armed", "SOLID #5B63F5, white '+0,5' 'nochmal', countdown 60 %", S(ball="armed", progress=0.6)))
    t.append(RR("ball.armed (late)", "15 % of WINDOW_MS left", S(ball="armed", progress=0.15)))
    t.append(RR("ball.sent (flash)", "2nd tap: 150 ms white rim", S(ball="sent_flash")))
    t.append(RR("ball.sent", "solid indigo outline, '…' + '+0,5 U', static 3/4 ring", S(ball="sent")))
    t.append(RR("ball.disabled (STALE_DATA)", "dashed grey outline + slash, txt3", S(ball="idle", age=17, data_age=17, loop_age=18)))
    t.append(RR("ball.disabled (NO_PHONE)", "phone lost, data fresh", S(ball="idle", phone_reach=False, data_age=3)))
    t.append(RR("ball.disabled (LOW_BG)", "urgent low: tap -> toast 'BG zu niedrig'", S(**URG, ball="idle")))
    t.append(RR("ball.disabled (LOW_BG, falling)", "low + falling trend", S(**LOW, ball="idle")))
    t.append(RR("ball.idle (low, flat)", "low but not falling: ball stays idle", S(**LOWF, ball="idle")))
    t.append(RR("ball.disabled (NO_PROFILE)", "toast 'Kein Profil'", S(no_profile=True, iob="", basal="-.--U/h", pred=[], ball="idle")))
    t.append(RR("ball.disabled (LOOP_OFF)", "toast 'Loop aus' (suspend not distinguishable, G6)", S(loop_run="OFF", loop_age=4, pred=[], ball="idle")))
    t.append(RR("ball.disabled (MAX_BOLUS)", "amount > phone max: disabled 40 %, toast", S(ball="idle", max_bolus=True)))
    t.append(RR("ball.delivering", "P2: BolusProgress ring 40 %", S(ball="delivering", progress=0.4)))
    t.append(RR("ball.delivered", "P2: check mark for 2 s, then idle", S(ball="delivered")))
    t.append(RR("mode.transition", "enter ambient: ball removed, armed cleared", S(ball="armed"), mode="ambient"))
    t.append(("ball.hit_area", "r=44 target, 8 px clear of graph zone (overlay)", hit_overlay(R(S(ball="idle")))))
    rs = [R(S(ball=b)) for b in ("idle", "armed", "sent")] + [R(S(ball="idle", phone_reach=False, data_age=3))]
    t.append(("ball.shapes (450)", "idle hollow · armed solid · sent outline+ring · disabled dashed+slash",
              ball_row(rs, ["idle", "armed", "sent", "disabled"], zoom=1.0)))
    rs4 = [R(S(ball=b), size=384) for b in ("idle", "armed", "sent")] + [R(S(ball="idle", phone_reach=False, data_age=3), size=384)]
    t.append(("ball.shapes (384)", "same at 0.853; armed sub-label '2×' below 416 px",
              ball_row(rs4, ["idle", "armed 2×", "sent", "disabled"], box=(0, 100, 82, 200), zoom=1.0)))
    sheet("glass_states_ball.png", "Glass · bolus ball state machine (request only, dataset 0) · shape, not alpha, separates the states", t)

    # ===== 4 ambient
    t = []
    a_in = R(S(), mode="ambient")
    t.append(("mode.ambient · in", "greys, solid 1 px card", a_in))
    t.append(RR("mode.ambient · high", "dashed card = high", S(**HIGH), mode="ambient"))
    t.append(RR("mode.ambient · very_high", "dashed 2 px, brighter", S(**VHI), mode="ambient"))
    t.append(RR("mode.ambient · low", "double card outline + TIEF word", S(**LOW), mode="ambient"))
    t.append(RR("mode.ambient · urgent_low", "double, inner 2 px, filled TIEF badge, BG Demi", S(**URG), mode="ambient"))
    t.append(RR("mode.ambient_warning · stale", "strike + 'Keine Daten · 17′'", S(age=17, data_age=17, loop_age=18), mode="ambient"))
    t.append(RR("mode.ambient_warning · lost", "'Telefon getrennt · 14′'", S(phone_reach=False, data_age=14, age=14, loop_age=14), mode="ambient"))
    t.append(RR("mode.ambient_warning · loop.bad", "'Loop 23′', ring neutral, no '!'", S(loop_age=23), mode="ambient"))
    t.append(RR("mode.ambient · first_start", "'Warte auf AAPS'", S(first_start=True, age=None, data_age=99, loop_age=None, iob="IOB", hist=[], pred=[], trend="NONE"), mode="ambient"))
    t.append(RR("mode.ambient · W7 dropped", "res critical not shown in ambient", S(res="5U", res_level=2, tt="5,6", tt_level=2), mode="ambient"))
    lb = R(S(), mode="lowbit")
    t.append(("mode.ambient_low_bit · in", "1 bit, AA off, no ambLine", lb))
    t.append(RR("mode.ambient_low_bit · high", "dashed white card", S(**HIGH), mode="lowbit"))
    t.append(RR("mode.ambient_low_bit · urgent_low", "double card + filled badge", S(**URG), mode="lowbit"))
    t.append(RR("mode.ambient_low_bit · stale", "strike 2 px + text", S(age=17, data_age=17, loop_age=18), mode="lowbit"))
    t.append(RR("mode.ambient_low_bit · loop.bad", "'Loop 23′' text, no '!'", S(loop_age=23), mode="lowbit"))
    bi = R(S(), mode="burnin", shift=(3, -2))
    t.append(("mode.ambient_burn_in · in", f"outlined BG, hollow dot, shift (+3,-2); lit {lit_pct(bi)} %", bi))
    bu = R(S(**URG), mode="burnin", shift=(-4, 2))
    t.append(("mode.ambient_burn_in · urgent_low", f"double card, OUTLINED badge; lit {lit_pct(bu)} %", bu))
    lbb = R(S(**LOW), mode="lowbit_burnin", shift=(2, 3))
    t.append(("mode.ambient_low_bit_burn_in", f"union of both; lit {lit_pct(lbb)} %", lbb))
    t.append(RR("mode.ambient · multi.two", "block = name + BG + age", S(patient="Markus", others=[other("Anna", 112, "UP45", 3)]), mode="ambient"))
    t.append(RR("mode.ambient · TT", "TT kept, basal/KH dropped", S(tt="5,6", tt_level=2, cob="24g"), mode="ambient"))
    t.append(("sys.simplified · in", "family SimpleUi reference: txt1 digits, hue on arrow only", simple_ui(S())))
    t.append(("sys.simplified · aging", "age line in warn", simple_ui(S(age=8, data_age=8))))
    t.append(("sys.simplified · low", "TIEF word", simple_ui(S(**LOW))))
    t.append(("sys.simplified · urgent_low", "filled TIEF badge", simple_ui(S(**URG))))
    t.append(("sys.simplified · stale", "strike + 'Keine Daten · 17′'", simple_ui(S(age=17, data_age=17))))
    t.append(("sys.simplified · phone_lost", "W2 'Telefon getrennt · 14′'", simple_ui(S(phone_reach=False, age=14, data_age=14))))
    t.append(("sys.simplified · ambient low", "doubled frame + TIEF", simple_ui(S(**LOW), amb=True)))
    t.append(("sys.simplified · ambient urgent", "doubled frame, filled badge", simple_ui(S(**URG), amb=True)))
    print("lit: ambient", lit_pct(a_in), "lowbit", lit_pct(lb), "burnin", lit_pct(bi), lit_pct(bu), "both", lit_pct(lbb))
    sheet("glass_states_ambient.png", "Glass · ambient, low-bit, burn-in (no ball, no glow, W1-W6 only) · shared SimpleUi", t)

    # ===== 5 units & locale & prefs
    t = []
    t.append(RR("units.mmol / locale.de_24h", "phone strings with comma", S()))
    t.append(RR("units.mmol 4-char", "'11,8' fitted (78 px)", S(**dict(HIGH, sgv=212, bg="11,8"))))
    MG = dict(units="mgdl", bg="115", sgv=115, delta="+3", delta_mgdl=3, hist=hist_for(115, 100, 5),
              pred=[115 + 0.5 * k for k in range(1, 13)], tt="100", amount="+0,5")
    t.append(RR("units.mgdl", "integers, graph 45-252", S(**MG)))
    t.append(RR("units.mgdl · high 3-digit", "'254' + '+18'", S(**dict(MG, sgv=254, bg="254", delta="+18", delta_mgdl=18, trend="UP", hist=hist_for(254, 140, 3)))))
    t.append(RR("units.mgdl · sentinel_high", "'400+' + HOCH", S(**dict(MG, sgv=400, bg="400", delta="+4", trend="FLAT", hist=hist_for(399, 300, 3)))))
    t.append(RR("units.mgdl · sentinel_low", "'39' + TIEF badge", S(**dict(MG, sgv=39, bg="39", delta="-6", delta_mgdl=-6, trend="DOWN", hist=hist_for(39, 90, 3)))))
    t.append(RR("units.mgdl · TT", "'TT 100'", S(**dict(MG, tt_level=2))))
    EN = dict(lang="en", time="2:07", h12=True, ampm="PM", date="Fri, Oct 17", bg="9.4", delta="+0.1", iob="2.53",
              basal="0.85 U/h", amount="+0.5")
    t.append(RR("locale.en_12h", "'2:07 PM', 'Fri, Oct 17', English phone strings", S(**EN, cob="24g")))
    t.append(RR("locale.en_12h · warning", "'Phone disconnected · 14′'", S(**EN, phone_reach=False, data_age=14, age=14, loop_age=14)))
    t.append(RR("locale.en · low", "'LOW' word", S(**dict(LOW, **dict(EN, bg="3.4", delta="-0.3", basal="0.00 U/h", iob="0.21")))))
    FR = dict(lang="fr", date="ven. 17 oct.", ball="off")
    t.append(RR("locale.long (fr)", "'Téléphone déconnecté · 14′' fits 257 px", S(**FR, phone_reach=False, data_age=14, age=14, loop_age=14)))
    t.append(RR("locale.long (fr) · loop", "'Boucle arrêtée'", S(**FR, loop_run="OFF")))
    t.append(RR("pref.date + week", "'Fr 17. Okt · KW 42'", S(prefs=dict(week=True))))
    t.append(RR("pref.date off", "time moves to y=74", S(prefs=dict(date=False))))
    t.append(RR("pref.seconds", "':07' txt2, interactive only", S(prefs=dict(seconds=True))))
    ALL = dict(date=True, week=True, seconds=True, delta=True, ago=True, direction=True, iob=True, detailed_iob=True,
               cob=True, basal=True, tt=True, bgi=True, uploader=True, rig=True, reservoir=True)
    t.append(RR("pref.all_on", "every toggle on: footer drops low runs", S(prefs=ALL, tt="5,6", tt_level=2, cob="24g", rig="80%")))
    MIN = dict(date=False, delta=False, ago=False, iob=False, cob=False, basal=False, tt=False, predictions=False)
    t.append(RR("pref.minimal", "BG + direction + time; no holes", S(prefs=MIN, ball="off")))
    t.append(RR("trend.hidden_pref", "chip off: BG centred, meta under it", S(prefs=dict(direction=False))))
    t.append(RR("delta.hidden_pref", "meta = age only", S(prefs=dict(delta=False))))
    t.append(RR("age.hidden_pref (aging)", "ago off but 8′ still shown (safety)", S(prefs=dict(ago=False, delta=False), age=8, data_age=8)))
    t.append(RR("iob.hidden_pref", "loop pill centred", S(prefs=dict(iob=False))))
    t.append(RR("sys.preview", "picker sample: 6,4 -> 10:08, no ball", S(sgv=115, bg="6,4", time="10:08", iob="1,25", loop_age=2, ball="hidden", hist=hist_for(115, 140, 2), pred=[115 - 0.3 * k for k in range(1, 13)])))
    sheet("glass_states_units_locale.png", "Glass · units, locale and display preferences", t)

    # ===== 6 multi-patient
    t = []
    t.append(RR("multi.one", "dataset 0 only: name hidden", S(patient="Markus")))
    o1 = other("Anna", 112, "UP45", 3, 3)
    o2 = other("Ben", 205, "FLAT", 2, 4, idx=2)
    t.append(RR("multi.two", "one block centred; hero name as header chip", S(patient="Markus", others=[o1])))
    t.append(RR("multi.three", "two blocks; graph and pills compress", S(patient="Markus", others=[o1, o2])))
    t.append(RR("multi.secondary_stale", "only Ben's block greys/strikes", S(patient="Markus", others=[o1, other("Ben", 205, "FLAT", 23, 24, idx=2)])))
    t.append(RR("multi.secondary_low", "doubled red block outline + arrow; stays in block", S(patient="Markus", others=[other("Anna", 63, "DOWN45", 2, 2), o2])))
    t.append(RR("multi.secondary_urgent_low", "block badge TIEF + slot 'Anna TIEF 2,8' (W3b)", S(patient="Markus", others=[other("Anna", 50, "DOWN2", 2, 2), o2])))
    t.append(RR("multi.secondary_very_stale", "slot 'Ben · Keine Daten 42′' (W3b)", S(patient="Markus", others=[o1, other("Ben", 205, "FLAT", 42, 44, idx=2)])))
    t.append(RR("multi.secondary_urgent · ambient", "doubled outline + filled TIEF marker + slot", S(patient="Markus", others=[other("Anna", 50, "DOWN2", 2, 2), o2]), mode="ambient"))
    t.append(RR("multi.patient_name", "'Annabelle-Sophie' -> 10 chars + …", S(patient="Markus", others=[other("Annabelle-Sophie", 112, "UP45", 3, 3), o2])))
    t.append(RR("multi.unnamed", "empty name -> P2 / P3", S(patient="", others=[other("", 112), other("", 205, idx=2)])))
    t.append(RR("multi.three + warning", "W6 in the slot is dataset 0 only", S(patient="Markus", loop_age=34, others=[o1, o2])))
    t.append(RR("multi.switched", "hero = Anna; pills/KH/basal/TT removed; Markus' block (IOB, loop) moves up", S(patient="Anna", sgv=112, bg=mmol(112), trend="UP45", switched=True, others=[other("Markus", 169, "FLAT", 1, 2, iob="2,53")], pred=[], tt="5,6", tt_level=2)))
    t.append(RR("multi.switched · dataset 0 low", "Markus' block doubles red; slot escalates if urgent", S(patient="Anna", sgv=112, bg=mmol(112), trend="UP45", switched=True, others=[other("Markus", 50, "DOWN2", 1, 2, iob="0,40")], pred=[])))
    t.append(RR("multi.three @384", "names at the 11 px floor; blocks drop loop dot", S(patient="Markus", others=[o1, o2]), size=384))
    t.append(RR("multi.three · ambient", "blocks = name + BG + age", S(patient="Markus", others=[o1, other("Ben", 205, "FLAT", 23, 24, idx=2)]), mode="ambient"))
    t.append(RR("multi.two · mg/dL", "units from each SingleBg", S(patient="Markus", **MG, others=[other("Anna", 112, "UP45", 3, 3, units="mgdl")])))
    sheet("glass_states_multi.png", "Glass · AAPSClient multi-patient (datasets 1 and 2)", t, cols=4)

    # ===== 7 shapes
    t = []
    base = S(tt="5,6", tt_level=2)
    for sz, nm in ((384, "Pixel Watch 1/2"), (396, "GW 40 mm"), (416, "GW 41 mm / TicWatch"), (450, "GW5 Pro (design)"),
                   (466, "TicWatch Pro 5"), (480, "GW 47 mm")):
        t.append((f"shape.round_{sz}", f"{nm}; s={sz / 450:.3f}" + ("; pill labels kept at the 11 px floor" if sz < 428 else ""), R(base, size=sz)))
    t.append(("shape.square_450", "face in inscribed circle, backdrop full-bleed", R(base, shape="square", size=450, rect=(450, 450))))
    t.append(("shape.square_360", "s=0.8", R(base, shape="square", size=360, rect=(360, 360))))
    t.append(("shape.rect_368x448", "circle d=368 centred vertically", R(base, shape="square", size=368, rect=(368, 448))))
    t.append(("shape.flat_tyre_360x330", "chin 30 px: nothing essential below 324", R(base, size=360, visible_h=330)))
    t.append(("shape.flat_tyre · multi.three", "layout up 16, basal run dropped; blocks end at 312 (>= 6 px above 324)", R(S(patient="Markus", others=[o1, o2]), size=360, visible_h=330)))
    t.append(("shape.flat_tyre · ambient", "", R(base, size=360, visible_h=330, mode="ambient")))
    t.append(("shape.round_384 · ambient", "", R(base, size=384, mode="ambient")))
    t.append(("shape.round_384 · warning", "status slot fits at 15,4 px", R(S(phone_reach=False, data_age=14, age=14, loop_age=14), size=384)))
    t.append(("shape.round_384 · width test", "'22,2+' HOCH, '-12,5', '12,40', labels kept", R(S(**dict(VHI, sgv=400, bg="22,2", delta="-12,5", iob="12,40", age=11, data_age=11)), size=384)))
    t.append(("shape.round_384 · urgent low", "TIEF badge + labels at 384", R(S(**URG), size=384)))
    sheet("glass_states_shapes.png", "Glass · screen sizes and shapes (tiles at real device pixels)", t, cols=5, tw_=480, th=480)


def trend_inset():
    c = Ctx(1.0, SS, False, False, False)
    W = 450 * SS
    im = Image.new("RGBA", (W, W), (0, 0, 0, 255))
    d = D(im)
    d.ellipse([0, 0, W - 1, W - 1], fill=hx("#0E1328"))
    names = ["UP2", "UP", "UP45", "FLAT", "DOWN45", "DOWN", "DOWN2", "UP3", "NONE"]
    for i, n in enumerate(names):
        cx = (120 + (i % 3) * 105) * SS
        cy = (120 + (i // 3) * 105) * SS
        r = 22 * SS
        col = T["r_in"] if n not in ("NONE",) else T["r_in"]
        d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=A(col, 40), outline=A(col, 210), width=2 * SS)
        arrow(d, c, cx, cy, n, col)
        text(d, (cx, cy + 36 * SS), n if n != "UP3" else "UP3/DOWN3", font(13 * SS, MED), T["txt2"])
    return im.convert("RGB").resize((450, 450), Image.LANCZOS)


def hit_overlay(im):
    im = im.copy().convert("RGBA")
    ov = Image.new("RGBA", im.size, (0, 0, 0, 0))
    d = ImageDraw.Draw(ov)
    d.ellipse([46 - 44, 176 - 44, 46 + 44, 176 + 44], outline=(255, 255, 255, 200), width=1)
    d.rectangle([84, 256, 366, 312], outline=(255, 210, 80, 200), width=1)
    d.text((48, 232), "ball r=44", font=font(12, DEMI), fill=(255, 255, 255, 220), anchor="mm")
    d.text((225, 250), "graph tap zone", font=font(12, DEMI), fill=(255, 210, 80, 220), anchor="mm")
    return Image.alpha_composite(im, ov).convert("RGB")


def simple_ui(st, amb=False):
    """ONE SimpleUi reference for the family (only the BG font varies per face).
    time on top; digits txt1 (amb1); range hue only on the arrow; age line under the BG (warn when aging);
    stale: strike + 'Keine Daten · NN′'; W2: 'Telefon getrennt · NN′'; low: TIEF word / urgent: filled badge;
    ambient range shapes: doubled frame = low, dashed frame = high."""
    W = 450 * SS
    c = Ctx(1.0, SS, amb, False, False)
    im = Image.new("RGBA", (W, W), (0, 0, 0, 255))
    d = D(im)
    f = face_state(st)
    L = st["lang"]
    S_ = STR[L]
    rk = f["range"]
    rc = RANGE_COL[rk]

    def col(i, a):
        return a if amb else i

    text(d, (225 * SS, 112 * SS), st["time"], font(46 * SS, ULT if amb else REG), col(T["txt1"], T["a1"]))
    stale = rk == "STALE"
    bgs = "---" if f["bgAge"] == "NONE" or (f["bgAge"] == "VERY" and st["age"] >= 60) else st["bg"]
    bf = font(104 * SS, DEMI if not amb or rk == "URGENT_LOW" else REG)
    bcx, bcy = 200 * SS, 222 * SS
    bcol = col(T["txt2"] if stale else T["txt1"], T["a1"])
    text(d, (bcx, bcy), bgs, bf, bcol)
    bw = tw(d, bgs, bf)
    if f["stale"]:
        d.line([bcx - bw / 2 - 6 * SS, bcy + 3 * SS, bcx + bw / 2 + 6 * SS, bcy + 3 * SS], fill=bcol, width=3 * SS)
    elif bgs != "---":
        arrow(d, c, bcx + bw / 2 + 32 * SS, bcy, st["trend"], col(rc, T["a1"]), scale=1.3)
    # ambient range shape around the digits
    fr = [bcx - bw / 2 - 22 * SS, bcy - 62 * SS, bcx + bw / 2 + 70 * SS, bcy + 54 * SS]
    if amb and rk in ("LOW", "URGENT_LOW"):
        d.rounded_rectangle(fr, radius=26 * SS, outline=T["a2"], width=SS)
        g = 6 * SS
        d.rounded_rectangle([fr[0] + g, fr[1] + g, fr[2] - g, fr[3] - g], radius=20 * SS, outline=T["a2"],
                            width=SS * (2 if rk == "URGENT_LOW" else 1))
    elif amb and rk in ("HIGH", "VERY_HIGH"):
        dashed_rr(d, fr, 26 * SS, T["a2"], SS, 6 * SS, 5 * SS)
    # line under the BG: TIEF / warning / age
    ly = 318 * SS
    own = f["owner"]
    if own and own[0] in (2, 3):
        text(d, (225 * SS, ly), own[1], font(24 * SS, MED), col(own[2], T["a1"]))
    elif rk in ("LOW", "URGENT_LOW") and bgs != "---":
        wf = font(22 * SS, BOLD)
        ww = tw(d, S_["low"], wf)
        if rk == "URGENT_LOW":
            box = [225 * SS - ww / 2 - 14 * SS, ly - 16 * SS, 225 * SS + ww / 2 + 14 * SS, ly + 16 * SS]
            d.rounded_rectangle(box, radius=16 * SS, fill=col(T["r_low"], T["a1"]))
            text(d, (225 * SS, ly), S_["low"], wf, (0, 0, 0) if amb else T["white"])
        else:
            text(d, (225 * SS, ly), S_["low"], wf, col(T["r_low"], T["a1"]))
        text(d, (225 * SS, ly + 34 * SS), fmt_age(st["age"], L), font(20 * SS, MED), col(T["txt2"], T["a2"]))
    else:
        ac = T["warn"] if f["bgAge"] == "AGING" else T["txt2"]
        text(d, (225 * SS, ly), fmt_age(st["age"], L), font(22 * SS, MED), col(ac, T["a1"] if f["bgAge"] == "AGING" else T["a2"]))
    if any(w[0] == 10 for w in f["warnings"]):
        phone_off_glyph(d, Ctx(1.0, SS, amb, False, False), 225 * SS, 368 * SS, col(T["txt2"], T["a2"]))
    m = Image.new("L", im.size, 0)
    ImageDraw.Draw(m).ellipse([0, 0, W - 1, W - 1], fill=255)
    out = Image.new("RGB", im.size, (0, 0, 0))
    out.paste(im.convert("RGB"), (0, 0), m)
    return out.resize((450, 450), Image.LANCZOS)


def crop_pair(a, b, box, la, lb):
    """side-by-side test tile: the same crop of two renders, stacked, 1:1 device pixels."""
    t = Image.new("RGB", (450, 450), (44, 45, 52))
    ca, cb = a.crop(box), b.crop(box)
    h = ca.height
    y = (450 - 2 * h - 30) // 2
    sd = ImageDraw.Draw(t)
    for k, (im, lab) in enumerate(((ca, la), (cb, lb))):
        yy = y + k * (h + 30)
        t.paste(im, ((450 - im.width) // 2, yy + 14))
        sd.text((225, yy + 6), lab, font=font(13, DEMI), fill=(220, 222, 230), anchor="mm")
    return t


def ball_row(renders, labels, box=(0, 118, 96, 234), zoom=1.0):
    t = Image.new("RGB", (450, 450), (44, 45, 52))
    sd = ImageDraw.Draw(t)
    n = len(renders)
    cw = 450 // n
    for k, (im, lab) in enumerate(zip(renders, labels)):
        cr = im.crop(box)
        if zoom != 1.0:
            cr = cr.resize((int(cr.width * zoom), int(cr.height * zoom)), Image.LANCZOS)
        x = k * cw + (cw - cr.width) // 2
        t.paste(cr, (x, 225 - cr.height // 2))
        sd.text((k * cw + cw // 2, 225 + cr.height // 2 + 16), lab, font=font(13, DEMI), fill=(220, 222, 230), anchor="mm")
    return t


def reds_note(note):
    return f"{note} · reds={len(REDS)}: {', '.join(REDS) if REDS else '-'}"


if __name__ == "__main__":
    main()
