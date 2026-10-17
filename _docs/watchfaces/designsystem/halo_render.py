"""AAPS Halo: parametric state renderer (design mockups only).

All geometry is in the 450 x 450 design canvas (see halo_spec.md section 2) and scaled to the target surface.
The model `M` mirrors what the watch gets (EventData.SingleBg / Status / prefs) plus the derived FaceState signals.
"""
import math, os
from functools import lru_cache
from PIL import Image, ImageDraw, ImageFont, ImageFilter

ROUND = "/System/Library/Fonts/SFNSRounded.ttf"
SS = 2  # supersampling

# ------------------------------------------------------------------ tokens (states.md section 3)
def hx(s): s = s.lstrip("#"); return tuple(int(s[i:i + 2], 16) for i in (0, 2, 4))
IN, HIGH, LOW, STALE = hx("3DDC97"), hx("FFB547"), hx("FF5A6E"), hx("8A93A8")
NEUTRAL_OL = hx("4A5160")  # neutral outline (loop pill while the slot owns W2/W3/W6)
WARN, ALERT, INS, TBR, TT = HIGH, LOW, hx("5AC8FA"), hx("3DDBD9"), hx("B388FF")
LOOPOK = hx("40D68C")
BALL, BALLARM = hx("7888FF"), hx("5B63F5")
TXT1, TXT2, TXT3 = hx("EEF1F7"), hx("9AA6C4"), hx("6F7A99")
AMB1, AMB2, AMB3, AMBL = hx("E0E0E0"), hx("9A9A9A"), hx("6E6E6E"), hx("3C3C3C")
TRACK, BAND, IOBTRACK = hx("1E2228"), hx("1C3E30"), hx("1A1C24")
WHITE, BLACK = (255, 255, 255), (0, 0, 0)

MG = 18.0182
CX = CY = 225.0
RING_R, RING_W, RING_W_EMPH = 133.5, 13.0, 19.0
A0, SWEEP = 135.0, 270.0
SMIN, SMAX = 40.0, 300.0
TAIL_R = 120.0
IOB_R = 207.0
TIME_Y, DATE_Y, HERO_Y, META_Y, SLOT_Y, PILL_Y, SEC_Y = 136, 165, 226, 300, 340, 372, 404
SIDE_X = 393
BTN_X, BTN_Y, BTN_R, BTN_HIT = 46, 225, 27, 44


def ang(mgdl):
    f = min(max((mgdl - SMIN) / (SMAX - SMIN), 0.0), 1.0)
    return A0 + f * SWEEP


def a(c, al):  # rgb + alpha
    return tuple(c[:3]) + (int(al),)


def mix(c1, c2, t):
    return tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))


# ------------------------------------------------------------------ model defaults
def model(**kw):
    M = dict(
        units="mmol", sgv=6.4 * MG, sgv_str="6,4", level=0, delta="+0,1", trend="FLAT", bg_age=1,
        low=3.9 * MG, high=10.0 * MG, tail=(6.2 * MG,), first_start=False,
        time="14:07", ampm=None, date="Fr 17. Okt", week=None, seconds=None,
        iob="1,25", cob="0 g", basal="0,85 U/h", tt="", tt_level=0,
        res="120 U", res_level=0, batt=64, batt_level=1, rig=None,
        loop_age=2, loop_mode="closed", loop_run="running", no_aps=False, flavour="full",
        phone="ok",
        show=dict(date=True, week=False, seconds=False, delta=True, direction=True, ago=True, iob=True, cob=True,
                  basal=True, tt=True, res=False, batt=False, rig=False, avg_delta=False, detailed_delta=False),
        ball="off", amount="0,5", armed_left=1.0, toast=None, progress=None, max_bolus=False,
        mode="interactive", burn_offset=(0, 0),
        secondaries=[], name="", switched=False,
        shape=("round", 450), simplified=False, preview=False, lang="de", caption=None,
    )
    show = kw.pop("show", None)
    M.update(kw)
    if show:
        M["show"] = dict(M["show"], **show)
    return M


# ------------------------------------------------------------------ FaceState (python mirror of the shared helper)
FALLING = ("DOWN45", "DOWN", "DOWN2", "DOWN3")


def age_class(m):
    if m is None: return "NONE"
    if m < 6: return "FRESH"
    if m < 12: return "AGING"
    if m < 30: return "STALE"
    return "VERY_STALE"


def fmt_age(m, lang="de"):
    if m is None: return "--"
    if m < 60: return f"{m}′"
    if m < 48 * 60: return f"{m // 60} Std" if lang == "de" else f"{m // 60} h"
    return f"{m // 1440} T" if lang == "de" else f"{m // 1440} d"


def classify(sgv, level, trend, age):
    """bgRange of one dataset (shared by hero and secondary blocks)."""
    ac = age_class(age)
    if ac == "NONE": return "NONE"
    if ac in ("STALE", "VERY_STALE"): return "STALE"
    if sgv < 54 or (level == -1 and trend in ("DOWN2", "DOWN3")): return "URGENT_LOW"
    if level == -1: return "LOW"
    if sgv > 250: return "VERY_HIGH"
    if level == 1: return "HIGH"
    return "IN"


def bg_range(M):
    return classify(M["sgv"], M["level"], M["trend"], M["bg_age"])


def sec_sgv(s):
    if "sgv" in s: return s["sgv"]
    try: return float(s["sgv_str"].replace(",", ".")) * MG
    except Exception: return 100.0


def sec_range(s):
    return classify(sec_sgv(s), s["level"], s.get("trend", "FLAT"), s["age"])


def range_col(r):
    return {"IN": IN, "HIGH": HIGH, "VERY_HIGH": HIGH, "LOW": LOW, "URGENT_LOW": LOW}.get(r, STALE)


def is_low(r): return r in ("LOW", "URGENT_LOW")


def loop_health(M):
    """OK <= 5', WARN 6-14', LATE 15-29' (amber, W6 in warn: loop age is TBR age, see gaps), BAD >= 30' (alert)."""
    if M["no_aps"]: return "NOAPS"
    if M["loop_run"] == "noprofile": return "NEUTRAL"
    if M["loop_run"] == "off": return "OFF"
    if M["loop_age"] is None: return "NEVER"
    if M["loop_mode"] == "open" or M["flavour"] == "pumpcontrol": return "OPEN" if M["loop_mode"] == "open" else "NEUTRAL"
    m = M["loop_age"]
    return "OK" if m <= 5 else "WARN" if m <= 14 else "LATE" if m <= 29 else "BAD"


def ellip(s, n=7):
    return s if len(s) <= n else s[:n] + "…"


def escalation(M):
    """Secondary dataset escalated into the face status line (rank 3.5, just below W1-W3)."""
    de = M["lang"] == "de"
    for s in M["secondaries"]:
        nm = ellip(s["name"] or s.get("fallback", "P2"))
        r = sec_range(s)
        if r == "URGENT_LOW":
            return f"{nm} {'TIEF' if de else 'LOW'} {s['sgv_str']}"
        if (s["age"] or 0) >= 30:
            return f"{nm} · {'Keine Daten' if de else 'No data'} · {fmt_age(s['age'], M['lang'])}"
    return None


def status_slot(M, ambient):
    """Warning hierarchy (states.md 4.2 + Halo v2 deltas). Returns (rank, text, colour, glyph)."""
    de = M["lang"] == "de"
    ac = age_class(M["bg_age"])
    age = fmt_age(M["bg_age"], M["lang"])
    if M["first_start"]:
        return 1, ("Warte auf AAPS" if de else "Waiting for AAPS"), TXT2, None
    if M["phone"] == "lost_stale":
        return 2, (f"Telefon getrennt · {age}" if de else f"Phone disconnected · {age}"), ALERT, "phone"
    if M["phone"] == "silent" or ac in ("STALE", "VERY_STALE"):
        return 3, (f"Keine Daten · {age}" if de else f"No data · {age}"), ALERT, None
    if ac == "NONE":
        return 3.1, ("Kein BG" if de else "No BG"), WARN, None
    esc = escalation(M)
    if esc:
        return 3.5, esc, ALERT, None
    if M["loop_run"] == "noprofile" and not M["switched"]:
        return 4, ("Kein Profil" if de else "No profile"), WARN, None
    if M["loop_run"] == "off" and not M["switched"]:
        return 5, ("Loop aus" if de else "Loop off"), WARN, "pause"
    h = loop_health(M)
    if h in ("LATE", "BAD") and not M["switched"]:
        return 6, f"Loop {fmt_age(M['loop_age'], M['lang'])}", (ALERT if h == "BAD" else WARN), None
    if ambient:
        return 0, None, None, None
    if is_low(bg_range(M)):
        return 0, None, None, None  # a fresh low suppresses W7..W9 (W10 is a glyph, not slot text)
    if M["switched"]:
        return 0, None, None, None
    if M["res_level"] == 2 and M["res"] not in ("", "--"):
        return 7, f"Reservoir {M['res']}", ALERT, None
    if M["batt_level"] == 0 and M["batt"] not in (0, None):
        return 8, (f"Tel {M['batt']} %" if de else f"Phone {M['batt']} %"), (ALERT if M["batt"] < 10 else WARN), None
    if M["res_level"] == 1 and M["res"] not in ("", "--"):
        return 9, f"Reservoir {M['res']}", WARN, None
    return 0, None, None, None


def ball_guard(M):
    """BolusRequestGuard verdict for an otherwise idle ball (family v2: + LOW_BG, NO_PROFILE, LOOP_OFF)."""
    if M["phone"] in ("lost_fresh", "lost_stale", "silent"): return "NO_PHONE"
    if age_class(M["bg_age"]) in ("STALE", "VERY_STALE", "NONE"): return "STALE_DATA"
    r = bg_range(M)
    if r == "URGENT_LOW" or (r == "LOW" and M["trend"] in FALLING): return "LOW_BG"
    if M["loop_run"] == "noprofile": return "NO_PROFILE"
    if M["loop_run"] == "off": return "LOOP_OFF"
    if M["max_bolus"]: return "MAX_BOLUS"
    return None


GUARD_TOAST = {
    "NO_PHONE": "Telefon nicht verbunden.\nKeine Anfrage gesendet.",
    "STALE_DATA": "Keine aktuellen Daten.\nKeine Anfrage gesendet.",
    "LOW_BG": "BG zu niedrig.\nKeine Anfrage gesendet.",
    "NO_PROFILE": "Kein Profil.\nKeine Anfrage gesendet.",
    "LOOP_OFF": "Loop aus.\nKeine Anfrage gesendet.",
    "MAX_BOLUS": "1,0 U liegt über dem\nMax-Bolus (0,8 U)",
}


# ------------------------------------------------------------------ drawing context
class Ctx:
    def __init__(self, M):
        self.M = M
        kind = M["shape"]
        if kind[0] == "round":
            self.w = self.h = kind[1]; self.d = kind[1]; self.oy = 0; self.chin = 0
        elif kind[0] == "square":
            self.w, self.h = kind[1], kind[2] if len(kind) > 2 else kind[1]
            self.d = min(self.w, self.h); self.oy = (self.h - self.d) / 2; self.chin = 0
        else:  # flat tyre: ("flat", w, visible_h)
            self.w, self.h = kind[1], kind[1]; self.d = kind[1]; self.oy = 0; self.chin = kind[1] - kind[2]
        self.ox = (self.w - self.d) / 2
        mode = M["mode"]
        self.amb = mode != "interactive"
        self.lowbit = mode in ("lowbit", "lowbit_burnin")
        self.burn = mode in ("burnin", "lowbit_burnin")
        self.k = self.d / 450.0 * SS
        self.s = self.d / 450.0
        self.shift = M["burn_offset"] if self.burn else (0, 0)
        # RGB canvas + an "RGBA" draw = real alpha blending for every translucent fill/stroke
        self.img = Image.new("RGB", (int(self.w * SS), int(self.h * SS)), (0, 0, 0))
        self.dr = ImageDraw.Draw(self.img, "RGBA")

    def composite(self, *layers):
        t = self.img.convert("RGBA")
        for l in layers: t.alpha_composite(l)
        self.img = t.convert("RGB")
        self.dr = ImageDraw.Draw(self.img, "RGBA")

    # chin: design y beyond which content is lifted
    def lift(self, y):
        # flat tyre: the bottom stack (slot, pill, runs) is re-spaced so its last row ends 6 px above the chin
        if not self.chin or y < SLOT_Y: return y
        limit = (self.h - self.chin - 8) / self.s  # 8 px safe band above the chin
        top, last = SLOT_Y - 6, min(SEC_Y, limit - 10)
        return top + (y - SLOT_Y) * (last - top) / (SEC_Y - SLOT_Y)

    def P(self, x, y):
        return ((self.ox + (x + self.shift[0]) * self.s) * SS, (self.oy + (y + self.shift[1]) * self.s) * SS)

    def L(self, v):  # length
        return v * self.k

    def font(self, size, weight="Medium"):
        px = size * self.s
        px = max(px, 11.0) if size >= 10 else px  # physical text floor (states.md 4.7)
        return _font(int(round(px * SS)), weight)

    def text(self, x, y, s, size, weight, fill, anchor="mm", stroke=None, alpha=255, halo=0):
        f = self.font(size, weight)
        if halo:  # black knock-out around the glyphs (text over a line)
            self.dr.text(self.P(x, y), s, font=f, fill=a(fill, alpha), anchor=anchor,
                         stroke_width=max(1, int(halo * self.k)), stroke_fill=(0, 0, 0, 255))
            return
        if stroke:
            self.dr.text(self.P(x, y), s, font=f, fill=(0, 0, 0, 255), anchor=anchor,
                         stroke_width=max(1, int(stroke * self.k)), stroke_fill=a(fill, alpha))
        else:
            self.dr.text(self.P(x, y), s, font=f, fill=a(fill, alpha), anchor=anchor)

    def tw(self, s, size, weight="Medium"):
        f = self.font(size, weight)
        return f.getlength(s) / self.k

    def fit(self, s, size, weight, maxw, minsize=None):
        w = self.tw(s, size, weight)
        if w <= maxw: return size
        ns = size * maxw / w
        return max(ns, minsize) if minsize else ns

    def bbox(self, r, cx=CX, cy=CY):
        x0, y0 = self.P(cx - r, cy - r); x1, y1 = self.P(cx + r, cy + r)
        return (x0, y0, x1, y1)

    def arc(self, r, a0, a1, col, w, alpha=255, cap=True, dr=None, cx=CX, cy=CY):
        dr = dr or self.dr
        if a1 < a0: a0, a1 = a1, a0
        if a1 - a0 < 0.2: return
        wpx = max(1, int(w * self.k))
        dr.arc(self.bbox(r + w / 2, cx, cy), a0, a1, fill=a(col, alpha), width=wpx)
        if cap:
            for t in (a0, a1):
                x, y = cx + r * math.cos(math.radians(t)), cy + r * math.sin(math.radians(t))
                self.circle(x, y, w / 2, fill=col, alpha=alpha, dr=dr)

    def circle(self, x, y, r, fill=None, outline=None, width=1.5, alpha=255, dr=None):
        dr = dr or self.dr
        x0, y0 = self.P(x - r, y - r); x1, y1 = self.P(x + r, y + r)
        dr.ellipse((x0, y0, x1, y1), fill=a(fill, alpha) if fill else None,
                   outline=a(outline, alpha) if outline else None, width=max(1, int(width * self.k)) if outline else 0)

    def line(self, pts, col, w, alpha=255, dr=None):
        dr = dr or self.dr
        dr.line([self.P(*p) for p in pts], fill=a(col, alpha), width=max(1, int(w * self.k)), joint="curve")

    def rrect(self, x0, y0, x1, y1, r, fill=None, outline=None, width=1, alpha=255, dr=None):
        dr = dr or self.dr
        p0 = self.P(x0, y0); p1 = self.P(x1, y1)
        dr.rounded_rectangle((p0[0], p0[1], p1[0], p1[1]), radius=r * self.k, fill=a(fill, alpha) if fill else None,
                             outline=a(outline, alpha) if outline else None,
                             width=max(1, int(width * self.k)) if outline else 0)

    def glow(self, fn, radius):
        if self.amb: fn(self.dr); return
        layer = Image.new("RGBA", self.img.size, (0, 0, 0, 0))
        fn(ImageDraw.Draw(layer))
        g = layer.filter(ImageFilter.GaussianBlur(radius * self.k))
        self.composite(g, layer)


@lru_cache(maxsize=256)
def _font(px, weight):
    f = ImageFont.truetype(ROUND, max(px, 4))
    try: f.set_variation_by_name(weight)
    except Exception: pass
    return f


# ------------------------------------------------------------------ glyphs
TREND_DEG = {"UP": -90, "UP45": -45, "FLAT": 0, "DOWN45": 45, "DOWN": 90, "UP2": -90, "DOWN2": 90, "UP3": -90, "DOWN3": 90}


def arrow_glyph(c, x, y, trend, col, size=22, w=3.2, alpha=255):
    """Face-own arrow (no system arrow font). Double head for fast trends, thicker for triple."""
    if trend not in TREND_DEG: return 0
    th = math.radians(TREND_DEG[trend])
    if trend.endswith("3"): w += 1.0
    dx, dy = math.cos(th), math.sin(th)
    L = size * 0.42
    tail = (x - dx * L, y - dy * L); head = (x + dx * L, y + dy * L)
    c.line([tail, head], col, w, alpha)
    heads = [0, 7] if trend[-1] in "23" else [0]
    for off in heads:
        hx_, hy_ = head[0] - dx * off, head[1] - dy * off
        for s in (1, -1):
            ax = math.radians(TREND_DEG[trend] + 180 + s * 42)
            c.line([(hx_, hy_), (hx_ + math.cos(ax) * size * 0.34, hy_ + math.sin(ax) * size * 0.34)], col, w, alpha)
    return size


def phone_off_glyph(c, x, y, col, size=13):
    w, h = size * 0.55, size
    c.rrect(x - w / 2, y - h / 2, x + w / 2, y + h / 2, 2, outline=col, width=1.4)
    c.line([(x - w / 2 - 2, y + h / 2 + 1), (x + w / 2 + 2, y - h / 2 - 1)], col, 1.6)


def pause_glyph(c, x, y, col, size=10):
    for dx in (-2.4, 2.4):
        c.line([(x + dx, y - size / 2), (x + dx, y + size / 2)], col, 2.0)


# ------------------------------------------------------------------ face
def render(M):
    c = Ctx(M)
    if M["simplified"]:
        draw_simplified(c)
    else:
        if c.amb: draw_ambient(c)
        else: draw_interactive(c)
    out = c.img.convert("RGB").resize((c.w, c.h), Image.LANCZOS)
    if c.lowbit:
        out = out.convert("L").point(lambda v: 255 if v > 90 else 0).convert("RGB")
    # mask to the physical screen
    mask = Image.new("L", (c.w, c.h), 0); md = ImageDraw.Draw(mask)
    if M["shape"][0] == "round": md.ellipse((0, 0, c.w - 1, c.h - 1), fill=255)
    elif M["shape"][0] == "flat":
        md.ellipse((0, 0, c.w - 1, c.h - 1), fill=255); md.rectangle((0, M["shape"][2], c.w, c.h), fill=0)
    else: md.rectangle((0, 0, c.w, c.h), fill=255)
    final = Image.new("RGB", (c.w, c.h), (0, 0, 0)); final.paste(out, (0, 0), mask)
    return final


def hero_value(M):
    ac = age_class(M["bg_age"])
    if ac == "NONE" or M["first_start"]: return "---", False
    if ac == "VERY_STALE" and M["bg_age"] >= 60: return "---", False
    s = M["sgv_str"]
    if M["sgv"] >= 400: s = s + "+"
    return s, ac in ("STALE", "VERY_STALE")


def hero_caption(M, rng, val, struck):
    """Non-colour BG word next to the digits: low -> 'TIEF' word, urgent low -> filled 'TIEF' badge, sentinel high -> 'HOCH'."""
    if val == "---" or struck: return None
    de = M["lang"] == "de"
    if rng == "URGENT_LOW": return ("TIEF" if de else "LOW"), "badge"
    if rng == "LOW": return ("TIEF" if de else "LOW"), "word"
    if M["sgv"] >= 400: return ("HOCH" if de else "HIGH"), "word"
    return None


CAP_SIZE = 16


def caption_w(c, cap):
    if not cap: return 0
    return c.tw(cap[0], CAP_SIZE, "Bold") + (16 if cap[1] == "badge" else 0) + 8


def draw_caption(c, x, y, cap, col, amb):
    """x = left edge, y = centre."""
    txt, kind = cap
    tw_ = c.tw(txt, CAP_SIZE, "Bold")
    if kind == "badge":
        if amb:  # burn-in safe: outlined box, never a filled area
            c.rrect(x, y - 13, x + tw_ + 16, y + 13, 7, outline=(WHITE if c.lowbit else AMB1), width=2)
            c.text(x + 8, y, txt, CAP_SIZE, "Bold", (WHITE if c.lowbit else AMB1), anchor="lm")
        else:
            c.rrect(x, y - 13, x + tw_ + 16, y + 13, 7, fill=LOW)
            c.text(x + 8, y, txt, CAP_SIZE, "Bold", WHITE, anchor="lm")
    else:
        c.text(x, y, txt, CAP_SIZE, "Bold", ((WHITE if c.lowbit else AMB1) if amb else col), anchor="lm")


def hero_block(c, M, rng, val, struck, base_size, maxw_plain, maxw_group):
    cap = hero_caption(M, rng, val, struck)
    cw = caption_w(c, cap)
    size = min(base_size, c.fit(val, base_size, "Semibold", (maxw_group - cw) if cap else maxw_plain))
    w = c.tw(val, size, "Semibold")
    xc = CX - cw / 2  # digits + caption are centred as one group
    return cap, size, w, xc


def draw_ring(c, M, rng, rc):
    ac = age_class(M["bg_age"])
    has = ac != "NONE" and not M["first_start"]
    very = ac == "VERY_STALE" and M["bg_age"] >= 60
    stale = rng == "STALE"
    emph = rng in ("VERY_HIGH", "URGENT_LOW")
    lowA, highA = ang(M["low"]), ang(M["high"])
    if has and not stale:
        layer = Image.new("RGBA", c.img.size, (0, 0, 0, 0)); ld = ImageDraw.Draw(layer)
        for i in range(30, 0, -1):
            r = 147 * i / 30
            c.circle(CX, CY, r, fill=rc, alpha=int((30 if emph else 22) * (1 - i / 30) ** 1.4), dr=ld)
        c.composite(layer.filter(ImageFilter.GaussianBlur(8 * c.k)))
    c.arc(RING_R, A0, A0 + SWEEP, TRACK, RING_W_EMPH if emph else RING_W)
    c.arc(RING_R, lowA, highA, BAND, RING_W, cap=False)
    if has and not very:
        va = ang(M["sgv"])
        wid = RING_W_EMPH if emph else RING_W
        if stale:
            c.arc(RING_R, A0, va, STALE, RING_W, alpha=110)
        else:
            def fill(dr):
                n = 60
                for i in range(n):
                    t0 = A0 + (va - A0) * i / n; t1 = A0 + (va - A0) * (i + 1.25) / n
                    al = int(255 * (0.22 + 0.78 * (i / n) ** 0.9))
                    c.arc(RING_R, t0, t1, rc, wid, alpha=al, cap=False, dr=dr)
                c.circle(CX + RING_R * math.cos(math.radians(A0)), CY + RING_R * math.sin(math.radians(A0)), wid / 2,
                         fill=rc, alpha=56, dr=dr)
            c.glow(fill, 6)
    for t in (lowA, highA):
        p = lambda r: (CX + r * math.cos(math.radians(t)), CY + r * math.sin(math.radians(t)))
        c.line([p(RING_R - 10), p(RING_R + 10)], BLACK, 3)
    if has and not very:
        va = ang(M["sgv"])
        if not stale and M["show"]["direction"] and M["tail"]:
            sa = ang(M["tail"][0])
            if abs(va - sa) > 1:
                n = 24
                for i in range(n):
                    t0 = sa + (va - sa) * i / n; t1 = sa + (va - sa) * (i + 1) / n
                    c.arc(TAIL_R, t0, t1, hx("AAB4C3"), 3, alpha=30 + 225 * (i + 1) / n, cap=False)
        if not stale and M["show"]["direction"] and M["trend"] in ("UP2", "DOWN2", "UP3", "DOWN3"):
            sgn = 1 if M["trend"].startswith("UP") else -1
            wch = 3.2 if M["trend"].endswith("3") else 2.4
            for off in (17, 24):
                t = va + sgn * off
                if not (A0 + 2 < t < A0 + SWEEP - 2): continue
                px_, py_ = CX + RING_R * math.cos(math.radians(t)), CY + RING_R * math.sin(math.radians(t))
                tang = math.radians(t + 90 * sgn)
                for s in (1, -1):
                    bx = math.radians(math.degrees(tang) + 180 + s * 50)
                    c.line([(px_, py_), (px_ + 6 * math.cos(bx), py_ + 6 * math.sin(bx))], rc, wch)
        kx, ky = CX + RING_R * math.cos(math.radians(va)), CY + RING_R * math.sin(math.radians(va))
        if stale:
            c.circle(kx, ky, 10, fill=hx("2A2E36")); c.circle(kx, ky, 10, outline=STALE, width=2); c.circle(kx, ky, 4, fill=STALE)
        else:
            c.glow(lambda dr: (c.circle(kx, ky, 12, fill=WHITE, dr=dr), c.circle(kx, ky, 6, fill=rc, dr=dr)), 5)


def draw_time(c, M, amb=False):
    col = AMB1 if amb else TXT1
    dcol = AMB2 if amb else TXT2
    date_on = M["show"]["date"] or M["switched"]
    ty = TIME_Y if date_on else 150
    t = M["time"]
    f = 30
    if M["ampm"] or (M["show"]["seconds"] and not amb and M["seconds"]):
        w = c.tw(t, f, "Medium")
        extra = M["ampm"] if M["ampm"] else ":" + M["seconds"]
        we = c.tw(extra, 12, "Semibold")
        x0 = CX - (w + 3 + we) / 2
        c.text(x0, ty, t, f, "Regular" if amb else "Medium", col, anchor="lm")
        c.text(x0 + w + 3, ty + 6, extra, 12, "Semibold", dcol, anchor="lm")
    else:
        c.text(CX, ty, t, f, "Regular" if amb else "Medium", col)
    if M["switched"]:
        # multi.switched: the hero patient is a header chip, the date steps aside
        nm = M["name"] or "P2"
        nm = nm if c.tw(nm, 17, "Bold") <= 150 else ellip(nm, 10)
        w = c.tw(nm, 17, "Bold") + 34
        if amb:
            c.rrect(CX - w / 2, DATE_Y - 14, CX + w / 2, DATE_Y + 14, 14, outline=AMB2, width=1.5)
        else:
            c.rrect(CX - w / 2, DATE_Y - 14, CX + w / 2, DATE_Y + 14, 14, fill=hx("262B36"))
            c.rrect(CX - w / 2, DATE_Y - 14, CX + w / 2, DATE_Y + 14, 14, outline=TXT2, width=1.2)
        c.circle(CX - w / 2 + 13, DATE_Y, 4, fill=(AMB1 if amb else TXT1))
        c.text(CX - w / 2 + 24, DATE_Y, nm, 17, "Bold", (AMB1 if amb else TXT1), anchor="lm")
        return
    if date_on:
        dtxt = M["date"]
        if M["show"]["week"] and M["week"]: dtxt += " · " + M["week"]
        if M["name"] and M["secondaries"]:
            nm = M["name"]
            while len(nm) > 3 and c.tw(nm + " · " + dtxt, 15, "Medium") > 220:  # primary name ellipsised to the row width
                nm = nm[:-2] + "…" if not nm.endswith("…") else nm[:-2] + "…"
            dtxt = nm + " · " + dtxt
        c.text(CX, DATE_Y, dtxt, 15, "Medium", dcol)


def draw_hero(c, M, rng, amb=False):
    val, struck = hero_value(M)
    cap, size, w, xc = hero_block(c, M, rng, val, struck, 104, 196, 220)
    urgent = rng == "URGENT_LOW"
    y = HERO_Y + 4
    if amb:
        sw = (1.5 if c.burn else 2.0) + (1.0 if urgent else 0)
        col = AMB1 if not struck else AMB2
        if c.lowbit: col = WHITE
        c.text(xc, y, val, size, "Light", col, stroke=sw)
    else:
        if val == "---":
            col = TXT3 if M["first_start"] or age_class(M["bg_age"]) == "NONE" else TXT2
            c.text(xc, y, val, size, "Semibold", col)
        elif struck:
            c.text(xc, y, val, size, "Semibold", TXT2)
        else:
            tc = LOW if is_low(rng) else WHITE  # a fresh low tints the digits range.low (plus the TIEF word)
            gc = range_col(rng)
            c.glow(lambda dr: dr.text(c.P(xc, y), val, font=c.font(size, "Semibold"), fill=a(gc, 120), anchor="mm"), 9)
            c.text(xc, y, val, size, "Semibold", tc)
    if struck:
        half = w / 2 + 6
        col = (AMB2 if not c.lowbit else WHITE) if amb else TXT2
        c.line([(xc - half, HERO_Y + 2), (xc + half, HERO_Y + 2)], col, 2 if amb else 3)
    if cap:
        draw_caption(c, xc + w / 2 + 8, HERO_Y - 26, cap, range_col(rng), amb)


def draw_meta(c, M, rng, slot_rank, amb=False):
    if slot_rank in (2, 3):  # the slot carries the age, the meta line steps back
        return
    ac = age_class(M["bg_age"])
    stale = rng in ("STALE",) or ac == "NONE"
    sh = M["show"]
    parts = []  # (kind, text, colour, size)
    if sh["direction"] and not stale and M["trend"] in TREND_DEG:
        parts.append(("arrow", M["trend"], (AMB1 if amb else range_col(rng)), 22))
    if sh["delta"] and not stale:
        d = M["delta"]
        parts.append(("text", d if d not in ("", "--", None) else "--", (AMB2 if amb else (TXT3 if d in ("--", "", None) else hx("B4BAC4"))), 22))
    if ac != "NONE" and not M["first_start"]:
        # the age is never optional on Halo: key_show_ago off only demotes it while fresh
        small = not sh["ago"] and ac == "FRESH"
        col = {"FRESH": (TXT3 if small else hx("B4BAC4")), "AGING": WARN, "STALE": ALERT, "VERY_STALE": ALERT}[ac]
        if amb: col = AMB2 if ac == "FRESH" else AMB1
        if parts and parts[-1][0] == "text": parts.append(("sep", " · ", AMB3 if amb else TXT3, 22))
        elif parts: parts.append(("gap", "", None, 22))
        parts.append(("text", fmt_age(M["bg_age"], M["lang"]), col, 16 if small else 22))
    if M["phone"] == "lost_fresh" and not amb:  # W10: glyph only, never suppressed by a low
        parts.append(("phone", "", TXT2, 22))
    if not parts: return
    ws = []
    for k, t, col, sz in parts:
        if k == "arrow": ws.append(24)
        elif k == "gap": ws.append(10)
        elif k == "phone": ws.append(22)
        else: ws.append(c.tw(t, sz, "Medium"))
    if parts[0][0] == "arrow" and len(parts) > 1: ws[0] += 10
    x = CX - sum(ws) / 2
    for (k, t, col, sz), w in zip(parts, ws):
        if k == "arrow":
            arrow_glyph(c, x + 12, META_Y, t, col, 24, 3.0 if not amb else 2.0)
        elif k == "phone":
            phone_off_glyph(c, x + 13, META_Y, col, 15)
        elif k in ("text", "sep"):
            c.text(x, META_Y, t, sz, "Medium", col, anchor="lm")
        x += w


def draw_slot(c, M, slot, amb=False):
    rank, txt, col, glyph = slot
    if not txt: return
    y = c.lift(SLOT_Y)
    if amb: col = AMB1 if rank in (2, 3, 3.5, 6) else AMB2
    if c.lowbit: col = WHITE
    maxw = 210 if M["shape"][0] == "round" else 300
    size = c.fit(txt, 17, "Semibold", maxw - (18 if glyph else 0), 14)
    w = c.tw(txt, size, "Semibold") + (18 if glyph else 0)
    x = CX - w / 2
    if glyph == "phone":
        phone_off_glyph(c, x + 6, y, col, 13); x += 18
    elif glyph == "pause":
        pause_glyph(c, x + 5, y, col, 11); x += 18
    c.text(x, y, txt, size, "Semibold", col, anchor="lm")


def draw_loop_pill(c, M, slot_rank, amb=False, compact=False):
    h = loop_health(M)
    if h == "NOAPS" or M["switched"]: return
    y = c.lift(PILL_Y)
    de = M["lang"] == "de"
    lbl, dot, txtc, bgc, olc, hollow = "", LOOPOK, hx("D2F5E1"), hx("12281E"), hx("285A40"), False
    age = fmt_age(M["loop_age"], M["lang"])
    if h == "OK": lbl = age
    elif h in ("WARN", "LATE"): lbl, dot, txtc, bgc, olc = age, WARN, hx("FFE6AA"), hx("2A2210"), hx("5A4A20")
    elif h == "BAD": lbl, dot, txtc, bgc, olc = age, ALERT, hx("FFD2D8"), hx("2E1218"), hx("6A2632")
    elif h in ("NEVER", "NEUTRAL"): lbl, dot, txtc, bgc, olc, hollow = "--", TXT3, TXT3, None, hx("2C313C"), True
    elif h == "OPEN": lbl, dot, txtc, bgc, olc, hollow = ("Offen" if de else "Open"), LOOPOK, TXT2, None, hx("285A40"), True
    elif h == "OFF": lbl, dot, txtc, bgc, olc = ("aus" if de else "off"), WARN, WARN, None, hx("5A4A20")
    if M["loop_mode"] == "lgs" and h in ("OK", "WARN", "LATE", "BAD"): lbl = "LGS " + lbl
    if compact and lbl.startswith("LGS "): lbl = lbl[4:]
    # red budget: the slot already says W2/W3/W6 -> the pill is a neutral grey outline, number in txt2
    if slot_rank in (2, 3, 6) and h in ("OK", "WARN", "LATE", "BAD"):
        dot, txtc, bgc, olc, hollow = TXT3, TXT2, None, NEUTRAL_OL, True
    lead = 22 if h == "OFF" else 26
    tw_ = c.tw(lbl, 17, "Semibold") if lbl else 0
    pw = max(56 if compact else 74, lead + tw_ + 16) if lbl else 40
    x0, x1 = CX - pw / 2, CX + pw / 2
    tx = x0 + lead + (pw - lead - 12 - tw_) / 2
    if amb:
        c.rrect(x0, y - 14, x1, y + 14, 14, outline=AMB3 if not c.lowbit else WHITE, width=1.5)
        dcol = AMB1 if not c.lowbit else WHITE
        if h == "OFF": pause_glyph(c, x0 + 15, y, dcol, 10)
        else: c.circle(x0 + 16, y, 4, outline=dcol, width=1.5)
        if lbl: c.text(tx, y, lbl, 17, "Semibold", dcol, anchor="lm")
        return
    if bgc: c.rrect(x0, y - 14, x1, y + 14, 14, fill=bgc)
    c.rrect(x0, y - 14, x1, y + 14, 14, outline=olc, width=1.2)
    if h == "OFF":
        pause_glyph(c, x0 + 15, y, WARN, 10)
    elif hollow:
        c.circle(x0 + 16, y, 4.5, outline=dot, width=1.6)
    else:
        c.glow(lambda dr: c.circle(x0 + 16, y, 5, fill=dot, dr=dr), 3)
    if lbl: c.text(tx, y, lbl, 17, "Semibold", txtc, anchor="lm")


def draw_iob(c, M, amb=False):
    sh = M["show"]
    if M["switched"]: return  # dataset-0 only
    if not sh["iob"] and not sh["cob"]: return
    def disp(s):
        s = (s or "").strip()
        return "--" if s in ("", "IOB", "-.--", "--g", "-- g", "--", "-.--U/h") else s
    iob = disp(M["iob"])
    try: v = float(iob.replace(",", ".").replace("U", ""))
    except Exception: v = None
    a1 = 38 if M["secondaries"] else 50  # >= 12 px clearance to the patient blocks
    labc = AMB3 if amb else INS
    if sh["iob"]:
        frac = 0 if (v is None or v <= 0) else min(v / 6.0, 1.0)
        if amb:
            if not c.lowbit: c.arc(IOB_R, -50, a1, AMBL, 1, cap=False)
            if frac: c.arc(IOB_R, a1 - (a1 + 50) * frac, a1, AMB2 if not c.lowbit else WHITE, 2, cap=False)
        else:
            c.arc(IOB_R, -50, a1, IOBTRACK, 5)
            if frac: c.glow(lambda dr: c.arc(IOB_R, a1 - (a1 + 50) * frac, a1, INS, 5, dr=dr), 3)
        iy, vy = (194, 215) if sh["cob"] else (210, 232)
        c.text(SIDE_X, iy, "IOB", 13, "Semibold", labc)
        if amb: vc = AMB2
        elif iob == "--" or v == 0: vc = TXT3
        else: vc = TXT1  # family rule: only label + arc carry ins, the value is txt1
        c.text(SIDE_X, vy, iob, c.fit(iob, 22, "Medium", 50), "Medium", vc)
    if sh["cob"] and not amb:
        cob = disp(M["cob"])
        ly, vy = (245, 265) if sh["iob"] else (212, 233)
        c.text(SIDE_X, ly, "KH" if M["lang"] == "de" else "COB", 13, "Semibold", TXT3)
        vc = TXT3 if cob in ("--", "0 g", "0g") else TXT1
        c.text(SIDE_X, vy, cob, c.fit(cob, 19, "Medium", 50), "Medium", vc)


def secondary_runs(M, slot_rank, amb=False):
    sh, runs = M["show"], []
    if M["switched"]: return runs  # dataset-0 only
    de = M["lang"] == "de"
    if sh["tt"] and M["tt_level"] == 2 and M["tt"] not in ("", "--"):
        runs.append((f"TT {M['tt']}", TT))
    if amb: return runs
    if sh["basal"] and M["basal"] not in ("", "--", "-.--U/h", "-.-- U/h"):
        b = M["basal"]
        col = TBR if b.endswith("%") else TXT2
        if b.startswith("0,00") or b.startswith("0.00") or b in ("0%", "0 %"):
            col = TXT2 if is_low(bg_range(M)) else WARN  # zero basal during a low is the loop doing its job
        runs.append((b, col))
    if sh["res"] and M["res"] not in ("", "--") and slot_rank not in (7, 9):
        runs.append((M["res"], {0: TXT2, 1: WARN, 2: ALERT}[M["res_level"]]))
    if sh["batt"] and M["batt"] and slot_rank != 8:
        runs.append(((f"Tel {M['batt']} %" if de else f"Phone {M['batt']} %"), WARN if M["batt_level"] == 0 else TXT2))
    if sh["rig"] and M["rig"]:
        runs.append((f"Rig {M['rig']}", TXT2))
    return runs


def draw_secondary(c, M, slot_rank, amb=False):
    runs = secondary_runs(M, slot_rank, amb)
    if not runs: return
    y = c.lift(SEC_Y)
    maxw = (200 if c.chin else 220) if M["shape"][0] in ("round", "flat") else 320
    sep = "  ·  "
    # drop order when it does not fit at >= 12 px: rig, phone battery, reservoir, basal; TT is kept last
    while len(runs) > 1 and c.fit(sep.join(r[0] for r in runs), 14, "Medium", maxw) < 12:
        runs = runs[:-1]
    size = c.fit(sep.join(r[0] for r in runs), 14, "Medium", maxw)
    total = c.tw(sep.join(r[0] for r in runs), size, "Medium")
    x = CX - total / 2
    for i, (t, col) in enumerate(runs):
        if i:
            c.text(x, y, sep, size, "Medium", AMB3 if amb else TXT3, anchor="lm"); x += c.tw(sep, size, "Medium")
        c.text(x, y, t, size, "Medium", (AMB2 if amb else col), anchor="lm"); x += c.tw(t, size, "Medium")


def draw_ball(c, M):
    """Returns the guard verdict (or None)."""
    st = M["ball"]
    if M["first_start"] and st != "off": st = "hidden"
    verdict = None
    if st == "idle":
        verdict = ball_guard(M)
        if verdict: st = "disabled"
    if st in ("off", "hidden"): return verdict
    x, y, r = BTN_X, BTN_Y, BTN_R
    amt = "+" + M["amount"]
    unit = "U"
    if st == "disabled":
        # shape, not only alpha: dashed 40 % outline + slash, amount txt3
        n = 20
        for i in range(n):
            t0 = i * 360 / n
            c.arc(r, t0, t0 + 360 / n * 0.55, BALL, 2.2, alpha=102, cap=False, cx=x, cy=y)
        d = r * 0.62
        c.line([(x - d, y + d), (x + d, y - d)], TXT3, 2.2)
        c.text(x, y - 5, amt, 14, "Bold", TXT3, halo=2.5)
        c.text(x, y + 11, unit, 10, "Semibold", TXT3, halo=2)
        return verdict
    if st == "armed":
        c.glow(lambda dr: c.circle(x, y, r, fill=BALLARM, dr=dr), 6)
        c.arc(r + 5, -90, -90 + 360 * M["armed_left"], WHITE, 2.2, cap=True, cx=x, cy=y)
        c.text(x, y - 5, amt, 14, "Bold", WHITE)
        lbl = "nochmal" if M["lang"] == "de" else "again"
        if 2 * r * c.s < 50: lbl = "2×"  # agreed short form below a 50 px physical ball
        c.text(x, y + 11, lbl, 10, "Semibold", WHITE)
        return verdict
    if st in ("sent", "delivering"):
        # solid outline at 55 %, amount replaced by '…', static partial ring (indigo, never white like armed)
        c.circle(x, y, r, fill=hx("141628"), alpha=140)
        c.circle(x, y, r, outline=BALL, width=2.5, alpha=140)
        if st == "delivering" and M["progress"] is not None:
            c.arc(r + 5, -90, -90 + 360 * M["progress"] / 100, BALL, 2.2, cap=True, cx=x, cy=y)
        else:
            c.arc(r + 5, -60, 240, BALL, 2.0, alpha=128, cap=True, cx=x, cy=y)
        c.text(x, y - 3, "…", 22, "Bold", hx("C8CEF5"))
        return verdict
    def body(dr):
        c.circle(x, y, r, fill=hx("141628"), dr=dr)
        c.circle(x, y, r, outline=BALL, width=2.5, dr=dr)
    if st == "idle": c.glow(body, 4)
    else: body(c.dr)
    if st == "delivered":
        c.line([(x - 9, y), (x - 2, y + 7), (x + 10, y - 7)], WHITE, 3)
        return verdict
    c.text(x, y - 5, amt, 14, "Bold", BALL)
    c.text(x, y + 11, unit, 10, "Semibold", hx("96A0DC"))
    return verdict


def draw_toast(c, M, verdict):
    t = M["toast"]
    if t == "guard": t = GUARD_TOAST.get(verdict or "", None)
    if not t: return
    lines = t.split("\n")
    w = max(c.tw(l, 14, "Medium") for l in lines) + 28
    h = 22 * len(lines) + 14
    y1 = 330; y0 = y1 - h
    c.rrect(CX - w / 2, y0, CX + w / 2, y1, 14, fill=hx("2E323C"), alpha=245)
    for i, l in enumerate(lines):
        c.text(CX, y0 + 18 + 22 * i, l, 14, "Medium", TXT1)


BLK_W, BLK_H = 80, 38
BLK_X = (142, 308)


def dashed_rrect(c, x0, y0, x1, y1, col, w):
    step, on = 7, 4
    xs = x0 + 6
    while xs < x1 - 6:
        c.line([(xs, y0), (min(xs + on, x1 - 6), y0)], col, w); c.line([(xs, y1), (min(xs + on, x1 - 6), y1)], col, w); xs += step
    ys = y0 + 6
    while ys < y1 - 6:
        c.line([(x0, ys), (x0, min(ys + on, y1 - 6))], col, w); c.line([(x1, ys), (x1, min(ys + on, y1 - 6))], col, w); ys += step


def draw_secondaries(c, M, amb=False):
    secs = M["secondaries"]
    if not secs: return
    small = c.d < 420
    y = c.lift(PILL_Y)
    de = M["lang"] == "de"
    for sx, s in zip(BLK_X, secs):
        r = sec_range(s)
        ac = age_class(s["age"])
        stale = r in ("STALE", "NONE")
        low = is_low(r)
        rc = range_col(r)
        w, h = BLK_W, BLK_H
        x0, y0, x1, y1 = sx - w / 2, y - h / 2, sx + w / 2, y + h / 2
        lc = WHITE if c.lowbit else AMB1
        if amb:
            if low:  # doubled outline = low (same shape code as the primary ring)
                c.rrect(x0, y0, x1, y1, 10, outline=lc, width=1.5)
                c.rrect(x0 + 3.5, y0 + 3.5, x1 - 3.5, y1 - 3.5, 7, outline=lc, width=1)
            elif r in ("HIGH", "VERY_HIGH"):
                dashed_rrect(c, x0, y0, x1, y1, lc if c.lowbit else AMB2, 1.5)
            else:
                c.rrect(x0, y0, x1, y1, 10, outline=AMB3 if not c.lowbit else WHITE, width=1)
        else:
            c.rrect(x0, y0, x1, y1, 10, fill=hx("0E1016"))
            if r == "URGENT_LOW":
                c.rrect(x0, y0, x1, y1, 10, fill=LOW, alpha=60)
            if low:
                c.rrect(x0, y0, x1, y1, 10, outline=LOW, width=2)
                c.rrect(x0 + 4, y0 + 4, x1 - 4, y1 - 4, 7, outline=LOW, width=1, alpha=150)
            else:
                c.rrect(x0, y0, x1, y1, 10, outline=rc, width=1, alpha=110)
        tag = ("TIEF" if de else "LOW") if low else None
        tagw = (c.tw(tag, 11, "Bold") + (8 if r == "URGENT_LOW" else 0) + 4) if tag else 0
        nm = ellip(s["name"] or s.get("fallback", "P2"))
        avail = w - 14 - tagw - (0 if (small or amb or tag) else 9)
        while len(nm) > 2 and c.tw(nm, 13, "Semibold") > avail:
            nm = nm.rstrip("…")[:-1] + "…"
        c.text(x0 + 7, y0 + 11, nm, 13, "Semibold", AMB2 if amb else TXT2, anchor="lm")
        if tag:
            tcol = lc if amb else (WHITE if r == "URGENT_LOW" else LOW)
            tx1 = x1 - 6
            if r == "URGENT_LOW":
                tw_ = c.tw(tag, 11, "Bold") + 8
                if amb: c.rrect(tx1 - tw_, y0 + 4, tx1, y0 + 18, 4, outline=lc, width=1.2)
                else: c.rrect(tx1 - tw_, y0 + 4, tx1, y0 + 18, 4, fill=LOW)
                c.text(tx1 - tw_ / 2, y0 + 11, tag, 11, "Bold", tcol)
            else:
                c.text(tx1, y0 + 11, tag, 11, "Bold", tcol, anchor="rm")
        elif not small and not amb:
            lcol = {"OK": LOOPOK, "WARN": WARN, "BAD": ALERT}.get(s.get("loop", "OK"), TXT3)
            c.circle(x1 - 9, y0 + 11, 3, fill=lcol)
        val = "---" if (ac == "NONE" or (s["age"] or 0) >= 60) else s["sgv_str"]
        vcol = (AMB1 if amb else (TXT2 if stale else rc))
        if c.lowbit and amb: vcol = WHITE
        c.text(x0 + 7, y0 + 27, val, 16, "Bold", vcol, anchor="lm")
        vw = c.tw(val, 16, "Bold")
        if stale and val != "---":
            c.line([(x0 + 6, y0 + 27), (x0 + 8 + vw, y0 + 27)], AMB2 if amb else TXT2, 1.5)
        ax = x0 + 7 + vw + 8
        if not stale and not amb and s.get("trend") in TREND_DEG:
            arrow_glyph(c, ax + 3, y0 + 27, s["trend"], rc, 11, 1.8)
        acol = {"FRESH": TXT2, "AGING": WARN}.get(ac, ALERT)
        if amb: acol = AMB2 if not c.lowbit else WHITE
        c.text(x1 - 6, y0 + 28, fmt_age(s["age"], M["lang"]), 11, "Semibold", acol, anchor="rm")


def draw_interactive(c):
    M = c.M
    rng = bg_range(M)
    rc = range_col(rng)
    slot = status_slot(M, False)
    draw_ring(c, M, rng, rc)
    draw_time(c, M)
    draw_hero(c, M, rng)
    draw_meta(c, M, rng, slot[0])
    draw_iob(c, M)
    draw_slot(c, M, slot)
    if M["secondaries"]:
        draw_loop_pill(c, M, slot[0], compact=True)
        draw_secondaries(c, M)
    else:
        draw_loop_pill(c, M, slot[0])
    draw_secondary(c, M, slot[0])
    if M["caption"]:
        c.text(CX, 318, M["caption"], 13, "Semibold", TXT2)
    verdict = None
    if not M["preview"] and not M["switched"]:
        verdict = draw_ball(c, M)
    draw_toast(c, M, verdict)


def draw_ambient(c):
    M = c.M
    rng = bg_range(M)
    slot = status_slot(M, True)
    lb = c.lowbit
    ring_col = WHITE if lb else AMB3
    rw = 1 if lb else 2
    val, struck = hero_value(M)
    live = val != "---" and not struck
    if live and is_low(rng):  # low = doubled track
        for dr_ in (-4.5, 4.5):
            c.arc(RING_R + dr_, A0, A0 + SWEEP, WHITE if lb else AMB2, rw, cap=False)
    elif live and rng in ("HIGH", "VERY_HIGH"):  # high = dashed track
        t = A0
        while t < A0 + SWEEP:
            c.arc(RING_R, t, min(t + 4, A0 + SWEEP), WHITE if lb else AMB2, rw + (1 if rng == "VERY_HIGH" else 0), cap=False); t += 7
    else:
        c.arc(RING_R, A0, A0 + SWEEP, ring_col, rw, cap=False)
    for t in (ang(M["low"]), ang(M["high"])):
        p = lambda r: (CX + r * math.cos(math.radians(t)), CY + r * math.sin(math.radians(t)))
        c.line([p(RING_R - 9), p(RING_R + 9)], WHITE if lb else AMB2, 2)
    if val != "---":
        va = ang(M["sgv"])
        kx, ky = CX + RING_R * math.cos(math.radians(va)), CY + RING_R * math.sin(math.radians(va))
        kc = WHITE if lb else (AMB2 if struck else AMB1)
        c.circle(kx, ky, 8, fill=BLACK); c.circle(kx, ky, 8, outline=kc, width=2)
    draw_time(c, M, amb=True)
    draw_hero(c, M, rng, amb=True)
    draw_meta(c, M, rng, slot[0], amb=True)
    draw_iob(c, M, amb=True)
    draw_slot(c, M, slot, amb=True)
    if M["secondaries"]:
        draw_loop_pill(c, M, slot[0], amb=True, compact=True)
        draw_secondaries(c, M, amb=True)
    else:
        draw_loop_pill(c, M, slot[0], amb=True)
    draw_secondary(c, M, slot[0], amb=True)


def draw_simplified(c):
    """sys.simplified: family SimpleUi reference (states.md v2). Time on top, white digits, range hue only on the
    arrow, age next to the arrow, W1-W3 / escalation line, TIEF/HOCH caption; ambient = outline digits."""
    M = c.M
    rng = bg_range(M)
    amb = c.amb
    lb = c.lowbit
    val, struck = hero_value(M)
    c.text(CX, 112, M["time"], 30, "Regular" if amb else "Medium", (WHITE if lb else AMB1) if amb else TXT1)
    cap, size, w, xc = hero_block(c, M, rng, val, struck, 110, 230, 260)
    y = 212
    col = ((WHITE if lb else (AMB2 if struck else AMB1)) if amb else (TXT2 if struck or val == "---" else TXT1))
    if amb: c.text(xc, y, val, size, "Light", col, stroke=(1.5 if c.burn else 2) + (1 if rng == "URGENT_LOW" else 0))
    else: c.text(xc, y, val, size, "Semibold", col)
    if struck:
        c.line([(xc - w / 2 - 6, y - 2), (xc + w / 2 + 6, y - 2)], (WHITE if lb else AMB2) if amb else TXT2, 2 if amb else 3)
    if cap:
        draw_caption(c, xc + w / 2 + 8, y - 30, cap, range_col(rng), amb)
    # arrow + age
    ac = age_class(M["bg_age"])
    parts_w = 0
    age = fmt_age(M["bg_age"], M["lang"]) if ac != "NONE" and not M["first_start"] else None
    arrow = (not struck) and val != "---" and M["trend"] in TREND_DEG
    acol = {"FRESH": TXT2, "AGING": WARN}.get(ac, ALERT)
    if amb: acol = (WHITE if lb else (AMB2 if ac == "FRESH" else AMB1))
    slot = status_slot(M, True)
    show_age = age and slot[0] not in (2, 3)
    aw = c.tw(age, 22, "Medium") if show_age else 0
    total = (30 if arrow else 0) + (12 if arrow and show_age else 0) + aw
    x = CX - total / 2
    if arrow:
        arrow_glyph(c, x + 15, 290, M["trend"], ((WHITE if lb else AMB1) if amb else range_col(rng)), 30, 3.2 if not amb else 2.2)
        x += 42
    if show_age:
        c.text(x, 290, age, 22, "Medium", acol, anchor="lm")
    if slot[1] and slot[0] <= 3.5:
        draw_slot(c, M, (slot[0], slot[1], slot[2], slot[3]), amb=amb)
