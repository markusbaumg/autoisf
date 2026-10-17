"""AAPS Orbit: full state matrix, family rules from states.md.

Pillow only.  uv run --with pillow python3 orbit_states.py
Every number here is the source of truth for orbit_spec.md (design canvas 450).
"""
import math, os, re
from PIL import Image, ImageDraw, ImageFont, ImageFilter

OUT = os.path.dirname(os.path.abspath(__file__))
SS = 3                                   # supersampling
FONT = "/System/Library/Fonts/SFNSRounded.ttf"

# ------------------------------------------------------------------ tokens (states.md §3)
T = dict(
    r_in="#3DDC97", r_high="#FFB547", r_low="#FF5A6E", r_stale="#8A93A8",
    on_red="#14060A", on_red_soft="#FFD6DC",   # text on a red backing only (badge / pill)
    warn="#FFB547", alert="#FF5A6E", ins="#5AC8FA", tbr="#3DDBD9", tt="#B388FF",
    loop_ok="#40D68C", ball_idle="#7888FF", ball_armed="#5B63F5",
    txt1="#EEF1F7", txt2="#9AA6C4", txt3="#6F7A99",
    amb1="#E0E0E0", amb2="#9A9A9A", amb3="#6E6E6E", ambLine="#3C3C3C",
    # Orbit materials (not hues)
    track="#2A3446", shell="#0E131B", ring_neutral="#5A6B85", ring_dim="#3A4250",
    pill_alert="#3A1018", pill_warn="#33240C", pill_info="#141A25", moon="#F2F6FC",
)
RANGE_HUE = dict(IN=T["r_in"], HIGH=T["r_high"], VERY_HIGH=T["r_high"], LOW=T["r_low"],
                 URGENT_LOW=T["r_low"], STALE=T["r_stale"], NONE=T["r_stale"])

STR = {
    "de": dict(waiting="Warte auf AAPS", phone_lost="Telefon getrennt · {}", no_data="Keine Daten · {}",
               no_bg="Kein BG", sec_low="{} TIEF {}", sec_stale="{}: Keine Daten · {}",
               no_profile="Kein Profil", loop_off="Loop aus", loop_age="Loop {}", open="Offen",
               lgs="LGS", pump="Pumpe", res="Reservoir {}", batt="Tel {} %", cob="KH", iob="IOB",
               loop="LOOP", basal="BASAL", tbr="TBR", tt="TT", off="aus", low="TIEF", high="HOCH",
               hours="{} Std", days="{} T", week="KW {}", again="nochmal", again_short="2×", date="Fr 17. Okt",
               no_graph="Kein Verlauf",
               toast_phone="Telefon nicht verbunden.\nKeine Anfrage gesendet.",
               toast_stale="Keine aktuellen Daten vom Telefon.\nKeine Anfrage gesendet.",
               toast_low="BG zu niedrig.\nKeine Anfrage gesendet.",
               toast_profile="Kein Profil.\nKeine Anfrage gesendet.",
               toast_loop_off="Loop aus.\nKeine Anfrage gesendet.",
               toast_max="0,5 U liegt über dem\nMax-Bolus (0,3 U)",
               toast_pending="Eine Anfrage läuft bereits.\nBitte auf der Uhr prüfen.",
               unit="U", dec=","),
    "en": dict(waiting="Waiting for AAPS", phone_lost="Phone disconnected · {}", no_data="No data · {}",
               no_bg="No BG", sec_low="{} LOW {}", sec_stale="{}: No data · {}",
               no_profile="No profile", loop_off="Loop off", loop_age="Loop {}", open="Open",
               lgs="LGS", pump="Pump", res="Reservoir {}", batt="Phone {} %", cob="COB", iob="IOB",
               loop="LOOP", basal="BASAL", tbr="TBR", tt="TT", off="off", low="LOW", high="HIGH",
               hours="{} h", days="{} d", week="W{}", again="again", again_short="2×", date="Fri, Oct 17",
               no_graph="No history",
               toast_phone="Phone not connected.\nNo request sent.", toast_stale="No current data from the phone.\nNo request sent.", toast_low="BG too low.\nNo request sent.",
               toast_profile="No profile.\nNo request sent.", toast_loop_off="Loop off.\nNo request sent.",
               toast_max="0.5 U is above the\nmax bolus (0.3 U)", toast_pending="A request is already pending.",
               unit="U", dec="."),
    "fr": dict(waiting="En attente d'AAPS", phone_lost="Téléphone déconnecté · {}",
               no_data="Aucune donnée · {}", no_bg="Pas de glycémie", sec_low="{} BAS {}",
               sec_stale="{} : aucune donnée · {}", no_profile="Aucun profil", loop_off="Boucle désactivée",
               loop_age="Boucle {}", open="Ouv.", lgs="LGS", pump="Pompe", res="Réservoir {}",
               batt="Tél {} %", cob="GA", iob="IA", loop="BOUCLE", basal="BASAL", tbr="TBR", tt="CT",
               off="arr.", low="BAS", high="HAUT", hours="{} h", days="{} j", week="S{}",
               again="encore", again_short="2×", date="ven. 17 oct.", no_graph="Pas d'historique",
               toast_phone="", unit="U", dec=","),
}
TOAST_KEY = dict(NO_PHONE="toast_phone", STALE_DATA="toast_stale", LOW_BG="toast_low", NO_PROFILE="toast_profile",
                 LOOP_OFF="toast_loop_off", MAX_BOLUS="toast_max", PENDING="toast_pending")


def de_space(s):
    """German/SI spacing for unit suffixes sent glued by the phone ('120%' → '120 %', '45g' → '45 g',
    '18U' → '18 U'). Never touches digits or the decimal separator."""
    return re.sub(r"(?<=[\d)])\s*(%|g|U/h|U)$", r" \1", s.strip())

_fc = {}


def font(px, weight="Regular"):
    key = (int(px), weight)
    if key not in _fc:
        f = ImageFont.truetype(FONT, max(1, int(px)))
        try:
            f.set_variation_by_name(weight)
        except Exception:
            pass
        _fc[key] = f
    return _fc[key]


def hx(h, a=255):
    h = h.lstrip("#")
    return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def mmol(mg, dec=","):
    return f"{mg / 18.0:.1f}".replace(".", dec)


# ------------------------------------------------------------------ FaceState (states.md §1)
def fmt_age(m, L):
    if m is None:
        return "--"
    if m >= 48 * 60:
        return L["days"].format(m // 1440)
    if m >= 60:
        return L["hours"].format(m // 60)
    return f"{m}′"


def derive(v):
    d = {}
    age = v["age"]
    d["bgAge"] = ("NONE" if age is None else "FRESH" if age < 6 else "AGING" if age < 12
                  else "STALE" if age < 30 else "VERY_STALE")
    fresh = d["bgAge"] in ("FRESH", "AGING")
    sgv, lvl, tr = v["sgv"], v["level"], v["trend"]
    dm = v.get("dmg")
    d["fastFall"] = tr in ("DOWN2", "DOWN3") or (dm is not None and dm <= -10)
    if fresh:
        if sgv < 54 or (lvl == -1 and d["fastFall"]):
            r = "URGENT_LOW"
        elif lvl == -1:
            r = "LOW"
        elif sgv > 250:
            r = "VERY_HIGH"
        elif lvl == 1:
            r = "HIGH"
        else:
            r = "IN"
    else:
        r = "NONE" if age is None else "STALE"
    d["range"] = r
    d["sentinel"] = (None if not fresh else "LOW" if sgv <= 39 else "HIGH" if sgv >= 400 else None)
    da = v.get("data_age", age if age is not None else 999)
    reach = v["phone"]
    d["link"] = ("OK" if reach and da < 12 else "LOST_FRESH" if not reach and da < 12
                 else "LOST_STALE" if not reach else "SILENT")
    d["dataAge"] = da
    d["first"] = v.get("first", False)
    mode = v["loop_mode"]
    d["noProfile"] = mode == "no_profile" or v["iob"] == ""
    la = v["loop_age"]
    if la is None:
        lh = "NEVER"
    elif mode in ("open", "no_aps"):
        lh = "NEUTRAL"
    else:
        lh = "OK" if la <= 5 else "WARN" if la < 15 else "BAD"
    d["loopHealth"] = lh
    # loop age comes from openApsStatus (last TBR, gap G7): 15-29' is amber, red only from 30'
    d["loopSevere"] = lh == "BAD" and la is not None and la >= 30
    d["lowFalling"] = r == "LOW" and tr in ("DOWN45", "DOWN", "DOWN2", "DOWN3")
    d["phoneGlyph"] = d["link"] == "LOST_FRESH"          # W10: glyph only, never suppressed
    return d


def sec_state(m):
    """Secondary dataset (1|2) derived signals. Same thresholds as FaceState, own dataset only."""
    ma = m["age"]
    fresh = ma < 12
    mg = m.get("mg")
    fall = m.get("trend") in ("DOWN2", "DOWN3")
    urgent = fresh and ((mg is not None and mg < 54) or (m["level"] < 0 and fall))
    low = fresh and m["level"] < 0
    return dict(fresh=fresh, low=low, urgent=urgent, very_stale=ma >= 30)


def status_slot(v, d, L, amb):
    """Warning hierarchy (states.md §4.2 + critique amendments). Returns (rank, text, tone) or None.
    W10 is no longer a slot entry: it is the phone-off glyph (d['phoneGlyph'])."""
    ages = fmt_age(v["age"] if v["age"] is not None else d["dataAge"], L)
    secs = [m for m in v.get("multi", []) if m.get("age") is not None][:2]
    sec_urgent = next((m for m in secs if sec_state(m)["urgent"]), None)
    sec_vstale = next((m for m in secs if sec_state(m)["very_stale"]), None)
    w = None
    if d["first"]:
        w = (1, L["waiting"], "info")
    elif d["link"] == "LOST_STALE":
        w = (2, L["phone_lost"].format(fmt_age(d["dataAge"], L)), "alert")
    elif d["link"] == "SILENT" or d["bgAge"] in ("STALE", "VERY_STALE"):
        w = (3, L["no_data"].format(ages), "alert")
    elif d["bgAge"] == "NONE":
        w = (3, L["no_bg"], "warn")                       # age.none: a BG-less face always explains itself
    elif sec_urgent is not None:
        w = (3.5, L["sec_low"].format(sec_urgent["name"] or "P2", sec_urgent["bg"]), "alert")
    elif d["noProfile"]:
        w = (4, L["no_profile"], "warn")
    elif v["loop_mode"] == "off":
        w = (5, L["loop_off"], "warn")
    elif d["loopHealth"] == "BAD":
        w = (6, L["loop_age"].format(fmt_age(v["loop_age"], L)), "alert" if d["loopSevere"] else "warn")
    elif sec_vstale is not None:
        w = (6.5, L["sec_stale"].format(sec_vstale["name"] or "P2", fmt_age(sec_vstale["age"], L)), "warn")
    elif v["res_level"] == 2 and v["res"] not in ("", "--"):
        w = (7, L["res"].format(de_space(v["res"])), "alert")
    elif v["batt_level"] == 0 and v["batt"] not in ("0", ""):
        w = (8, L["batt"].format(v["batt"]), "alert" if int(v["batt"]) < 10 else "warn")
    elif v["res_level"] == 1 and v["res"] not in ("", "--"):
        w = (9, L["res"].format(de_space(v["res"])), "warn")
    if w is None:
        return None
    if d["range"] in ("LOW", "URGENT_LOW") and w[0] >= 7:
        return None                    # fresh low: W7-W9 suppressed; W1-W6 ALWAYS show (incl. urgent low)
    if amb and w[0] > 6:
        return None
    return w


def ball_verdict(v, d):
    """Display verdict of the bolus button (family state machine + LOW_BG / NO_PROFILE / LOOP_OFF guards)."""
    if d["link"] in ("LOST_FRESH", "LOST_STALE"):
        return "NO_PHONE"
    if d["dataAge"] >= 12 or d["bgAge"] in ("STALE", "VERY_STALE", "NONE"):
        return "STALE_DATA"
    if d["noProfile"]:
        return "NO_PROFILE"
    if v["loop_mode"] == "off":
        return "LOOP_OFF"
    if d["range"] == "URGENT_LOW" or d["lowFalling"]:
        return "LOW_BG"
    if v.get("max_bolus") is not None and v["amount"] > v["max_bolus"]:
        return "MAX_BOLUS"
    return None


# ------------------------------------------------------------------ defaults
BASE = dict(
    W=450, H=450, shape="round", inset=0, mode="interactive", shift=(0, 0), lang="de",
    units="mmol", sgv=115, level=0, trend="FLAT", dmg=1.8, delta="+0,1", age=1, phone=True,
    loop_mode="closed", loop_age=2, iob="1,25", cob="0g", basal="0,85U/h", tt="", tt_level=0,
    res="86U", res_level=0, batt="64", batt_level=1, low=70, high=180,
    time="14:19", ampm=None, minute=19, seconds=None, date=None, week=None,
    show=dict(delta=True, ago=True, direction=True, iob=True, cob=True, basal=True, tt=True,
              loop=True, date=True),
    ball="off", amount=0.5, armed_left=0.6, ball_tf=None, toast=None,
    hist="normal", pred=True, caption=None, tf_h=3,
    multi=[], name="", zones=False, bgstr=None, switched=False, max_bolus=None,
)


def hist_series(kind, sgv, age, units):
    """(minutes_ago, mg/dL or None for a gap) over 3 h, 5-min steps; ends at the BG age."""
    if kind == "empty":
        return []
    pts = []
    for i in range(36, -1, -1):
        t = age + i * 5
        if t > 180:
            continue
        x = i / 36.0
        val = sgv + (130 - sgv) * x * 0.9 + 14 * math.sin(i / 4.0) * x
        if kind == "gaps" and 8 <= i <= 13:
            val = None
        pts.append((t, max(40, min(400, val)) if val is not None else None))
    return pts


# ------------------------------------------------------------------ renderer
class Ctx:
    def __init__(self, v):
        self.W, self.H = v["W"], v["H"]
        diam = min(v["W"], v["H"]) if v["shape"] != "flat" else v["W"]
        self.s = diam / 450.0
        self.cx = v["W"] / 2
        self.cy = v["H"] / 2 if v["shape"] != "flat" else v["W"] / 2
        self.dx, self.dy = v["shift"]

    def p(self, x, y):
        return ((self.cx + (x - 225 + self.dx) * self.s) * SS, (self.cy + (y - 225 + self.dy) * self.s) * SS)

    def L(self, n):
        return n * self.s * SS

    def f(self, size, weight="Regular", floor=11):
        return font(max(size * self.s, floor) * SS, weight)


def P(r, deg):
    a = math.radians(deg)
    return 225 + r * math.sin(a), 225 - r * math.cos(a)


def text_w(c, s, size, weight):
    return c.f(size, weight).getlength(s) / SS / c.s


def ellipsize(c, s, size, weight, maxw):
    if text_w(c, s, size, weight) <= maxw:
        return s
    while s and text_w(c, s + "…", size, weight) > maxw:
        s = s[:-1]
    return s + "…"


def ellipsize_keep_suffix(c, s, size, weight, maxw):
    """Truncate the words, never the trailing ' · N′' (the safety-relevant age)."""
    if text_w(c, s, size, weight) <= maxw or " · " not in s:
        return ellipsize(c, s, size, weight, maxw)
    head, tail = s.rsplit(" · ", 1)
    tail = " · " + tail
    room = maxw - text_w(c, tail, size, weight)
    while head and text_w(c, head + "…", size, weight) > room:
        head = head[:-1].rstrip()
    return head + "…" + tail


def fit_font(c, s, sizes, weight, maxw):
    for sz in sizes:
        if text_w(c, s, sz, weight) <= maxw:
            return c.f(sz, weight)
    return c.f(sizes[-1], weight)


def badge(d, c, x, y, text, col, filled, amb=False, burn=False, size=16, txtcol=None):
    """Range word badge (TIEF / HOCH). Outlined = low / sentinel high, filled = urgent low.
    Burn-in never fills: a filled badge becomes a doubled outline."""
    f = c.f(size, "Bold")
    w = f.getlength(text) / SS / c.s
    hw, hh = w / 2 + 10, size * 0.78
    r0 = [*c.p(x - hw, y - hh), *c.p(x + hw, y + hh)]
    lw = max(1, int(c.L(1.6)))
    if filled and not burn:
        d.rounded_rectangle(r0, radius=c.L(hh), fill=col)
        d.text(c.p(x, y), text, font=f, fill=txtcol or hx(T["on_red"]), anchor="mm")
    else:
        d.rounded_rectangle(r0, radius=c.L(hh), outline=col, width=lw)
        if filled:   # burn-in urgent: doubled outline
            r1 = [*c.p(x - hw - 3, y - hh - 3), *c.p(x + hw + 3, y + hh + 3)]
            d.rounded_rectangle(r1, radius=c.L(hh + 3), outline=col, width=lw)
        d.text(c.p(x, y), text, font=f, fill=col, anchor="mm")
    return hw


def glow_layer(img, c, fn, rad, box):
    """Draw fn into a cropped layer around box (canvas units), blur, composite."""
    x0, y0 = c.p(box[0], box[1]); x1, y1 = c.p(box[2], box[3])
    pad = c.L(rad) * 3
    bx = [max(0, int(x0 - pad)), max(0, int(y0 - pad)), min(img.width, int(x1 + pad)), min(img.height, int(y1 + pad))]
    lay = Image.new("RGBA", (bx[2] - bx[0], bx[3] - bx[1]), (0, 0, 0, 0))
    fn(ImageDraw.Draw(lay), -bx[0], -bx[1])
    lay = lay.filter(ImageFilter.GaussianBlur(c.L(rad)))
    img.alpha_composite(lay, (bx[0], bx[1]))


def draw_arrow(d, c, x, y, trend, size, col, width, outline=False):
    """Face-own trend glyph: shaft + head(s), rotated. Returns used half-width (canvas)."""
    ang = dict(UP=0, UP2=0, UP3=0, UP45=45, FLAT=90, DOWN45=135, DOWN=180, DOWN2=180, DOWN3=180)[trend]
    dbl = trend.endswith("2") or trend.endswith("3")
    if trend.endswith("3"):
        width *= 1.45
    a = math.radians(ang)
    ux, uy = math.sin(a), -math.cos(a)
    L = size * 0.62
    tail = (x - ux * L / 2, y - uy * L / 2)
    tip = (x + ux * L / 2, y + uy * L / 2)
    w = c.L(width)

    def seg(p0, p1):
        q0, q1 = c.p(*p0), c.p(*p1)
        d.line([q0, q1], fill=col, width=max(1, int(w)))
        for q in (q0, q1):
            d.ellipse([q[0] - w / 2, q[1] - w / 2, q[0] + w / 2, q[1] + w / 2], fill=col)

    seg(tail, tip)
    hl = size * 0.30
    heads = [tip] + ([(tip[0] - ux * size * 0.24, tip[1] - uy * size * 0.24)] if dbl else [])
    for hp in heads:
        for s_ in (-1, 1):
            b = a + math.pi + s_ * math.radians(42)
            seg(hp, (hp[0] + math.sin(b) * hl, hp[1] - math.cos(b) * hl))
    return size * 0.36


def pause_glyph(d, c, x, y, col, h=14, hollow=False):
    for ox in (-4, 4):
        a = c.p(x + ox - 2, y - h / 2); b = c.p(x + ox + 2, y + h / 2)
        if hollow:
            d.rectangle([a, b], outline=col, width=max(1, int(c.L(1.2))))
        else:
            d.rounded_rectangle([a, b], radius=c.L(1), fill=col)


def phone_off_glyph(d, c, x, y, col):
    a = c.p(x - 4, y - 7); b = c.p(x + 4, y + 7)
    d.rounded_rectangle([a, b], radius=c.L(1.5), outline=col, width=max(1, int(c.L(1.4))))
    d.line([c.p(x - 7, y + 7), c.p(x + 7, y - 7)], fill=col, width=max(1, int(c.L(1.6))))


def squircle(c, x, y, h, cr, n=10):
    pts = [(x, y - h)]
    for cx, cy, a0 in [(x + h - cr, y - h + cr, -90), (x + h - cr, y + h - cr, 0),
                       (x - h + cr, y + h - cr, 90), (x - h + cr, y - h + cr, 180)]:
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


def dash_path(pts, on, off):
    """Split a polyline into equal-length dashes (lengths in the same units as pts)."""
    out, cur, acc, draw = [], [pts[0]], 0.0, True
    for i in range(len(pts) - 1):
        a, b = pts[i], pts[i + 1]
        seg = math.dist(a, b); t = 0.0
        while seg - t > 1e-6:
            lim = on if draw else off
            step = min(lim - acc, seg - t)
            t += step; acc += step
            p = (a[0] + (b[0] - a[0]) * t / seg, a[1] + (b[1] - a[1]) * t / seg)
            if draw:
                cur.append(p)
            if acc >= lim - 1e-6:
                if draw:
                    out.append(cur)
                cur = [p]; acc = 0.0; draw = not draw
    if draw and len(cur) > 1:
        out.append(cur)
    return out


def drop_glyph(d, c, x, y, h, col):
    r = h * 0.36
    cy = y + h / 2 - r
    pts = [c.p(x, y - h / 2), c.p(x + r * 0.94, cy - r * 0.34)]
    pts += [c.p(x + r * math.cos(math.radians(a)), cy + r * math.sin(math.radians(a))) for a in range(-20, 201, 8)]
    pts += [c.p(x - r * 0.94, cy - r * 0.34)]
    d.polygon(pts, fill=col)


def render(**ov):
    v = dict(BASE)
    show = dict(BASE["show"]); show.update(ov.pop("show", {}))
    v.update(ov); v["show"] = show
    L = STR[v["lang"]]
    if v["date"] is None:
        v["date"] = L["date"]
    mode = v["mode"]
    amb = mode != "interactive"
    lowbit = "low_bit" in mode
    burn = "burn_in" in mode
    if not burn:
        v["shift"] = (0, 0)
    c = Ctx(v)
    d_ = derive(v)
    img = Image.new("RGBA", (v["W"] * SS, v["H"] * SS), (0, 0, 0, 255))
    d = ImageDraw.Draw(img, "RGBA")
    rng = d_["range"]
    hue = RANGE_HUE[rng]
    stale = d_["bgAge"] in ("STALE", "VERY_STALE")
    fresh = d_["bgAge"] in ("FRESH", "AGING")
    emph = rng in ("VERY_HIGH", "URGENT_LOW")
    small = c.s < 0.9
    multi = [m for m in v["multi"] if m.get("age") is not None]

    def A(tok):          # ambient colour mapping (states.md §4.5)
        if not amb:
            return hx(T[tok]) if tok in T else hx(tok)
        if lowbit:
            return None if tok == "ambLine" else (255, 255, 255, 255)
        return hx(T[tok])

    slot = status_slot(v, d_, L, amb)
    swd = v["switched"]
    # red budget (family rule 3): BG accent group + status slot. Whenever the slot owns W2/W3/W6 the
    # loop element is neutral grey, so the loop age is never red twice.
    loop_neutral = bool(slot and slot[0] in (2, 3, 6))
    low_now = fresh and rng in ("LOW", "URGENT_LOW")

    # ---------------- ball visibility / state
    ball = v["ball"]
    if amb or v.get("headless") or ball in ("hidden",) or v.get("first") or swd:
        ball = "hidden" if v["ball"] != "off" else "off"
    verdict = ball_verdict(v, d_) if ball not in ("off", "hidden") else None
    if ball in ("idle", "armed") and verdict:
        ball = "disabled"
    ball_drawn = ball in ("idle", "armed", "disabled", "sent", "delivering", "delivered")

    # ---------------- balls (positions fixed)
    balls = []         # (deg, kind, payload)
    if show["loop"] and not swd:
        balls.append((45, "loop", None))
    if show["iob"] and not swd:
        balls.append((90, "iob", None))
    if show["basal"] and not amb and not swd:
        balls.append((135, "basal", None))
    sec = multi[:2]
    if sec:
        balls.append((225, "patient", sec[0]))
    elif show["cob"] and not amb:
        balls.append((225, "cob", None))
    tt_active = v["tt_level"] == 2 and v["tt"] not in ("", "--") and not swd
    tt_in_meta = False
    if len(sec) == 2:
        balls.append((315, "patient", sec[1]))
        tt_in_meta = tt_active and show["tt"]
    elif show["tt"] and tt_active:
        balls.append((315, "tt", None))

    # ---------------- corona (BG accent)
    if not amb and fresh:
        a = int(34 * (1.2 if emph else 1.0))
        sm = 6
        lay = Image.new("RGBA", (img.width // sm, img.height // sm), (0, 0, 0, 0))
        x0, y0 = c.p(105, 115); x1, y1 = c.p(345, 315)
        ImageDraw.Draw(lay).ellipse([x0 / sm, y0 / sm, x1 / sm, y1 / sm], fill=hx(hue, a))
        lay = lay.filter(ImageFilter.GaussianBlur(c.L(60) / sm)).resize(img.size, Image.BILINEAR)
        img.alpha_composite(lay)
        d = ImageDraw.Draw(img, "RGBA")

    # ---------------- time / date strings + track gaps
    f_time = c.f(30, "Regular" if burn else ("Regular" if amb else "Semibold"))
    tstr = v["time"]
    tw = f_time.getlength(tstr) / SS / c.s
    extra = ""
    if v["ampm"]:
        extra = v["ampm"]
    if v["seconds"] and not amb:
        extra = v["seconds"]
    ew = text_w(c, extra, 12, "Medium") + 3 if extra else 0
    datestr = None
    if show["date"]:
        datestr = v["date"] + (f" · {L['week'].format(v['week'])}" if v["week"] else "")
    dw = text_w(c, datestr, 18, "Medium") if datestr else 0
    gap_t = math.degrees((tw + ew + 20) / 2 / 168)
    gap_d = math.degrees((dw + 20) / 2 / 168) if datestr else 0
    # flat tyre: date row must sit above the chin
    date_y = 393
    if v["shape"] == "flat":
        limit_canvas = 225 + ((v["H"] - 6) - c.cy) / c.s - 9
        date_y = min(date_y, limit_canvas)

    def track_arcs(r, col, width, dash=None):
        box = [*c.p(225 - r, 225 - r), *c.p(225 + r, 225 + r)]
        segs = [(gap_t, 180 - gap_d), (180 + gap_d, 360 - gap_t)] if datestr else [(gap_t, 360 - gap_t)]
        for a0, a1 in segs:
            if dash:
                a = a0
                step_on = math.degrees(dash[0] / r); step_off = math.degrees(dash[1] / r)
                while a < a1:
                    d.arc(box, a - 90, min(a + step_on, a1) - 90, fill=col, width=max(1, int(c.L(width))))
                    a += step_on + step_off
            else:
                d.arc(box, a0 - 90, a1 - 90, fill=col, width=max(1, int(c.L(width))))

    if not amb:
        if rng == "URGENT_LOW":
            # urgent low: the track itself doubles in range.low (part of the BG accent group, not a 2nd red)
            track_arcs(165.5, hx(T["r_low"], 170), 1.5); track_arcs(170.5, hx(T["r_low"], 170), 1.5)
        else:
            track_arcs(168, hx(T["track"]), 1.5)
    else:
        # range expressed by the track's shape (states.md §4.5): high dashed, low doubled
        if rng in ("HIGH", "VERY_HIGH"):
            track_arcs(168, A("amb2"), 1, dash=(6, 6))
        elif rng in ("LOW", "URGENT_LOW"):
            track_arcs(166, A("amb2"), 1); track_arcs(171, A("amb2"), 1)
        else:
            col = A("ambLine")
            if col:
                track_arcs(168, col, 1)

    # ---------------- minute moon
    occl = None
    if not amb:
        mdeg = (v["minute"] * 6) % 360
        blockers = [(b[0], math.degrees(math.asin(34 / 168)) + 1.5) for b in balls]
        if ball_drawn:
            blockers.append((270, math.degrees(math.atan(36.3 / 168)) + 1.5))
        in_gap = mdeg < gap_t + 2 or mdeg > 360 - gap_t - 2 or (datestr and abs(mdeg - 180) < gap_d + 2)
        for bd, bh in blockers:
            diff = (mdeg - bd + 540) % 360 - 180
            if abs(diff) < bh:
                occl = (bd, diff, bh)
        if not in_gap and occl is None:
            mx, my = c.p(*P(168, mdeg)); r = c.L(4.5)
            d.ellipse([mx - r, my - r, mx + r, my + r], fill=hx(T["moon"]))

    # ---------------- time
    tcol = A("amb1") if amb else hx("#E6ECF4")
    tx, ty = P(168, 0)
    x_t = tx - (tw + ew) / 2
    d.text(c.p(x_t, ty), tstr, font=f_time, fill=tcol, anchor="lm")
    if extra:
        d.text(c.p(x_t + tw + 3, ty + 4), extra, font=c.f(12, "Medium"), fill=A("amb2") if amb else hx(T["txt2"]), anchor="lm")
    # primary patient name under the time (multi only)
    if swd and v["name"]:
        # multi.switched: the hero patient is a header chip, not small text
        nm = ellipsize(c, v["name"], 18, "Semibold", 140)
        w = text_w(c, nm, 18, "Semibold")
        r0 = [*c.p(225 - w / 2 - 22, 84), *c.p(225 + w / 2 + 12, 108)]
        d.rounded_rectangle(r0, radius=c.L(12), fill=None if amb else hx(T["pill_info"]),
                            outline=A("amb2") if amb else hx(T["txt3"]), width=max(1, int(c.L(1.5))))
        pr = c.p(225 - w / 2 - 10, 96); rr = c.L(4)
        d.ellipse([pr[0] - rr, pr[1] - rr, pr[0] + rr, pr[1] + rr], outline=A("amb1") if amb else hx(T["txt1"]),
                  width=max(1, int(c.L(1.5))))
        d.text(c.p(225 + 5, 96), nm, font=c.f(18, "Semibold"), fill=A("amb1") if amb else hx(T["txt1"]), anchor="mm")
    elif multi and v["name"]:
        nm = ellipsize(c, v["name"], 14, "Medium", 120)
        d.text(c.p(225, 87), nm, font=c.f(14, "Medium"), fill=A("amb2") if amb else hx(T["txt2"]), anchor="mm")

    # ---------------- date
    if datestr:
        d.text(c.p(225, date_y), datestr, font=c.f(18, "Medium"), fill=A("amb2") if amb else hx("#A9B4C4"), anchor="mm")

    # ---------------- hero BG
    sgvs = v["bgstr"]
    if sgvs is None:
        sgvs = str(int(v["sgv"])) if v["units"] == "mgdl" else mmol(v["sgv"], ",")
    if d_["sentinel"] == "HIGH":
        sgvs = sgvs + "+"
    if d_["bgAge"] == "NONE" or (v["age"] is not None and v["age"] >= 60):
        sgvs = "---"
    show_arrow = show["direction"] and fresh and v["trend"] not in ("NONE",)
    bg_w8 = "Light" if amb and rng != "URGENT_LOW" else "Regular" if amb else "Bold"
    size = 108
    avail = 252
    aw = 40 if show_arrow else 0
    while size > 60 and text_w(c, sgvs, size, bg_w8) + (aw + 14 if show_arrow else 0) > avail:
        size -= 4
    f_bg = c.f(size, bg_w8, floor=0)
    wbg = f_bg.getlength(sgvs) / SS / c.s
    group = wbg + (aw + 14 if show_arrow else 0)
    bx = 225 - group / 2 + wbg / 2
    by = 186
    if amb:
        bgcol = A("amb1")
    elif not fresh:
        bgcol = hx(T["txt2"])
    elif rng in ("LOW", "URGENT_LOW"):
        bgcol = hx(T["r_low"])           # any fresh low tints the digits; the TIEF badge is the non-colour cue
    else:
        bgcol = hx(T["txt1"])
    if not amb and fresh:
        glow_layer(img, c, lambda g, ox, oy: g.text((c.p(bx, by)[0] + ox, c.p(bx, by)[1] + oy), sgvs, font=f_bg,
                                                      fill=hx(hue, 150 if emph else 120), anchor="mm"),
                   12, (bx - wbg / 2, by - 50, bx + wbg / 2, by + 50))
        d = ImageDraw.Draw(img, "RGBA")
    if burn:
        d.text(c.p(bx, by), sgvs, font=f_bg, fill=(0, 0, 0, 255), anchor="mm",
               stroke_width=max(1, int(c.L(3 if rng == "URGENT_LOW" else 2))), stroke_fill=bgcol)
        d.text(c.p(bx, by), sgvs, font=f_bg, fill=(0, 0, 0, 255), anchor="mm")
    else:
        d.text(c.p(bx, by), sgvs, font=f_bg, fill=bgcol, anchor="mm")
    bb = d.textbbox(c.p(bx, by), sgvs, font=f_bg, anchor="mm")
    right = (bb[2] / SS - c.cx) / c.s + 225 - c.dx
    left = (bb[0] / SS - c.cx) / c.s + 225 - c.dx
    if show_arrow:
        acol = A("amb1") if amb else hx(hue)
        draw_arrow(d, c, right + 14 + aw / 2, by + 2, v["trend"], 48 * size / 108, acol,
                   (2 if amb else 4.6) * size / 108)
    # range word badge above the digits (y 124): LOW outlined, URGENT_LOW / sentinel low filled,
    # sentinel high outlined HOCH. Same element in every mode; ambient swaps the hue for amb1/white.
    if fresh and rng in ("LOW", "URGENT_LOW"):
        badge(d, c, 225, 124, L["low"], A("amb1") if amb else hx(T["r_low"]), filled=rng == "URGENT_LOW",
              amb=amb, burn=burn, txtcol=(0, 0, 0, 255) if amb else None)
    elif d_["sentinel"] == "HIGH":
        badge(d, c, 225, 124, L["high"], A("amb1") if amb else hx(T["r_high"]), filled=False, amb=amb, burn=burn)
    if stale and sgvs != "---":
        yy = by + 4
        d.line([c.p(left - 6, yy), c.p(right + 6, yy)], fill=bgcol, width=max(1, int(c.L(2 if amb else 3))))

    # ---------------- meta row (delta · age)
    parts = []                                    # (text, colour)
    sub = A("amb2") if amb else hx(T["txt2"])
    if fresh:
        if show["delta"]:
            dl = v["delta"]
            parts.append((dl if dl not in ("", "--") else "--", sub if dl not in ("", "--") else (A("amb3") if amb else hx(T["txt3"]))))
        if show["ago"] or d_["bgAge"] != "FRESH":
            parts.append((fmt_age(v["age"], L), (A("amb1") if amb else hx(T["warn"])) if d_["bgAge"] == "AGING" else sub))
    elif d_["bgAge"] == "NONE" and not d_["first"]:
        parts.append(("--", A("amb3") if amb else hx(T["txt3"])))
    if tt_in_meta and fresh:
        parts.append((f"TT {v['tt']}", A("amb1") if amb else hx(T["tt"])))
    glyph = d_["phoneGlyph"]
    meta_present = bool(parts) and not (slot and slot[0] <= 3)
    if meta_present:
        f_sub = c.f(21, "Regular" if amb else "Medium")
        sep = "  ·  "
        widths = [f_sub.getlength(t) / SS / c.s for t, _ in parts]
        sw = f_sub.getlength(sep) / SS / c.s
        tot = sum(widths) + sw * (len(parts) - 1) + (18 if glyph else 0)
        x = 225 - tot / 2
        for i, (t, col) in enumerate(parts):
            d.text(c.p(x, 256), t, font=f_sub, fill=col, anchor="lm")
            x += widths[i]
            if i < len(parts) - 1:
                d.text(c.p(x, 256), sep, font=f_sub, fill=hx(T["txt3"]) if not amb else A("amb3"), anchor="lm"); x += sw
        if glyph:
            phone_off_glyph(d, c, x + 11, 256, hx(T["txt2"]) if not amb else A("amb2"))

    # ---------------- status pill
    pill_y = None
    if slot:
        pill_y = 256 if (slot[0] <= 3 or not meta_present) else 289
        tone = slot[2]
        maxw = 236 if pill_y == 256 else 290
        txt = ellipsize_keep_suffix(c, slot[1], 18, "Semibold", maxw - 28)
        w = text_w(c, txt, 18, "Semibold")
        r0 = [*c.p(225 - w / 2 - 14, pill_y - 15), *c.p(225 + w / 2 + 14, pill_y + 15)]
        if amb:
            d.rounded_rectangle(r0, radius=c.L(15), outline=A("amb2"), width=max(1, int(c.L(1))))
            d.text(c.p(225, pill_y), txt, font=c.f(18, "Medium"), fill=A("amb1"), anchor="mm")
        else:
            fill, line, tc = dict(alert=(T["pill_alert"], T["alert"], T["on_red_soft"]),
                                  warn=(T["pill_warn"], T["warn"], "#FFE7C2"),
                                  info=(T["pill_info"], T["ring_dim"], T["txt2"]))[tone]
            d.rounded_rectangle(r0, radius=c.L(15), fill=hx(fill), outline=hx(line), width=max(1, int(c.L(1.5))))
            d.text(c.p(225, pill_y), txt, font=c.f(18, "Semibold"), fill=hx(tc), anchor="mm")

    # ---------------- sparkline
    compact = pill_y == 289
    gx0, gnow, gx1 = 151, 265, 291
    gy0, gy1 = (309, 335) if compact else (279, 323)
    if v["units"] == "mgdl":
        lo, hi = 45, 252
    else:
        lo, hi = 45, 252          # 2,5-14 mmol == same mg/dL window
    yv = lambda mg: gy1 - (min(max(mg, lo), hi) - lo) / (hi - lo) * (gy1 - gy0)
    tf = v["tf_h"] * 60
    xt = lambda mins_ago: gnow - mins_ago / tf * (gnow - gx0)
    if not amb:
        for mg in (v["low"], v["high"]):
            yy = yv(mg)
            for xx in range(gx0, gx1, 6):
                d.line([c.p(xx, yy), c.p(xx + 2, yy)], fill=hx(T["r_in"], 120), width=max(1, int(c.L(1))))
    series = hist_series(v["hist"], v["sgv"], v["age"] if v["age"] is not None else 0, v["units"]) if not d_["first"] else []
    if v["age"] is not None and v["age"] > tf:
        series = []
    if swd:
        series = []
        d.text(c.p((gx0 + gx1) / 2, (gy0 + gy1) / 2), L["no_graph"], font=c.f(14, "Medium"),
               fill=A("amb3") if amb else hx(T["txt3"]), anchor="mm")
    runs, cur = [], []
    prev_t = None
    for t_, mg in series:
        if mg is None or (prev_t is not None and t_ - prev_t > 15 and False):
            if cur:
                runs.append(cur); cur = []
            continue
        cur.append((xt(t_), yv(mg), mg))
    if cur:
        runs.append(cur)
    lc = (A("amb3") if not lowbit else A("amb1")) if amb else hx(hue)
    lw = 1.5 if amb else 2.5
    if not amb and fresh and runs:
        def gl(g, ox, oy):
            for run in runs:
                if len(run) > 1:
                    g.line([(c.p(x, y)[0] + ox, c.p(x, y)[1] + oy) for x, y, _ in run], fill=hx(hue, 150),
                           width=int(c.L(4)), joint="curve")
        glow_layer(img, c, gl, 4, (gx0, gy0, gx1, gy1))
        d = ImageDraw.Draw(img, "RGBA")
    for run in runs:
        if len(run) > 1:
            d.line([c.p(x, y) for x, y, _ in run], fill=lc, width=max(1, int(c.L(lw))), joint="curve")
        for x, y, mg in run:
            if mg > hi or mg < lo:
                up = mg > hi
                yy = gy0 - 3 if up else gy1 + 3
                tri = [c.p(x - 3, yy + (3 if up else -3)), c.p(x + 3, yy + (3 if up else -3)), c.p(x, yy - (2 if up else -2))]
                d.polygon(tri, fill=lc)
                break
    if runs:
        ex, ey, _ = runs[-1][-1]
        r = c.L(4)
        q = c.p(ex, ey)
        if amb or not fresh:
            d.ellipse([q[0] - r, q[1] - r, q[0] + r, q[1] + r], outline=A("amb1") if amb else hx("#C8C8C8"),
                      width=max(1, int(c.L(1.2))))
        else:
            d.ellipse([q[0] - r, q[1] - r, q[0] + r, q[1] + r], fill=(255, 255, 255, 255))
        if v["pred"] and fresh and not amb and not swd and v["loop_mode"] not in ("no_aps",):
            for i in range(1, 6):
                px = gnow + i * (gx1 - gnow) / 5
                mg = v["sgv"] + v["dmg"] * i * 0.7 if v["dmg"] is not None else v["sgv"]
                q = c.p(px, yv(mg)); r = c.L(2.2)
                d.ellipse([q[0] - r, q[1] - r, q[0] + r, q[1] + r], fill=hx(T["tt"], 220))
    if v["caption"] and not amb:
        d.text(c.p(gx0 + 2, gy0 - 2), v["caption"], font=c.f(13, "Semibold"), fill=hx(T["txt2"]), anchor="ls")

    # ---------------- status balls
    def shell(x, y, ring, width=2, dash=False, alpha=255):
        q = c.p(x, y); rr = c.L(34)
        box = [q[0] - rr, q[1] - rr, q[0] + rr, q[1] + rr]
        if amb:
            d.ellipse(box, fill=(0, 0, 0, 255))
            col = A("amb3")
            if dash:
                for a in range(0, 360, 20):
                    d.arc(box, a, a + 10, fill=col, width=max(1, int(c.L(1.2))))
            else:
                d.ellipse(box, outline=col, width=max(1, int(c.L(1.2))))
            return box
        d.ellipse(box, fill=hx(T["shell"]))
        if dash:
            for a in range(0, 360, 20):
                d.arc(box, a, a + 10, fill=hx(ring, alpha), width=max(1, int(c.L(width))))
        else:
            d.ellipse(box, outline=hx(ring, alpha), width=max(1, int(c.L(width))))
        return box

    def val_font(s_, sizes=(22, 17, 14), maxw=56):
        for sz in sizes:
            wgt = "Semibold" if sz == 22 else "Bold"
            if amb:
                wgt = "Regular"
            if text_w(c, s_, sz, wgt) <= maxw:
                return c.f(sz, wgt)
        return c.f(sizes[-1], "Regular" if amb else "Bold")

    def ball_text(x, y, value, label, vcol, lcol):
        if value is not None:
            d.text(c.p(x, y - 7 if label else y), value, font=val_font(value), fill=A("amb1") if amb else hx(vcol), anchor="mm")
        if label and not (small and value is None and False):
            # labels never touch the ring: max width 50, step 14 → 12 → 11
            lf = fit_font(c, label, (14, 12, 11) if not small else (13, 12, 11), "Semibold", 50)
            d.text(c.p(x, y + 15 if value is not None else y), label, font=lf,
                   fill=A("amb2") if amb else hx(lcol, 235), anchor="mm")

    for deg, kind, pl in balls:
        x, y = P(168, deg)
        if kind == "loop":
            mode_ = v["loop_mode"]; lh = d_["loopHealth"]
            if d_["first"] or lh == "NEVER" and mode_ != "no_aps":
                shell(x, y, T["ring_dim"], dash=True); ball_text(x, y, "--", L["loop"], T["txt3"], T["txt3"])
            elif mode_ == "no_aps":
                shell(x, y, T["ring_dim"]); ball_text(x, y, L["pump"], None, T["txt2"], T["txt3"])
            elif d_["noProfile"]:
                shell(x, y, T["ring_dim"]); ball_text(x, y, "--", L["loop"], T["txt3"], T["txt3"])
            elif mode_ == "off":
                shell(x, y, T["warn"])
                pause_glyph(d, c, x, y - 7, A("amb1") if amb else hx(T["warn"]), hollow=burn)
                ball_text(x, y, None, None, T["warn"], T["warn"])
                d.text(c.p(x, y + 15), L["off"], font=c.f(14, "Semibold"), fill=A("amb2") if amb else hx(T["warn"]), anchor="mm")
            elif mode_ == "open":
                shell(x, y, T["loop_ok"], width=1.6, dash=True); ball_text(x, y, L["open"], L["loop"], T["txt1"], T["loop_ok"])
            elif loop_neutral or lh == "BAD":
                # red budget: the slot owns the loop message (W6) or a bigger one (W2/W3) → neutral grey
                shell(x, y, T["ring_neutral"])
                ball_text(x, y, fmt_age(v["loop_age"], L), L["lgs"] if mode_ == "lgs" else L["loop"], T["txt2"], T["txt2"])
            else:
                ring = dict(OK=T["loop_ok"], WARN=T["warn"], NEUTRAL=T["ring_neutral"])[lh]
                shell(x, y, ring)
                vcol = T["txt1"] if lh in ("OK", "NEUTRAL") else ring
                ball_text(x, y, fmt_age(v["loop_age"], L), L["lgs"] if mode_ == "lgs" else L["loop"], vcol, ring)
        elif kind == "iob":
            s_ = v["iob"].strip()
            unknown = s_ in ("", "IOB", "--", "-.--")
            try:
                val = float(s_.replace(",", "."))
            except ValueError:
                val = None
            box = shell(x, y, T["ring_dim"])
            if not amb and val is not None and val > 0:
                frac = min(val / 6.0, 1.0)
                d.arc(box, -90, -90 + 360 * frac, fill=hx(T["ins"]), width=max(1, int(c.L(3))))
            if unknown:
                ball_text(x, y, "--", L["iob"], T["txt3"], T["txt3"])
            elif val == 0:
                ball_text(x, y, s_, L["iob"], T["txt3"], T["ins"])
            else:
                ball_text(x, y, s_, L["iob"], T["txt1"], T["ins"])
        elif kind == "basal":
            s_ = v["basal"].strip()
            if s_ in ("", "-.--U/h", "--"):
                shell(x, y, T["ring_dim"]); ball_text(x, y, "--", L["basal"], T["txt3"], T["txt3"])
            elif s_.endswith("%"):
                zero = s_.replace(" ", "") == "0%"
                # zero basal during a fresh low is the loop doing the right thing → neutral, not amber
                ring = (T["ring_neutral"] if low_now else T["warn"]) if zero else T["tbr"]
                lab = T["txt2"] if (zero and low_now) else ring
                shell(x, y, ring); ball_text(x, y, de_space(s_), L["tbr"], T["txt1"], lab)
            else:
                vs = re.sub(r"\s*U/h$", "", s_)
                zero = vs.startswith("0,00") or vs.startswith("0.00")
                warn0 = zero and not low_now
                ring = T["warn"] if warn0 else T["ring_neutral"]
                shell(x, y, ring); ball_text(x, y, vs, L["basal"], T["txt1"], T["warn"] if warn0 else T["txt2"])
        elif kind == "cob":
            s_ = v["cob"].strip()
            if s_ in ("", "--g", "--"):
                shell(x, y, T["ring_dim"]); ball_text(x, y, "--", L["cob"], T["txt3"], T["txt3"])
            elif s_ == "0g":
                shell(x, y, T["ring_dim"]); ball_text(x, y, de_space(s_), L["cob"], T["txt3"], T["txt3"])
            else:
                shell(x, y, T["ring_neutral"]); ball_text(x, y, de_space(s_), L["cob"], T["txt1"], T["txt2"])
        elif kind == "tt":
            shell(x, y, T["tt"]); ball_text(x, y, v["tt"].replace(" ", ""), L["tt"], T["txt1"], T["tt"])
        elif kind == "patient":
            m = pl
            ma = m["age"]
            mst = ma >= 12
            ss = sec_state(m)
            mh = T["r_stale"] if mst else (T["r_low"] if m["level"] < 0 else T["r_high"] if m["level"] > 0 else T["r_in"])
            box = shell(x, y, mh, width=2.5 if ss["low"] else 2)
            if ss["low"]:
                # shape cue (family §4.9): doubled outline = low, in every mode
                q = c.p(x, y); ri = c.L(29.5)
                d.ellipse([q[0] - ri, q[1] - ri, q[0] + ri, q[1] + ri],
                          outline=A("amb2") if amb else hx(mh), width=max(1, int(c.L(1.5 if not amb else 1.2))))
            nm = ellipsize(c, m["name"] or "P2", 12, "Semibold", 50)
            d.text(c.p(x, y - 18), nm, font=c.f(12, "Semibold"), fill=A("amb2") if amb else hx(T["txt2"]), anchor="mm")
            bgs = m["bg"] if ma < 60 else "---"
            fb = val_font(bgs, (20, 17, 14), 46 if ss["low"] else 52)
            bcol = A("amb1") if amb else hx(T["txt2"] if mst else T["r_low"] if ss["low"] else T["txt1"])
            bxo = -5 if ss["low"] else 0
            d.text(c.p(x + bxo, y + 1), bgs, font=fb, fill=bcol, anchor="mm")
            if ss["low"]:
                bb3 = d.textbbox(c.p(x + bxo, y + 1), bgs, font=fb, anchor="mm")
                rx_ = (bb3[2] / SS - c.cx) / c.s + 225 - c.dx
                draw_arrow(d, c, rx_ + 7, y + 1, "DOWN", 13, A("amb1") if amb else hx(T["r_low"]), 1.8)
            if ss["urgent"]:
                # urgent: filled mini TIEF badge replaces the age (age is fresh by definition)
                badge(d, c, x, y + 19, L["low"], A("amb1") if amb else hx(T["r_low"]), filled=True,
                      amb=amb, burn=burn, size=11, txtcol=(0, 0, 0, 255) if amb else None)
            if mst and bgs != "---":
                bb2 = d.textbbox(c.p(x, y + 1), bgs, font=fb, anchor="mm")
                d.line([(bb2[0] - c.L(2), c.p(0, y + 2)[1]), (bb2[2] + c.L(2), c.p(0, y + 2)[1])], fill=bcol,
                       width=max(1, int(c.L(2))))
            acol = (A("amb2") if amb else hx(T["alert"] if mst else T["warn"] if ma >= 6 else T["txt2"]))
            if not ss["urgent"]:
                d.text(c.p(x, y + 19), fmt_age(ma, L), font=c.f(12, "Semibold"), fill=acol, anchor="mm")
            if not amb and not small and m.get("loop_age") is not None:
                la = m["loop_age"]
                lcol = T["loop_ok"] if la <= 5 else T["warn"] if la < 30 else T["alert"]
                ox, oy = P(168 + 34, deg)
                q = c.p(ox, oy); r = c.L(4)
                d.ellipse([q[0] - r, q[1] - r, q[0] + r, q[1] + r], fill=hx(lcol), outline=(0, 0, 0, 255), width=int(c.L(1.5)))
            elif not amb and not small and m.get("loop_age") is None:
                ox, oy = P(168 + 34, deg)
                q = c.p(ox, oy); r = c.L(3.5)
                d.ellipse([q[0] - r, q[1] - r, q[0] + r, q[1] + r], fill=(0, 0, 0, 255), outline=hx(T["txt3"]), width=int(c.L(1.2)))
        # moon rim light
        if occl and occl[0] == deg and not amb:
            _, diff, bh = occl
            ha = deg + diff / bh * 90
            q = c.p(x, y); ro = c.L(35)
            d.arc([q[0] - ro, q[1] - ro, q[0] + ro, q[1] + ro], ha - 90 - 22, ha - 90 + 22, fill=hx("#FFFFFF", 220),
                  width=max(1, int(c.L(1.6))))

    # ---------------- bolus button (9 o'clock): shape, not alpha, separates the states
    if ball_drawn:
        x, y = P(168, 270)
        amt = "+" + f"{v['amount']:.1f}".replace(".", L["dec"])
        h, cr = 33, 16
        r0 = [*c.p(x - h, y - h), *c.p(x + h, y + h)]
        path = [c.p(px, py) for px, py in squircle(c, x, y, h + 0.5, cr)]
        ind = T["ball_idle"]
        if ball == "armed":
            # ARMED: solid fill, white text, white countdown
            glow_layer(img, c, lambda g, ox, oy: g.rounded_rectangle([r0[0] + ox, r0[1] + oy, r0[2] + ox, r0[3] + oy],
                                                                  radius=c.L(cr), fill=hx(T["ball_armed"], 150)),
                       8, (x - h, y - h, x + h, y + h))
            d = ImageDraw.Draw(img, "RGBA")
            d.rounded_rectangle(r0, radius=c.L(cr), fill=hx(T["ball_armed"]))
            d.line(path, fill=(255, 255, 255, 60), width=int(c.L(3.5)), joint="curve")
            if v["armed_left"] > 0:
                d.line(partial(path, v["armed_left"]), fill=(255, 255, 255, 255), width=int(c.L(3.5)), joint="curve")
            d.text(c.p(x, y - 7), amt, font=c.f(21, "Bold"), fill=(255, 255, 255, 255), anchor="mm")
            sub_ = L["again"] if not small and text_w(c, L["again"], 13, "Semibold") <= 56 else L["again_short"]
            d.text(c.p(x, y + 14), sub_, font=c.f(13, "Semibold"), fill=(255, 255, 255, 255), anchor="mm")
        elif ball == "idle":
            # IDLE: hollow, full 2 px indigo outline, indigo text
            d.rounded_rectangle(r0, radius=c.L(cr), fill=hx(T["shell"]), outline=hx(ind), width=max(1, int(c.L(2))))
            d.text(c.p(x, y - 7), amt, font=c.f(21, "Bold"), fill=hx(ind), anchor="mm")
            d.text(c.p(x, y + 14), L["unit"], font=c.f(14, "Semibold"), fill=hx(ind, 220), anchor="mm")
        elif ball == "disabled":
            # DISABLED: dashed txt3 outline + diagonal slash, everything txt3 (40 %)
            d.rounded_rectangle(r0, radius=c.L(cr), fill=hx(T["shell"]))
            for dsh in dash_path(path, c.L(7), c.L(5)):
                d.line(dsh, fill=hx(T["txt3"], 190), width=max(1, int(c.L(1.8))), joint="curve")
            d.text(c.p(x, y - 7), amt, font=c.f(21, "Bold"), fill=hx(T["txt3"], 150), anchor="mm")
            d.text(c.p(x, y + 14), L["unit"], font=c.f(14, "Semibold"), fill=hx(T["txt3"], 150), anchor="mm")
            d.line([c.p(x - 22, y + 22), c.p(x + 22, y - 22)], fill=hx(T["txt2"], 220), width=max(1, int(c.L(2.2))))
        elif ball in ("sent", "delivering"):
            # SENT: solid indigo outline at 55 %, static top arc, big '…' replaces the amount
            d.rounded_rectangle(r0, radius=c.L(cr), fill=hx(T["shell"]), outline=hx(ind, 140), width=max(1, int(c.L(2))))
            if ball == "sent":
                n = len(path); k = max(2, int(n * 0.08))
                d.line(path[:k] , fill=hx(ind), width=int(c.L(3)), joint="curve")
                d.line(path[-k:], fill=hx(ind), width=int(c.L(3)), joint="curve")
                d.text(c.p(x, y - 9), "…", font=c.f(30, "Bold"), fill=hx(ind), anchor="mm")
            else:
                d.line(partial(path, 0.45), fill=hx(ind), width=int(c.L(3)), joint="curve")
                d.text(c.p(x, y - 7), "45 %", font=c.f(17, "Bold"), fill=hx(ind), anchor="mm")
            d.text(c.p(x, y + 15), f"{amt} {L['unit']}", font=c.f(12, "Semibold"), fill=hx(T["txt2"]), anchor="mm")
        elif ball == "delivered":
            d.rounded_rectangle(r0, radius=c.L(cr), fill=hx(T["shell"]), outline=hx(ind), width=max(1, int(c.L(2))))
            q = [c.p(x - 10, y - 7), c.p(x - 2, y + 1), c.p(x + 11, y - 13)]
            d.line(q, fill=hx(ind), width=int(c.L(3.5)), joint="curve")
            d.text(c.p(x, y + 15), f"{amt} {L['unit']}", font=c.f(13, "Bold"), fill=hx(ind), anchor="mm")

    # ---------------- toast (system overlay, illustration only)
    if v.get("tap") and verdict:
        v["toast"] = L[TOAST_KEY[verdict]]
    if v["toast"] and not amb:
        lines = v["toast"].split("\n")
        fh = c.f(15, "Medium")
        w = max(fh.getlength(t) for t in lines) / SS / c.s
        r0 = [*c.p(225 - w / 2 - 14, 300), *c.p(225 + w / 2 + 14, 300 + 22 * len(lines) + 12)]
        d.rounded_rectangle(r0, radius=c.L(14), fill=hx("#2B2F38", 245))
        for i, t in enumerate(lines):
            d.text(c.p(225, 317 + 22 * i), t, font=fh, fill=hx(T["txt1"]), anchor="mm")

    # ---------------- tap zones overlay (spec illustration)
    if v["zones"]:
        lay = Image.new("RGBA", img.size, (0, 0, 0, 0)); g = ImageDraw.Draw(lay)
        g.rectangle([*c.p(143, 268), *c.p(299, 340)], fill=(90, 200, 250, 70), outline=(90, 200, 250, 255), width=SS)
        g.rectangle([*c.p(150, 35), *c.p(300, 80)], fill=(255, 255, 255, 50), outline=(255, 255, 255, 220), width=SS)
        q = c.p(*P(168, 270)); r = c.L(44)
        g.ellipse([q[0] - r, q[1] - r, q[0] + r, q[1] + r], fill=(120, 136, 255, 70), outline=(120, 136, 255, 255), width=SS)
        q = c.p(*P(168, 45)); r = c.L(36)
        g.ellipse([q[0] - r, q[1] - r, q[0] + r, q[1] + r], outline=(64, 214, 140, 255), width=SS)
        img.alpha_composite(lay)
        d = ImageDraw.Draw(img, "RGBA")
        for t_, xy in (("tap.graph", (221, 352)), ("tap.menu", (225, 25)), ("tap.ball r44", (57, 280)), ("tap.loop", (330, 150))):
            d.text(c.p(*xy), t_, font=c.f(12, "Bold"), fill=(255, 255, 255, 255), anchor="mm")

    # ---------------- downsample, low-bit, mask
    out = img.resize((v["W"], v["H"]), Image.LANCZOS).convert("RGB")
    if lowbit:
        out = out.convert("L").point(lambda p: 255 if p > 90 else 0).convert("RGB")
    mask = Image.new("L", (v["W"], v["H"]), 0)
    md = ImageDraw.Draw(mask)
    if v["shape"] == "round":
        md.ellipse([0, 0, v["W"] - 1, v["H"] - 1], fill=255)
    elif v["shape"] == "flat":
        md.ellipse([0, 0, v["W"] - 1, v["W"] - 1], fill=255)
        md.rectangle([0, v["H"], v["W"], v["W"]], fill=0)
    else:
        md.rounded_rectangle([0, 0, v["W"] - 1, v["H"] - 1], radius=int(min(v["W"], v["H"]) * 0.12), fill=255)
    res = Image.new("RGB", (v["W"], v["H"]), (0, 0, 0))
    res.paste(out, (0, 0), mask)
    return res, mask


def simplified(**ov):
    """sys.simplified: ONE shared SimpleUi reference for the family (charging / simplify_ui).
    Layout: time on top (y 112, 44 px Regular), BG digits txt1 (face font) centred at y 226, trend glyph
    right of the digits is the only range-hue element, then one info line at y 318:
    age (txt2; warn when AGING) or the slot text for W2/W3. Low = TIEF badge above the digits
    (outlined; filled for urgent), stale = strike. Ambient: same layout in amb greys / white."""
    v = dict(BASE); v.update(ov)
    L = STR[v["lang"]]
    amb = v["mode"] != "interactive"
    lowbit = "low_bit" in v["mode"]
    c = Ctx(v); d_ = derive(v)
    img = Image.new("RGBA", (450 * SS, 450 * SS), (0, 0, 0, 255)); d = ImageDraw.Draw(img, "RGBA")
    W1 = (255, 255, 255, 255) if lowbit else hx(T["amb1"]) if amb else hx(T["txt1"])
    W2 = (255, 255, 255, 255) if lowbit else hx(T["amb2"]) if amb else hx(T["txt2"])
    rng = d_["range"]; fresh = d_["bgAge"] in ("FRESH", "AGING")
    d.text(c.p(225, 112), v["time"], font=c.f(44, "Regular"), fill=W1, anchor="mm")
    sgvs = v["bgstr"] or (str(v["sgv"]) if v["units"] == "mgdl" else mmol(v["sgv"]))
    if d_["bgAge"] == "NONE" or (v["age"] is not None and v["age"] >= 60):
        sgvs = "---"
    f = c.f(112, "Light" if amb else "Regular")
    bgc = W1 if fresh else W2
    showarr = fresh and v["trend"] != "NONE"
    wbg = f.getlength(sgvs) / SS / c.s
    bx = 225 - (44 if showarr else 0) / 2
    d.text(c.p(bx, 226), sgvs, font=f, fill=bgc, anchor="mm")
    bb = d.textbbox(c.p(bx, 226), sgvs, font=f, anchor="mm")
    rx = (bb[2] / SS - c.cx) / c.s + 225; lx = (bb[0] / SS - c.cx) / c.s + 225
    if showarr:
        draw_arrow(d, c, rx + 26, 228, v["trend"], 44, W1 if amb else hx(RANGE_HUE[rng]), 2.4 if amb else 4.2)
    if not fresh and sgvs != "---":
        d.line([c.p(lx - 6, 230), c.p(rx + 6, 230)], fill=bgc, width=int(c.L(2 if amb else 3)))
    if fresh and rng in ("LOW", "URGENT_LOW"):
        badge(d, c, 225, 160, L["low"], W1 if amb else hx(T["r_low"]), filled=rng == "URGENT_LOW",
              amb=amb, txtcol=(0, 0, 0, 255) if amb else None)
    # info line: W2 / W3 / Kein BG text, else the age (always shown, warn when aging)
    slot = status_slot(v, d_, L, True)
    if slot and slot[0] in (2, 3):
        d.text(c.p(225, 318), ellipsize_keep_suffix(c, slot[1], 22, "Medium", 300), font=c.f(22, "Medium"),
               fill=W1 if amb else hx(T["alert"]), anchor="mm")
    else:
        col = (W1 if amb else hx(T["warn"])) if d_["bgAge"] == "AGING" else W2
        t = fmt_age(v["age"], L)
        if d_["phoneGlyph"]:
            d.text(c.p(225 - 12, 318), t, font=c.f(22, "Medium"), fill=col, anchor="mm")
            phone_off_glyph(d, c, 225 + 16, 318, W2)
        else:
            d.text(c.p(225, 318), t, font=c.f(22, "Medium"), fill=col, anchor="mm")
    out = img.resize((450, 450), Image.LANCZOS).convert("RGB")
    if lowbit:
        out = out.convert("L").point(lambda p: 255 if p > 90 else 0).convert("RGB")
    m = Image.new("L", (450, 450), 0); ImageDraw.Draw(m).ellipse([0, 0, 449, 449], fill=255)
    r = Image.new("RGB", (450, 450)); r.paste(out, (0, 0), m)
    return r, m


def composite(kind, items, cs):
    """Comparison tiles: '__pair__' = two faces at half size; '__balls__' = ball crops at 1.6x."""
    cell = Image.new("RGB", (cs, cs), (34, 36, 42)); cd = ImageDraw.Draw(cell)
    n = len(items)
    if kind == "__pair__":
        sz = (cs - 24) // 2
        for i, (lab, kw) in enumerate(items):
            im, m = render(**kw)
            im = im.resize((sz, sz), Image.LANCZOS); m = m.resize((sz, sz), Image.LANCZOS)
            x0 = 8 + i * (sz + 8); y0 = (cs - sz) // 2
            cell.paste(im, (x0, y0), m)
            cd.text((x0 + sz // 2, y0 - 14), lab, font=LBL2, fill=(235, 236, 242), anchor="mm")
    else:
        crop = 104; z = (cs - 16 - 8 * (n - 1)) // n
        for i, (lab, kw) in enumerate(items):
            im, _ = render(**kw)
            bx, by = P(168, 270)
            box = (int(bx - crop / 2), int(by - crop / 2), int(bx + crop / 2), int(by + crop / 2))
            tile = im.crop(box).resize((z, z), Image.LANCZOS)
            x0 = 8 + i * (z + 8); y0 = (cs - z) // 2
            cell.paste(tile, (x0, y0))
            cd.text((x0 + z // 2, y0 + z + 16), lab, font=LBL2, fill=(235, 236, 242), anchor="mm")
    return cell


# ------------------------------------------------------------------ sheets
LBL = ImageFont.truetype(FONT, 17)
LBL2 = ImageFont.truetype(FONT, 13)


def sheet(name, tiles, cols=4, cs=450):
    tw, th = cs + 16, cs + 70
    rows = (len(tiles) + cols - 1) // cols
    sh = Image.new("RGB", (cols * tw + 16, rows * th + 56), (22, 23, 27))
    sd = ImageDraw.Draw(sh)
    sd.text((16, 18), f"AAPS Orbit · {name}", font=ImageFont.truetype(FONT, 22), fill=(230, 232, 240), anchor="lm")
    for i, (sid, note, kw) in enumerate(tiles):
        x0, y0 = 16 + (i % cols) * tw, 40 + (i // cols) * th
        if isinstance(kw, tuple):
            cell = composite(kw[0], kw[1], cs)
        else:
            if kw.get("__simple__"):
                k2 = dict(kw); k2.pop("__simple__")
                im, m = simplified(**k2)
            else:
                im, m = render(**kw)
            cell = Image.new("RGB", (cs, cs), (34, 36, 42))
            ox, oy = (cs - im.width) // 2, (cs - im.height) // 2
            cell.paste(im, (ox, oy), m)
        sh.paste(cell, (x0, y0))
        sd.text((x0 + cs // 2, y0 + cs + 20), sid, font=LBL, fill=(235, 236, 242), anchor="mm")
        sd.text((x0 + cs // 2, y0 + cs + 44), note, font=LBL2, fill=(150, 156, 172), anchor="mm")
    p = os.path.join(OUT, f"orbit_states_{name}.png")
    sh.save(p, optimize=True)
    print(p, len(tiles))


ON = dict(ball="idle")
LOWBG = dict(sgv=62, level=-1, trend="DOWN45", dmg=-6, delta="-0,3")
LOWFLAT = dict(sgv=65, level=-1, trend="FLAT", dmg=-1, delta="-0,1")
URG = dict(sgv=50, level=-1, trend="DOWN2", dmg=-14, delta="-0,8")
STALE17 = dict(age=17, loop_age=19)
P2 = dict(name="Tom", bg="7,9", mg=142, level=0, age=3, loop_age=2)
P3 = dict(name="Mia", bg="12,1", mg=218, level=1, age=4, loop_age=9)
P2LOW = dict(P2, bg="3,4", mg=61, level=-1, trend="DOWN45")
P2URG = dict(P2, bg="2,8", mg=50, level=-1, trend="DOWN2")

if __name__ == "__main__":
    import sys
    only = sys.argv[1:] or None

    def want(n):
        return only is None or n in only

    if want("data"):
        sheet("data", [
            ("bg.in_range", "6,4 → +0,1 · 1′ (age.fresh)", dict()),
            ("bg.high", "11,8 ↗ amber corona", dict(sgv=212, level=1, trend="UP45", dmg=6, delta="+0,4")),
            ("bg.very_high", "16,1 + corona +20 %", dict(sgv=290, level=1, trend="UP", dmg=8, delta="+0,6")),
            ("bg.low", "3,4 red digits + outlined TIEF", LOWBG),
            ("bg.urgent_low", "2,8 ⇊ filled TIEF + doubled red track", URG),
            ("bg.low vs bg.urgent_low", "outline vs filled badge, single vs doubled track",
             ("__pair__", [("bg.low 3,4", LOWBG), ("bg.urgent_low 2,8", URG)])),
            ("bg.sentinel_low", "phone '2,2' = urgent low", dict(sgv=39, level=-1, trend="DOWN", dmg=-9, delta="-0,4", bgstr="2,2")),
            ("bg.sentinel_high", "'22,2+' + outlined HOCH", dict(sgv=400, level=1, trend="UP", dmg=9, delta="+0,5", bgstr="22,2")),
            ("age.aging", "8′ in warn", dict(age=8, data_age=8)),
            ("age.stale", "17′ · W3 pill, strike, loop grey", dict(age=17, data_age=17, loop_age=19)),
            ("age.very_stale", "42′", dict(age=42, data_age=42, loop_age=44)),
            ("age.very_stale ≥60′", "'---', 3 Std (=conn.restored_old_data)", dict(age=180, data_age=180, loop_age=182)),
            ("age.none", "'Kein BG' warn in slot A", dict(age=None, data_age=3, hist="empty")),
            ("trend.fast", "⇈ double head", dict(sgv=160, level=0, trend="UP2", dmg=12, delta="+0,7")),
            ("trend.fast (X/DOWN3)", "thicker double head", dict(sgv=98, trend="DOWN3", dmg=-22, delta="-1,2")),
            ("trend.none", "'??' → no glyph, BG recentred", dict(trend="NONE")),
            ("trend.hidden_pref", "key_show_direction off", dict(show=dict(direction=False))),
            ("delta.missing", "'--' in txt3", dict(delta="--", dmg=None)),
            ("delta.wide", "'-12,5' (mg/dL detailed)", dict(units="mgdl", sgv=142, delta="-12,5", trend="DOWN45", dmg=-12)),
            ("delta.hidden_pref", "key_show_delta off", dict(show=dict(delta=False))),
            ("graph.empty", "targets only", dict(hist="empty")),
            ("graph.gaps", "line breaks, no interpolation", dict(hist="gaps")),
            ("graph.no_predictions", "right of now empty", dict(pred=False)),
            ("graph.timeframe_changed", "caption 1,5 s", dict(caption="5 Std", tf_h=5)),
            ("conn.first_start", "W1, skeleton", dict(first=True, age=None, loop_age=None, iob="IOB", cob="--g",
                                                      basal="-.--U/h", delta="--", hist="empty", data_age=999)),
        ])
    if want("indicators"):
        sheet("indicators", [
            ("iob.normal", "value txt1, arc + label ins", dict()),
            ("iob.zero", "dim value, empty arc", dict(iob="0,00")),
            ("iob.negative", "minus, empty arc", dict(iob="-0,35")),
            ("iob.large", "arc capped", dict(iob="12,40")),
            ("iob.unknown", "'--'", dict(iob="-.--")),
            ("cob.values 45 g", "neutral ring, '45g' → '45 g'", dict(cob="45g")),
            ("cob.values 20(30) g", "14 px fallback", dict(cob="20(30)g")),
            ("cob unknown", "'--g' → '--'", dict(cob="--g")),
            ("basal.normal", "'0,85U/h' → 0,85", dict()),
            ("basal.tbr_percent", "teal TBR '120 %'", dict(basal="120%")),
            ("basal.tbr_percent 0 %", "warn (BG in range)", dict(basal="0%")),
            ("basal.zero_abs", "'0,00' warn (BG in range)", dict(basal="0,00U/h")),
            ("basal.zero_abs + bg.low", "neutral: loop doing its job", dict(basal="0,00U/h", **LOWBG)),
            ("basal 0 % + bg.urgent_low", "neutral, one red group only", dict(basal="0%", **URG)),
            ("basal.unknown", "'--'", dict(basal="-.--U/h")),
            ("tt.active", "TT 8,0 at 315°", dict(tt="8,0", tt_level=2)),
            ("tt.active (range)", "'4,4 - 5,0'", dict(tt="4,4 - 5,0", tt_level=2)),
            ("tt.profile / aps_adjusted / unknown", "ball absent", dict(tt="5,6", tt_level=1)),
            ("loop.lgs", "label LGS", dict(loop_mode="lgs")),
            ("pref.all_on", "+ TT + ball + KW + seconds", dict(tt="8,0", tt_level=2, ball="idle", week=42, seconds=":07",
                                                               cob="12g", basal="110%")),
            ("pref.minimal", "BG + direction + time", dict(show=dict(delta=False, ago=False, iob=False, cob=False,
                                                                     basal=False, tt=False, loop=False, date=False))),
            ("pref.minimal + aging", "age is not optional", dict(age=8, data_age=8, show=dict(delta=False, ago=False, iob=False, cob=False,
                                                                                                 basal=False, tt=False, loop=False, date=False))),
            ("pref.minimal + loop.bad", "W6 pill only, LOOP ball not forced", dict(loop_age=23, show=dict(delta=False, ago=False, iob=False, cob=False,
                                                                                                 basal=False, tt=False, loop=False, date=False))),
        ])
    if want("warnings"):
        sheet("warnings", [
            ("conn.first_start", "W1 Warte auf AAPS", dict(first=True, age=None, loop_age=None, iob="IOB", cob="--g",
                                                          basal="-.--U/h", delta="--", hist="empty", data_age=999, ball="idle")),
            ("conn.phone_ok", "nothing extra", dict(ball="idle")),
            ("conn.phone_lost_fresh", "W10 glyph, ball disabled", dict(phone=False, ball="idle")),
            ("conn.phone_lost_fresh + bg.low", "glyph never suppressed", dict(phone=False, ball="idle", **LOWBG)),
            ("conn.phone_lost_stale", "W2 · reds: pill=1 (loop grey)", dict(phone=False, age=14, data_age=14, loop_age=16, ball="idle")),
            ("conn.phone_silent", "W3 · reds: pill=1 (loop grey)", dict(age=17, data_age=17, loop_age=19, ball="idle")),
            ("conn.no_profile", "W4, '--' values, ball disabled", dict(loop_mode="no_profile", iob="", cob="--g", basal="-.--U/h", ball="idle")),
            ("loop.ok", "2′ green", dict()),
            ("loop.warn", "9′ amber", dict(loop_age=9)),
            ("loop.bad 15–29′", "W6 amber (openApsStatus = last TBR)", dict(loop_age=23)),
            ("loop.bad ≥30′", "W6 red · reds: pill=1", dict(loop_age=34)),
            ("loop.bad + bg.low", "reds: BG group + pill = 2, ring grey", dict(loop_age=34, **LOWBG)),
            ("prio urgent low + loop.bad", "W6 still shown · reds = 2", dict(loop_age=34, ball="idle", **URG)),
            ("loop.never", "hollow grey, never red", dict(loop_age=None)),
            ("loop.open", "dashed ring, Offen", dict(loop_mode="open", loop_age=40)),
            ("loop.off", "W5 + pause glyph + 'aus'", dict(loop_mode="off", loop_age=3, ball="idle")),
            ("loop.unknown_mode (client)", "age only", dict(loop_mode="unknown", loop_age=8, ball="off")),
            ("loop.no_aps (pumpcontrol)", "grey Pumpe, no preds", dict(loop_mode="no_aps", loop_age=None, pred=False, ball="idle")),
            ("pump.res_critical", "W7 'Reservoir 5 U'", dict(res="5U", res_level=2)),
            ("batt.phone_low", "W8 warn", dict(batt="18", batt_level=0)),
            ("batt.phone_low <10 %", "W8 alert", dict(batt="7", batt_level=0)),
            ("pump.res_low", "W9 'Reservoir 18 U'", dict(res="18U", res_level=1)),
            ("suppression (low + res crit)", "W7–W9 hidden", dict(res="5U", res_level=2, **LOWBG)),
            ("pump.res_unknown", "never red, hidden", dict(res="", res_level=2)),
            ("locale.long (fr) W2", "words cut, '· 14′' kept", dict(lang="fr", phone=False, age=14, data_age=14, loop_age=16)),
            ("locale.long (fr) labels", "BOUCLE steps down to fit", dict(lang="fr", loop_age=3, ball="idle")),
        ])
    if want("ball"):
        B = lambda **k: dict(ball="idle", **k)
        sheet("ball", [
            ("ball.off", "pref off: no shape, no hit", dict(ball="off")),
            ("ball.hidden", "wearControl off / amount invalid", dict(ball="hidden")),
            ("ball.idle", "hollow, indigo '+0,5' / 'U'", B()),
            ("ball.idle 1,0 U", "amount from pref", B(amount=1.0)),
            ("idle | disabled | sent | armed", "shape, not alpha",
             ("__balls__", [("idle", B()), ("disabled", B(phone=False)), ("sent", dict(ball="sent")),
                            ("armed", dict(ball="armed", armed_left=0.6))])),
            ("ball.disabled NO_PHONE", "dashed + slash + toast", B(phone=False, tap=True)),
            ("ball.disabled STALE_DATA", "W3 + disabled", B(age=17, data_age=17, loop_age=19, tap=True)),
            ("ball.disabled LOW_BG (urgent)", "toast 'BG zu niedrig'", B(tap=True, **URG)),
            ("ball.disabled LOW_BG (low falling)", "low + ↘ = disabled", B(**LOWBG)),
            ("ball.disabled NO_PROFILE", "W4 + disabled", B(loop_mode="no_profile", iob="", cob="--g", basal="-.--U/h")),
            ("ball.disabled LOOP_OFF", "W5 + disabled", B(loop_mode="off", tap=True)),
            ("ball.disabled MAX_BOLUS", "max 0,3 U: disabled + toast", B(max_bolus=0.3, tap=True)),
            ("ball.armed t=0", "solid fill, countdown full (800 ms)", dict(ball="armed", armed_left=1.0)),
            ("ball.armed t=480 ms", "40 % left", dict(ball="armed", armed_left=0.4)),
            ("ball.armed + bg.low flat", "allowed (not falling) · indigo ≠ red", dict(ball="armed", armed_left=0.7, **LOWFLAT)),
            ("ball.sent", "'…' + static top arc ≤30 s", dict(ball="sent")),
            ("ball.sent + tap", "toast pending", dict(ball="sent", toast=STR["de"]["toast_pending"])),
            ("ball.delivering (P2)", "progress on outline", dict(ball="delivering")),
            ("ball.delivered (P2)", "check 2 s", dict(ball="delivered")),
            ("ball.armed @384", "'2×' short form (s < 0.9)", dict(W=384, H=384, ball="armed", armed_left=0.6)),
            ("tap zones", "graph / menu / ball r44 / loop", B(zones=True)),
            ("ball en", "'+0.5' / again", dict(ball="armed", lang="en", time="2:19", ampm="PM", armed_left=0.6, bgstr="6.4", delta="+0.1", iob="1.25", basal="0.85U/h")),
        ])
    if want("ambient"):
        A_ = lambda m, **k: dict(mode=m, ball="idle", **k)
        S_ = lambda **k: dict(__simple__=True, **k)
        sheet("ambient", [
            ("mode.ambient in", "solid track, ball gone", A_("ambient")),
            ("mode.ambient high", "dashed track", A_("ambient", sgv=212, level=1, trend="UP45", delta="+0,4")),
            ("mode.ambient low", "doubled track + outlined TIEF", A_("ambient", **LOWBG)),
            ("mode.ambient urgent_low", "doubled + filled TIEF + Regular digits", A_("ambient", **URG)),
            ("mode.ambient_warning stale", "text + strike", A_("ambient", age=17, data_age=17, loop_age=19)),
            ("mode.ambient loop.off", "W5 kept", A_("ambient", loop_mode="off")),
            ("mode.ambient W7 dropped", "res crit: only W1–W6", A_("ambient", res="5U", res_level=2)),
            ("mode.ambient phone_lost_fresh", "glyph kept", A_("ambient", phone=False)),
            ("mode.ambient first_start", "W1", A_("ambient", first=True, age=None, loop_age=None, iob="IOB", hist="empty", data_age=999)),
            ("mode.ambient_low_bit in", "1-bit, no track", A_("ambient_low_bit")),
            ("mode.ambient_low_bit low", "white double track + TIEF", A_("ambient_low_bit", **LOWBG)),
            ("mode.ambient_low_bit stale", "", A_("ambient_low_bit", age=17, data_age=17, loop_age=19, phone=False)),
            ("mode.ambient_burn_in in", "outlined digits, shift (3,-2)", A_("ambient_burn_in", shift=(3, -2))),
            ("mode.ambient_burn_in urgent", "TIEF doubled outline (no fill)", A_("ambient_burn_in", shift=(-4, 2), **URG)),
            ("mode.ambient_low_bit_burn_in", "union", A_("ambient_low_bit_burn_in", shift=(-2, 4))),
            ("mode.ambient multi.three", "blocks: name+BG+age", A_("ambient", name="Lena", multi=[P2, P3])),
            ("mode.ambient multi secondary low/urgent", "doubled block · filled TIEF · W3.5", A_("ambient", name="Lena", multi=[P2LOW, P2URG | dict(name="Mia")])),
            ("sys.simplified", "SimpleUi reference: range hue on the arrow only", S_()),
            ("sys.simplified aging", "age in warn", S_(age=8, data_age=8)),
            ("sys.simplified low", "red arrow + outlined TIEF", S_(**LOWBG)),
            ("sys.simplified urgent_low", "filled TIEF", S_(**URG)),
            ("sys.simplified phone_lost (fresh)", "age + phone glyph", S_(phone=False)),
            ("sys.simplified phone_lost_stale", "W2 text + strike", S_(phone=False, age=14, data_age=14, loop_age=16)),
            ("sys.simplified stale", "W3 text + strike", S_(age=17, data_age=17, loop_age=19)),
            ("sys.simplified ambient low", "greys, TIEF carries the low", S_(mode="ambient", **LOWBG)),
            ("sys.simplified low-bit urgent", "", S_(mode="ambient_low_bit", **URG)),
        ])
    if want("units"):
        sheet("units", [
            ("units.mmol", "6,4", dict()),
            ("units.mmol 4-char", "11,8", dict(sgv=212, level=1, trend="UP45", dmg=6, delta="+0,4")),
            ("units.mgdl", "115, delta +3", dict(units="mgdl", delta="+3")),
            ("units.mgdl 400+", "sentinel high + HOCH", dict(units="mgdl", sgv=400, level=1, trend="UP", dmg=9, delta="+12", bgstr="400")),
            ("units.mgdl 39 TIEF", "sentinel low = urgent", dict(units="mgdl", sgv=39, level=-1, trend="DOWN", dmg=-9, delta="-9", bgstr="39")),
            ("units.mgdl stale", "strike, W3", dict(units="mgdl", age=17, data_age=17, loop_age=19, delta="+3")),
            ("units.mgdl all-on", "IOB/basal from phone", dict(units="mgdl", delta="+3", ball="idle", tt="140", tt_level=2, week=42)),
            ("units.phone_locale", "EN phone strings on DE watch: '6.4' kept", dict(bgstr="6.4", delta="+0.1", iob="1.25", basal="0.85U/h", ball="idle")),
            ("locale.de_24h", "14:19 · Fr 17. Okt", dict(ball="idle")),
            ("locale.en_12h", "2:19 PM · Fri, Oct 17", dict(lang="en", time="2:19", ampm="PM", iob="1.25", delta="+0.1",
                                                          basal="0.85U/h", ball="idle", bgstr="6.4")),
            ("pref.date + week", "Fr 17. Okt · KW 42", dict(week=42)),
            ("pref.date off", "bottom gap closes", dict(show=dict(date=False))),
            ("pref.seconds", ":07 interactive only", dict(seconds=":07")),
            ("delta detailed", "'+0,08'", dict(delta="+0,08")),
            ("sys.preview", "deterministic sample", dict(time="10:08", minute=8, age=1, loop_age=2)),
            ("locale.long (fr)", "labels + date", dict(lang="fr", ball="idle", loop_age=3)),
        ])
    if want("multi"):
        sheet("multi", [
            ("multi.one", "name hidden", dict(name="Lena", loop_mode="unknown")),
            ("multi.two", "P2 at 225°, COB yields", dict(name="Lena", multi=[P2], loop_mode="unknown")),
            ("multi.three", "P2 225° / P3 315°", dict(name="Lena", multi=[P2, P3], loop_mode="unknown")),
            ("multi.three + TT", "primary TT → meta row", dict(name="Lena", multi=[P2, P3], tt="8,0", tt_level=2, loop_mode="unknown")),
            ("multi.secondary_stale", "only that ball greys", dict(name="Lena", multi=[dict(P2, age=24, loop_age=30), P3], loop_mode="unknown")),
            ("multi.secondary_low", "doubled ring + ↓ (stays in block)", dict(name="Lena", multi=[P2LOW, P3], loop_mode="unknown")),
            ("multi.secondary_urgent_low", "filled TIEF + face pill W3.5", dict(name="Lena", multi=[P2URG, P3], loop_mode="unknown")),
            ("multi.secondary_very_stale", "≥30′ → pill W6.5 (warn)", dict(name="Lena", multi=[P2, dict(P3, age=42, loop_age=None)], loop_mode="unknown")),
            ("multi.secondary urgent + primary low", "both visible, pill = Tom", dict(name="Lena", multi=[P2URG, P3], loop_mode="unknown", **LOWFLAT)),
            ("multi.patient_name", "'Annabelle-Sophie' ellipsised", dict(name="Annabelle-Sophie", multi=[dict(P2, name="Maximilian"), P3], loop_mode="unknown")),
            ("multi.three @384", "loop dots dropped", dict(W=384, H=384, name="Lena", multi=[P2, P3], loop_mode="unknown")),
            ("multi.three mg/dL", "", dict(units="mgdl", delta="+3", name="Lena", multi=[dict(P2, bg="142"), dict(P3, bg="218")], loop_mode="unknown")),
            ("multi + primary stale", "primary W3; blocks unaffected", dict(name="Lena", multi=[P2, P3], age=17, data_age=17, loop_age=19, loop_mode="unknown")),
            ("flavor.client", "no ball, loop age from NS", dict(loop_mode="unknown", loop_age=8, pred=False)),
            ("multi.switched", "hero=Tom chip; ds0-only values hidden", dict(switched=True, name="Tom", multi=[dict(name="Lena", bg="6,4", mg=115, level=0, age=1, loop_age=2)],
                                                                         loop_mode="unknown", sgv=142, delta="+0,2", ball="idle")),
            ("multi.switched + hero low", "TIEF under the chip", dict(switched=True, name="Tom", multi=[dict(name="Lena", bg="6,4", mg=115, level=0, age=1, loop_age=2)],
                                                                   loop_mode="unknown", ball="idle", **LOWBG)),
        ])
    if want("shapes"):
        sheet("shapes", [
            ("round 384 (Pixel Watch 1/2)", "s 0.853", dict(W=384, H=384, ball="idle", tt="8,0", tt_level=2)),
            ("round 384 all-on stale", "", dict(W=384, H=384, ball="idle", tt="8,0", tt_level=2, age=17, data_age=17, loop_age=19, week=42)),
            ("round 384 urgent low", "badge + doubled track fit", dict(W=384, H=384, ball="idle", **URG)),
            ("round 396 (GW 40 mm)", "s 0.88", dict(W=396, H=396, ball="idle")),
            ("round 416", "s 0.924", dict(W=416, H=416, ball="armed")),
            ("round 450 (GW5 Pro)", "design canvas", dict(ball="idle")),
            ("round 456 (Pixel Watch 3)", "s 1.013", dict(W=456, H=456, ball="idle")),
            ("round 480 (GW 47 mm)", "s 1.067", dict(W=480, H=480, ball="idle", tt="8,0", tt_level=2) ),
            ("square 360", "inscribed circle, corners black", dict(W=360, H=360, shape="square", ball="idle")),
            ("square 450", "", dict(W=450, H=450, shape="square", ball="idle", res="5U", res_level=2)),
            ("rect 368x448", "circle centred vertically", dict(W=368, H=448, shape="square", ball="idle")),
            ("flat tyre 360x330", "date stays above chin", dict(W=360, H=330, shape="flat", ball="idle", week=42)),
            ("flat tyre 360x330 ambient", "", dict(W=360, H=330, shape="flat", mode="ambient")),
        ], cs=480)
