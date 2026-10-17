"""AAPS Halo: state-matrix sheets. Run: uv run --with pillow python3 halo_states.py [sheet ...]"""
import os, sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from halo_render import model, render, MG

OUT = os.path.dirname(os.path.abspath(__file__))
FONT = "/System/Library/Fonts/SFNS.ttf"
LBL = ImageFont.truetype(FONT, 17)
LBL2 = ImageFont.truetype(FONT, 13)


def bg(mmol, mgdl=None, level=0, trend="FLAT", delta="+0,1", units="mmol"):
    mg = mgdl if mgdl is not None else round(float(mmol.replace(",", ".")) * MG)
    return dict(sgv=float(mg), sgv_str=(mmol if units == "mmol" else str(mg)), level=level, trend=trend,
                delta=delta, units=units)


def tail_from(mg, dm):  # 30 min ago value for the tail
    return (mg - 6 * dm,)


def masked(im, shp):
    mk = Image.new("L", im.size, 0); md = ImageDraw.Draw(mk)
    if shp[0] == "round": md.ellipse((0, 0, im.width - 1, im.height - 1), fill=255)
    elif shp[0] == "flat":
        md.ellipse((0, 0, im.width - 1, im.height - 1), fill=255)
        md.rectangle((0, int(im.height * shp[2] / shp[1]), im.width, im.height), fill=0)
    else: md.rectangle((0, 0, im.width, im.height), fill=255)
    return mk


def tile_image(kw, cs):
    cell = Image.new("RGB", (cs, cs), (34, 36, 42))
    cd = ImageDraw.Draw(cell)
    if "compose" in kw:  # side-by-side comparison of two faces
        n = len(kw["compose"]); sz = cs // n - 6
        for k, (sub, lab) in enumerate(zip(kw["compose"], kw["labels"])):
            im = render(model(**sub)).resize((sz, sz), Image.LANCZOS)
            x = 3 + k * (sz + 6) + 3; y = (cs - sz) // 2 - 10
            cell.paste(im, (x, y), masked(im, ("round", sz)))
            cd.text((x + sz // 2, y + sz + 18), lab, font=LBL2, fill=(220, 222, 230), anchor="mm")
        return cell
    if "ballcmp" in kw:  # enlarged ball crops, same scale, side by side
        states = kw["ballcmp"]
        cols = 2; cw = cs // cols; ch = cs // ((len(states) + 1) // 2)
        for k, (lab, sub) in enumerate(states):
            im = render(model(**sub))
            crop = im.crop((4, 175, 92, 263)).resize((int(cw * 0.62), int(cw * 0.62)), Image.LANCZOS)
            x = (k % cols) * cw + (cw - crop.width) // 2; y = (k // cols) * ch + 12
            cell.paste(crop, (x, y))
            cd.text((x + crop.width // 2, y + crop.height + 16), lab, font=LBL2, fill=(220, 222, 230), anchor="mm")
        return cell
    im = render(model(**kw))
    if im.width > cs or im.height > cs:
        im.thumbnail((cs, cs), Image.LANCZOS)
    shp = kw.get("shape", ("round", 450))
    mk = masked(im, shp)
    rim = Image.new("RGB", im.size, (70, 72, 80))
    cell.paste(rim, ((cs - im.width) // 2, (cs - im.height) // 2), mk.filter(ImageFilter.MaxFilter(3)))
    cell.paste(im, ((cs - im.width) // 2, (cs - im.height) // 2), mk)
    return cell


def sheet(name, tiles, cols=4, cs=450):
    tw, th = cs + 16, cs + 70
    rows = (len(tiles) + cols - 1) // cols
    sh = Image.new("RGB", (cols * tw + 16, rows * th + 56), (22, 23, 27))
    sd = ImageDraw.Draw(sh)
    sd.text((16, 22), f"AAPS Halo · {name} · family v2", font=ImageFont.truetype(FONT, 22), fill=(230, 232, 240), anchor="lm")
    for i, (sid, note, kw) in enumerate(tiles):
        x0, y0 = 16 + (i % cols) * tw, 44 + (i // cols) * th
        sh.paste(tile_image(kw, cs), (x0, y0))
        sd.text((x0 + cs // 2, y0 + cs + 20), sid, font=LBL, fill=(235, 236, 242), anchor="mm")
        sd.text((x0 + cs // 2, y0 + cs + 44), note, font=LBL2, fill=(150, 156, 172), anchor="mm")
    p = os.path.join(OUT, f"halo_states_{name}.png")
    sh.save(p, optimize=True)
    print(p, len(tiles))


ON = dict(ball="idle")
HIGH = dict(**bg("11,8", level=1, trend="UP45", delta="+0,4"), tail=(190,))
LOW = dict(**bg("3,4", level=-1, trend="DOWN45", delta="-0,3"), tail=(75,))
LOWFLAT = dict(**bg("3,6", level=-1, trend="FLAT", delta="+0,0"), tail=(66,))
URG = dict(**bg("2,8", level=-1, trend="DOWN2", delta="-0,8"), tail=(80,))
STALE17 = dict(bg_age=17, loop_age=19)
SEC_A = dict(name="Lea", age=2, level=0, sgv_str="6,8", trend="FLAT", loop="OK")
SEC_B = dict(name="Annabelle-Sophie", age=4, level=1, sgv_str="12,1", trend="UP45", loop="WARN")
SEC_STALE = dict(name="Lea", age=23, level=0, sgv_str="7,2", trend="FLAT", loop="BAD")
SEC_VSTALE = dict(name="Lea", age=42, level=0, sgv_str="7,2", trend="FLAT", loop="BAD")
SEC_LOW = dict(name="Lea", age=2, level=-1, sgv_str="3,4", trend="DOWN45", loop="OK")
SEC_URG = dict(name="Lea", age=1, level=-1, sgv_str="2,8", trend="DOWN2", loop="OK")
SEC_KUSI = dict(name="Kusi", age=1, level=0, sgv_str="6,4", trend="FLAT", loop="OK")
MIN = dict(date=False, delta=False, ago=False, iob=False, cob=False, basal=False, tt=False)

SHEETS = {}

SHEETS["data"] = [
    ("bg.in_range", "6,4 FLAT +0,1 · 1′ (age.fresh)", dict(ON)),
    ("bg.high", "11,8 UP45 amber ring", dict(HIGH)),
    ("bg.very_high", "16,1 ring 19 px stroke", dict(**bg("16,1", level=1, trend="UP", delta="+0,6"), tail=(250,))),
    ("bg.low", "3,4 red digits + TIEF word", dict(LOW)),
    ("bg.urgent_low", "2,8 red digits + filled TIEF badge, 19 px ring", dict(URG)),
    ("bg.low vs bg.urgent_low", "word vs badge, 13 vs 19 px ring", dict(compose=[LOW, URG], labels=["bg.low 3,4", "bg.urgent_low 2,8"])),
    ("bg.sentinel_low", "phone '2,2' (39) = urgent → badge", dict(**bg("2,2", mgdl=39, level=-1, trend="DOWN", delta="-0,4"))),
    ("bg.sentinel_high", "'22,2+' + HOCH word", dict(**bg("22,2", mgdl=400, level=1, trend="UP", delta="+0,5"), tail=(360,))),
    ("age.aging", "8′ in warn", dict(bg_age=8)),
    ("age.stale", "17′ grey, strike, W3", dict(STALE17)),
    ("age.very_stale", "42′", dict(bg_age=42, loop_age=44)),
    ("age.very_stale ≥60′", "'---', 3 Std (= conn.restored_old_data)", dict(bg_age=180, loop_age=182)),
    ("age.none", "slot 'Kein BG' warn (rank 3)", dict(bg_age=None, tail=(), **ON)),
    ("conn.first_start", "skeleton, W1", dict(bg_age=None, first_start=True, iob="IOB", cob="--g", basal="-.--U/h",
                                              loop_age=None, tail=(), ball="hidden")),
    ("trend.fast", "UP2 double head + chevrons", dict(**bg("8,9", level=0, trend="UP2", delta="+0,7"), tail=(120,))),
    ("trend.fast (DOWN3)", "thicker double head", dict(**bg("5,4", level=0, trend="DOWN3", delta="-1,2"), tail=(140,))),
    ("trend.none", "'??' → no glyph", dict(trend="NONE")),
    ("trend.hidden_pref", "key_show_direction off", dict(show=dict(direction=False))),
    ("delta.missing", "'--' in txt3", dict(delta="--")),
    ("delta.wide", "'-12,5' detailed mg/dL", dict(**bg("", mgdl=142, trend="DOWN45", delta="-12,5", units="mgdl"))),
    ("delta.hidden_pref", "key_show_delta off", dict(show=dict(delta=False))),
    ("graph.empty", "no tail, ring = gauge", dict(tail=())),
    ("iob.zero", "0,00 dim, arc empty", dict(iob="0,00")),
    ("iob.negative", "-0,35 txt1, arc empty", dict(iob="-0,35")),
    ("iob.large", "12,40 arc full", dict(iob="12,40", cob="20(30) g")),
    ("iob.unknown / cob '--'", "'--'", dict(iob="", cob="--g")),
    ("basal.tbr_percent", "120 % teal", dict(basal="120 %")),
    ("basal.zero_abs", "0,00 U/h warn (BG in range)", dict(basal="0,00 U/h")),
    ("basal.zero_abs + bg.low", "0 % neutral txt2 during a low", dict(LOW, basal="0 %", tt="8,0", tt_level=2)),
    ("tt.active", "TT 8,0 violet", dict(tt="8,0", tt_level=2)),
    ("tt.active (range)", "TT 4,4 - 5,0", dict(tt="4,4 - 5,0", tt_level=2, basal="0 %")),
    ("tt.aps_adjusted / profile", "hidden", dict(tt="5,6", tt_level=1)),
    ("conn.no_profile", "W4, IOB/KH/basal '--', ball disabled", dict(iob="", cob="--g", basal="-.--U/h", loop_run="noprofile", **ON)),
    ("sys.preview", "deterministic sample, no ball", dict(time="10:08", loop_age=2, preview=True, ball="idle")),
]

SHEETS["warnings"] = [
    ("conn.phone_lost_stale", "W2, pill grey, ball disabled", dict(phone="lost_stale", bg_age=14, loop_age=15, **ON)),
    ("conn.phone_silent", "W3, pill grey, ball disabled (not hidden)", dict(phone="silent", **STALE17, **ON)),
    ("conn.phone_lost_fresh", "W10 = glyph after the age", dict(phone="lost_fresh", **ON)),
    ("conn.phone_lost_fresh + bg.low", "W10 glyph survives the low", dict(LOW, phone="lost_fresh", **ON)),
    ("conn.no_profile", "W4 Kein Profil", dict(loop_run="noprofile", iob="", cob="--g", basal="-.--U/h")),
    ("age.none", "'Kein BG' explains the '---'", dict(bg_age=None, tail=())),
    ("loop.ok", "2′ green pill", dict()),
    ("loop.warn", "9′ amber", dict(loop_age=9)),
    ("loop.late 15-29′", "W6 'Loop 23′' AMBER, pill grey", dict(loop_age=23)),
    ("loop.bad ≥30′", "W6 'Loop 34′' red, pill grey", dict(loop_age=34)),
    ("loop.never", "hollow grey '--'", dict(loop_age=None)),
    ("loop.open", "hollow, 'Offen'", dict(loop_mode="open", loop_age=31)),
    ("loop.lgs", "'LGS 3′'", dict(loop_mode="lgs", loop_age=3)),
    ("loop.off", "W5 + pill 'aus'", dict(loop_run="off", loop_age=4)),
    ("loop.no_aps (pumpcontrol)", "pill removed", dict(no_aps=True, loop_age=None, flavour="pumpcontrol")),
    ("loop.unknown_mode (client)", "age only; warn common", dict(loop_mode="unknown", loop_age=8, flavour="client")),
    ("pump.res_critical", "W7 Reservoir 5 U", dict(res="5 U", res_level=2)),
    ("batt.phone_low", "W8 Tel 18 %", dict(batt=18, batt_level=0)),
    ("batt.phone_low <10 %", "W8 alert", dict(batt=7, batt_level=0)),
    ("pump.res_low", "W9 Reservoir 18 U", dict(res="18 U", res_level=1)),
    ("pump.res_unknown", "hidden, never red", dict(res="", res_level=2, show=dict(res=True))),
    ("prio: low suppresses W7-W9", "3,4 + res 5 U → slot empty", dict(LOW, res="5 U", res_level=2, batt=8, batt_level=0)),
    ("prio: W2 beats W6 + W7", "reds: slot only (=1)", dict(phone="lost_stale", bg_age=25, loop_age=25, res="5 U", res_level=2)),
    ("prio: W5 beats W6", "loop off + 40′", dict(loop_run="off", loop_age=40)),
    ("prio: urgent low + loop.bad", "W6 shows; reds: hero group + slot (=2)", dict(URG, loop_age=34)),
    ("prio: urgent low + loop.late", "reds: hero group (=1), slot amber", dict(URG, loop_age=18)),
    ("prio: urgent low + loop.off", "W5 shows; reds = 1", dict(URG, loop_run="off", loop_age=6)),
    ("pump.res_ok + batt.phone_ok", "prefs on, txt2 runs", dict(res="120 U", batt=64, show=dict(res=True, batt=True))),
    ("flavor.pumpcontrol", "no loop, no tail, ball ok", dict(no_aps=True, loop_age=None, flavour="pumpcontrol", **ON)),
]

BALLCMP = [("idle: outline, indigo text", dict(ball="idle")),
           ("armed: solid fill + countdown", dict(ball="armed", armed_left=0.6)),
           ("sent: 55 %, '…', static ring", dict(ball="sent")),
           ("disabled: dashed + slash", dict(ball="idle", phone="lost_fresh"))]

SHEETS["ball"] = [
    ("ball.off", "pref off (default): nothing", dict(ball="off")),
    ("ball.hidden", "wearControl off / switched", dict(ball="hidden")),
    ("ball.idle", "outline, '+0,5' / 'U' indigo", dict(ball="idle")),
    ("ball.* side by side", "same scale, 2.5× crop", dict(ballcmp=BALLCMP)),
    ("ball.idle (1,0)", "amount 1,0", dict(ball="idle", amount="1,0")),
    ("ball.armed (start)", "1st tap: ring full 800 ms", dict(ball="armed", armed_left=1.0)),
    ("ball.armed (mid)", "countdown 40 % left", dict(ball="armed", armed_left=0.4)),
    ("ball.sent", "'…' + static ring for 30 s", dict(ball="sent")),
    ("ball.sent + tap", "toast pending", dict(ball="sent", toast="Eine Anfrage läuft bereits.\nBitte auf der Uhr prüfen.")),
    ("ball.disabled NO_PHONE", "dashed + slash, W10 glyph", dict(ball="idle", phone="lost_fresh")),
    ("ball.disabled STALE_DATA", "W3", dict(ball="idle", **STALE17)),
    ("ball.disabled + tap", "toast with reason", dict(ball="idle", phone="lost_fresh", toast="guard")),
    ("ball.disabled MAX_BOLUS", "1,0 > max 0,8: disabled + toast", dict(ball="idle", amount="1,0", max_bolus=True, toast="guard")),
    ("ball.disabled LOW_BG (urgent)", "2,8 DOWN2: no insulin button", dict(URG, ball="idle")),
    ("ball.disabled LOW_BG + tap", "toast 'BG zu niedrig.'", dict(LOW, ball="idle", toast="guard")),
    ("ball + bg.low FLAT", "low, not falling: stays idle", dict(LOWFLAT, ball="idle")),
    ("ball.disabled NO_PROFILE", "W4", dict(loop_run="noprofile", iob="", cob="--g", basal="-.--U/h", ball="idle")),
    ("ball.disabled LOOP_OFF", "W5", dict(loop_run="off", ball="idle")),
    ("ball.delivering (P2)", "progress ring 60 %", dict(ball="delivering", progress=60)),
    ("ball.delivered (P2)", "check 2 s", dict(ball="delivered")),
    ("ball in ambient", "never drawn", dict(ball="idle", mode="ambient")),
    ("round 384 · armed", "'2×' (ball < 50 px)", dict(ball="armed", armed_left=0.7, shape=("round", 384))),
    ("round 384 · disabled", "slash still readable", dict(ball="idle", phone="lost_fresh", shape=("round", 384))),
    ("round 384 · sent", "", dict(ball="sent", shape=("round", 384))),
]

SHEETS["ambient"] = [
    ("mode.ambient · in", "single solid track", dict(mode="ambient")),
    ("mode.ambient · high", "whole track dashed", dict(HIGH, mode="ambient")),
    ("mode.ambient · very high", "dashed, 3 px", dict(**bg("16,1", level=1, trend="UP", delta="+0,6"), mode="ambient")),
    ("mode.ambient · low", "doubled track + TIEF word", dict(LOW, mode="ambient")),
    ("mode.ambient · urgent low", "doubled track + boxed TIEF", dict(URG, mode="ambient")),
    ("mode.ambient · low vs urgent", "", dict(compose=[dict(LOW, mode="ambient"), dict(URG, mode="ambient")], labels=["low", "urgent low"])),
    ("mode.ambient_warning · stale", "strike + Keine Daten", dict(STALE17, mode="ambient")),
    ("mode.ambient_warning · lost", "Telefon getrennt", dict(phone="lost_stale", bg_age=15, loop_age=16, mode="ambient")),
    ("mode.ambient · loop.bad", "Loop 34′", dict(loop_age=34, mode="ambient")),
    ("mode.ambient · loop.off", "Loop aus + pill 'aus'", dict(loop_run="off", mode="ambient")),
    ("mode.ambient · first_start", "W1", dict(bg_age=None, first_start=True, iob="IOB", loop_age=None, tail=(), mode="ambient")),
    ("mode.ambient · TT", "TT kept, basal dropped", dict(tt="8,0", tt_level=2, mode="ambient")),
    ("mode.ambient_low_bit · in", "1 bit, AA off", dict(mode="lowbit")),
    ("mode.ambient_low_bit · urgent low", "doubled 1 px + boxed TIEF", dict(URG, mode="lowbit")),
    ("mode.ambient_low_bit · stale", "strike 2 px", dict(STALE17, mode="lowbit")),
    ("mode.ambient_burn_in · in", "shift (+5,0)", dict(mode="burnin", burn_offset=(5, 0))),
    ("mode.ambient_burn_in · low", "shift (-3,-4)", dict(LOW, mode="burnin", burn_offset=(-3, -4))),
    ("mode.ambient_low_bit_burn_in · high", "union, shift (0,5)", dict(HIGH, mode="lowbit_burnin", burn_offset=(0, 5))),
    ("mode.ambient · multi.two", "block = name + BG + age", dict(mode="ambient", name="Kusi", secondaries=[SEC_A])),
    ("mode.transition", "armed → ambient: reset, hidden", dict(ball="armed", mode="ambient")),
    ("sys.simplified · in", "family SimpleUi reference", dict(simplified=True)),
    ("sys.simplified · aging", "8′ amber next to the arrow", dict(simplified=True, bg_age=8)),
    ("sys.simplified · stale", "strike + Keine Daten · 17′", dict(simplified=True, **STALE17)),
    ("sys.simplified · phone lost", "Telefon getrennt · 14′", dict(simplified=True, phone="lost_stale", bg_age=14, loop_age=15)),
    ("sys.simplified · low", "TIEF word, arrow red", dict(LOW, simplified=True)),
    ("sys.simplified · urgent low", "TIEF badge", dict(URG, simplified=True)),
    ("sys.simplified · ambient low", "outline digits + TIEF", dict(LOW, simplified=True, mode="ambient")),
    ("sys.simplified · ambient urgent", "boxed TIEF, +1 px", dict(URG, simplified=True, mode="ambient")),
]

SHEETS["units_locale"] = [
    ("units.mmol", "6,4 comma", dict(ON)),
    ("units.mgdl", "115 · +2 · ring in mg/dL", dict(**bg("", mgdl=115, delta="+2", units="mgdl"), tail=(110,), **ON)),
    ("units.mgdl high", "212", dict(**bg("", mgdl=212, level=1, trend="UP45", delta="+7", units="mgdl"), tail=(190,))),
    ("units.mgdl sentinel high", "'400+' + HOCH", dict(**bg("", mgdl=400, level=1, trend="UP", delta="+9", units="mgdl"), tail=(360,))),
    ("units.mgdl sentinel low", "'39' + TIEF badge", dict(**bg("", mgdl=39, level=-1, trend="DOWN", delta="-6", units="mgdl"))),
    ("units.mgdl low", "'62' + TIEF word", dict(**bg("", mgdl=62, level=-1, trend="DOWN45", delta="-4", units="mgdl"), tail=(75,))),
    ("units.mmol 4-char", "'11,8' fits 196 px", dict(HIGH)),
    ("units.mmol widest", "'22,2+' + HOCH, group fit 220", dict(**bg("22,2", mgdl=400, level=1, trend="UP", delta="+0,5"), tail=(360,))),
    ("units.phone_locale (en)", "phone sends '6.4' → shown as sent", dict(**bg("6.4", delta="+0.1"), iob="1.25", basal="0.85 U/h", lang="en",
                                                                          date="Fri, Oct 17", time="2:07", ampm="PM", amount="0.5", ball="idle")),
    ("locale.en · low", "LOW word", dict(**bg("3.4", level=-1, trend="DOWN45", delta="-0.3"), lang="en", date="Fri, Oct 17", time="2:07", ampm="PM")),
    ("locale.de_24h", "14:07 · Fr 17. Okt", dict(ON)),
    ("locale.en_12h", "2:07 PM · Fri, Oct 17", dict(lang="en", time="2:07", ampm="PM", date="Fri, Oct 17", **ON)),
    ("locale.long (fr)", "date ellipsis rules", dict(lang="fr", date="ven. 17 oct.", **ON)),
    ("pref.date + week", "Fr 17. Okt · KW 42", dict(show=dict(week=True), week="KW 42")),
    ("pref.date off", "time recentres", dict(show=dict(date=False))),
    ("pref.seconds", ":07 small, interactive only", dict(show=dict(seconds=True), seconds="07")),
    ("pref.all_on", "densest", dict(tt="8,0", tt_level=2, basal="120 %", res="120 U", batt=64, rig="88 %", cob="20 g",
                                    show=dict(week=True, seconds=True, res=True, batt=True, rig=True), week="KW 42",
                                    seconds="07", ball="idle")),
    ("pref.minimal", "age kept (txt3, 16 px) while fresh", dict(show=MIN)),
    ("pref.minimal + aging", "age full size, amber", dict(bg_age=8, show=MIN)),
    ("pref iob on, cob off", "IOB recentres", dict(show=dict(cob=False))),
    ("units.mgdl + all", "mg/dL TT + basal", dict(**bg("", mgdl=165, level=0, trend="UP45", delta="+4", units="mgdl"), tail=(140,),
                                                  tt="140", tt_level=2, basal="1,20 U/h", ball="idle")),
]

SHEETS["multi"] = [
    ("multi.one", "name hidden", dict(name="Kusi")),
    ("multi.two", "1 block, name in date row", dict(name="Kusi", secondaries=[SEC_A])),
    ("multi.three", "names ≤ 7 + '…', IOB arc ends at +38°", dict(name="Kusi", secondaries=[SEC_A, SEC_B])),
    ("multi.secondary_low", "doubled outline + TIEF", dict(name="Kusi", secondaries=[SEC_LOW, SEC_B])),
    ("multi.secondary_urgent_low", "block filled + badge, slot 'Lea TIEF 2,8'", dict(name="Kusi", secondaries=[SEC_URG, SEC_B])),
    ("multi.secondary_stale", "only the block greys", dict(name="Kusi", secondaries=[SEC_STALE, SEC_B])),
    ("multi.secondary very stale ≥30′", "escalated: 'Lea · Keine Daten · 42′'", dict(name="Kusi", secondaries=[SEC_VSTALE, SEC_B])),
    ("multi.three + primary stale", "W3 beats the escalation", dict(name="Kusi", secondaries=[SEC_URG, SEC_B], **STALE17)),
    ("multi.patient_name empty", "fallback P2/P3", dict(secondaries=[dict(SEC_A, name=""), dict(SEC_B, name="", fallback="P3")])),
    ("multi.switched (P2)", "chip 'Lea'; IOB/KH/pill/runs/ball hidden", dict(name="Lea", switched=True, secondaries=[SEC_KUSI],
                                                                         **bg("6,8"), ball="idle", tt="8,0", tt_level=2)),
    ("multi.three @384", "13 px names (≥ 11 phys), no loop dot", dict(name="Kusi", secondaries=[SEC_A, SEC_B], shape=("round", 384))),
    ("multi.secondary_urgent_low @384", "", dict(name="Kusi", secondaries=[SEC_URG, SEC_B], shape=("round", 384))),
    ("flavor.client primary", "loop age warn, no ball", dict(flavour="client", loop_mode="unknown", loop_age=9, name="Kusi",
                                                             secondaries=[SEC_A])),
    ("multi.three ambient", "low = doubled, high = dashed", dict(name="Kusi", secondaries=[SEC_LOW, SEC_B], mode="ambient")),
    ("multi ambient · urgent low", "boxed TIEF + slot", dict(name="Kusi", secondaries=[SEC_URG, dict(SEC_A, name="Tom")], mode="ambient")),
    ("multi.two + urgent low (primary)", "", dict(URG, name="Kusi", secondaries=[SEC_A])),
    ("multi.two + ball", "ball = dataset 0 pump", dict(name="Kusi", secondaries=[SEC_A], ball="idle")),
    ("multi.three · long primary", "primary name ellipsised to row", dict(name="Annabelle-Sophie", secondaries=[SEC_A, dict(SEC_B, name="Maximilian")])),
]

SHEETS["shapes"] = [
    ("round 384 (Pixel Watch 1/2)", "s = 0,853", dict(shape=("round", 384), **ON)),
    ("round 384 · pref.all_on", "", dict(shape=("round", 384), tt="8,0", tt_level=2, basal="120 %", res="120 U", cob="20 g",
                                         show=dict(res=True, week=True), week="KW 42", ball="idle")),
    ("round 384 · W2", "slot fits", dict(shape=("round", 384), phone="lost_stale", bg_age=14, loop_age=15)),
    ("round 384 · mg/dL 400+", "HOCH fits", dict(shape=("round", 384), **bg("", mgdl=400, level=1, trend="UP", delta="+9", units="mgdl"), tail=(360,))),
    ("round 384 · urgent low", "badge fits", dict(shape=("round", 384), **URG)),
    ("round 396 (GW 40 mm)", "", dict(shape=("round", 396), **ON)),
    ("round 416", "", dict(shape=("round", 416), **HIGH)),
    ("round 456 (Pixel Watch 3)", "", dict(shape=("round", 456), **ON)),
    ("round 480 (GW 47 mm)", "s = 1,067", dict(shape=("round", 480), **LOW)),
    ("square 450", "inscribed circle", dict(shape=("square", 450), **ON)),
    ("square 360", "inscribed circle, s = 0,8", dict(shape=("square", 360), **ON)),
    ("rect 368×448", "centred vertically", dict(shape=("square", 368, 448), **ON)),
    ("flat tyre 360×330", "8 px safe band above the chin", dict(shape=("flat", 360, 330), tt="8,0", tt_level=2, **ON)),
    ("flat tyre · pref.all_on", "rig dropped first, then batt, res, basal", dict(shape=("flat", 360, 330), tt="8,0", tt_level=2, basal="120 %", res="120 U", batt=64,
                                                             rig="88 %", cob="20 g", show=dict(res=True, batt=True, rig=True, week=True), week="KW 42", **ON)),
    ("flat tyre · W3", "slot above chin", dict(shape=("flat", 360, 330), **STALE17)),
    ("flat tyre · multi.three", "blocks + runs above chin", dict(shape=("flat", 360, 330), name="Kusi", secondaries=[SEC_A, SEC_B], tt="8,0", tt_level=2)),
    ("square 360 · ambient", "", dict(shape=("square", 360), mode="ambient")),
    ("round 384 · burn-in", "shift scaled", dict(shape=("round", 384), mode="burnin", burn_offset=(-5, 0))),
    ("round 384 · ball armed", "hit r 44 clipped, '2×'", dict(shape=("round", 384), ball="armed", armed_left=0.7)),
    ("round 384 · simplified low", "", dict(shape=("round", 384), simplified=True, **LOW)),
]


if __name__ == "__main__":
    only = sys.argv[1:] or list(SHEETS)
    for n in only:
        sheet(n, SHEETS[n])
