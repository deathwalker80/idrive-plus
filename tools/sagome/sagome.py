"""
Sagome di auto generiche (vista laterale, lato destro, muso a destra) per la pagina "Porte, finestrini e tetto" di
MINI/BMW/Supra Plus e NBT Plus, per chi non vuole caricare una foto della sua auto.
Sono carrozzerie generiche disegnate qui: nessun modello reale, nessun logo, nessuna calandra.

Per ogni carrozzeria scrive in app/app/src/main/assets/auto/sagome/<id>/:
  ombre.png    la carrozzeria in scala di grigi (luce e ombra): nell'app si moltiplica per il colore scelto
  luci.png     riflessi bianchi sopra la carrozzeria colorata
  tetto.png    la maschera del tetto (per il tetto a contrasto)
  parti.png    vetri, gomme, cerchi, fari, plastiche: non cambiano col colore
  punti.json   gli 11 punti della pagina (x, y da 0 a 1; il lato sinistro, non visibile, e' "nascosto")
e l'anteprima in tools/sagome/anteprima/.
Il colore si applica come nell'app: ombre x colore, poi luci sopra, poi parti sopra (la stessa formula di Foto.kt).
Uso: python3 tools/sagome/sagome.py      (Pillow, numpy)
"""
import json
import math
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

QUI = os.path.dirname(os.path.abspath(__file__))
RADICE = os.path.dirname(os.path.dirname(QUI))
ASSETS = os.path.join(RADICE, "app", "app", "src", "main", "assets", "auto", "sagome")
ANTEPRIMA = os.path.join(QUI, "anteprima")

W, H = 1200, 520            # tela di ogni sagoma (pixel finali)
SS = 3                      # sovracampionamento
SUOLO = 470                 # y del suolo


# ------------------------------------------------------------------------------------------------ geometria
def catmull(punti, passi=14, chiusa=False):
    """curva morbida che passa per i punti (Catmull-Rom centripeta semplice)"""
    p = list(punti)
    if chiusa:
        p = [p[-1]] + p + [p[0], p[1]]
    else:
        p = [p[0]] + p + [p[-1]]
    out = []
    for i in range(1, len(p) - 2):
        p0, p1, p2, p3 = p[i - 1], p[i], p[i + 1], p[i + 2]
        for k in range(passi):
            t = k / passi
            t2, t3 = t * t, t * t * t
            x = 0.5 * ((2 * p1[0]) + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3)
            y = 0.5 * ((2 * p1[1]) + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)
            out.append((x, y))
    if not chiusa:
        out.append(p[-2])
    return out


def arrotonda(poli, r, passi=6):
    """poligono con gli angoli arrotondati di raggio r"""
    out = []
    n = len(poli)
    for i in range(n):
        a, b, c = poli[i - 1], poli[i], poli[(i + 1) % n]
        v1 = (a[0] - b[0], a[1] - b[1])
        v2 = (c[0] - b[0], c[1] - b[1])
        l1 = math.hypot(*v1) or 1
        l2 = math.hypot(*v2) or 1
        rr = min(r, l1 / 2.2, l2 / 2.2)
        p1 = (b[0] + v1[0] / l1 * rr, b[1] + v1[1] / l1 * rr)
        p2 = (b[0] + v2[0] / l2 * rr, b[1] + v2[1] / l2 * rr)
        for k in range(passi + 1):
            t = k / passi
            x = (1 - t) ** 2 * p1[0] + 2 * (1 - t) * t * b[0] + t * t * p2[0]
            y = (1 - t) ** 2 * p1[1] + 2 * (1 - t) * t * b[1] + t * t * p2[1]
            out.append((x, y))
    return out


def maschera(disegna):
    m = Image.new("L", (W * SS, H * SS), 0)
    disegna(ImageDraw.Draw(m), lambda pts: [(x * SS, y * SS) for x, y in pts])
    return np.asarray(m.resize((W, H), Image.LANCZOS), np.float32) / 255.0


def poligono(pts):
    return maschera(lambda d, s: d.polygon(s(pts), fill=255))


def cerchio(cx, cy, r):
    return maschera(lambda d, s: d.ellipse([(cx - r) * SS, (cy - r) * SS, (cx + r) * SS, (cy + r) * SS], fill=255))


def linea(pts, w):
    return maschera(lambda d, s: d.line(s(pts), fill=255, width=max(1, int(w * SS)), joint="curve"))


def sfoca(m, r):
    im = Image.fromarray((np.clip(m, 0, 1) * 255).astype(np.uint8))
    return np.asarray(im.filter(ImageFilter.GaussianBlur(r)), np.float32) / 255.0


def interp(xs, ys, x):
    return float(np.interp(x, xs, ys))


# ------------------------------------------------------------------------------------------------ carrozzerie
# Ogni carrozzeria: misure in pixel sulla tela (muso a destra). Nessun modello reale: proporzioni tipiche del tipo.
#   x0,x1        coda e muso;  ruote: xr, xa (centri), R (raggio gomma);  sotto: altezza della pancia dal suolo
#   profilo      punti della linea alta (dalla coda al muso): coda bassa, coda alta, tetto dietro, tetto davanti,
#                base del parabrezza, cofano, muso
#   vetri        linea di cintura (y), montante C (x in basso, x in alto), montante A (x in basso, x in alto),
#                altezza del tetto interno; montanti B (x, larghezza); porte (lista di x dei tagli)
CARROZZERIE = [
    dict(id="utilitaria3", nome="Utilitaria 3 porte", marche=["MINI"],
         x0=245, x1=985, xr=350, xa=835, R=74, pancia=54,
         profilo=[(247, 396), (250, 300), (262, 250), (315, 200), (345, 190), (630, 188), (690, 198), (765, 268), (915, 292), (972, 314), (985, 384)],
         cintura=284, c=(292, 330), a=(755, 684), cielo=206, b=[(588, 16)], porte=[(572, 786)], tetto_su=(318, 684)),
    dict(id="utilitaria5", nome="Utilitaria 5 porte", marche=["MINI"],
         x0=215, x1=1000, xr=330, xa=850, R=74, pancia=54,
         profilo=[(217, 396), (220, 300), (232, 250), (285, 200), (315, 190), (642, 188), (702, 198), (777, 268), (930, 292), (987, 314), (1000, 384)],
         cintura=284, c=(262, 300), a=(767, 696), cielo=206, b=[(548, 18)], porte=[(368, 552), (552, 798)], tetto_su=(288, 696)),
    dict(id="familiare_compatta", nome="Station wagon compatta", marche=["MINI", "BMW"],
         x0=160, x1=1030, xr=300, xa=875, R=76, pancia=54,
         profilo=[(162, 392), (165, 292), (172, 240), (198, 198), (236, 188), (660, 186), (722, 198), (795, 270), (955, 296), (1015, 318), (1030, 382)],
         cintura=286, c=(188, 214), a=(785, 712), cielo=206, b=[(560, 18), (330, 14)], porte=[(345, 562), (562, 812)], tetto_su=(200, 712)),
    dict(id="suv_compatto", nome="SUV compatto", marche=["MINI", "BMW"],
         x0=185, x1=1025, xr=330, xa=865, R=86, pancia=80,
         profilo=[(187, 380), (190, 268), (202, 212), (250, 164), (288, 152), (660, 150), (722, 162), (798, 244), (955, 272), (1013, 294), (1025, 368)],
         cintura=262, c=(228, 262), a=(788, 716), cielo=170, b=[(556, 20)], porte=[(362, 560), (560, 812)], tetto_su=(250, 716),
         rivestimenti=True),
    dict(id="cabrio", nome="Cabrio", marche=["MINI", "BMW"],
         x0=245, x1=985, xr=350, xa=835, R=74, pancia=54,
         profilo=[(247, 396), (250, 300), (264, 272), (340, 266), (450, 262), (560, 258), (650, 254), (700, 250), (765, 270), (915, 292), (972, 314), (985, 384)],
         cintura=284, c=(0, 0), a=(752, 676), cielo=212, b=[], porte=[(560, 786)], aperta=True, tetto_su=None),
    dict(id="berlina", nome="Berlina", marche=["BMW"],
         x0=125, x1=1075, xr=290, xa=900, R=78, pancia=50,
         profilo=[(127, 390), (131, 318), (155, 292), (262, 284), (350, 216), (425, 194), (640, 192), (712, 208), (800, 282), (990, 304), (1058, 324), (1075, 386)],
         cintura=294, c=(305, 378), a=(792, 714), cielo=212, b=[(560, 20)], porte=[(338, 562), (562, 828)], tetto_su=(390, 712)),
    dict(id="familiare", nome="Station wagon", marche=["BMW"],
         x0=125, x1=1075, xr=290, xa=900, R=78, pancia=50,
         profilo=[(127, 390), (129, 302), (137, 254), (170, 206), (220, 194), (640, 192), (712, 208), (800, 282), (990, 304), (1058, 324), (1075, 386)],
         cintura=294, c=(155, 192), a=(792, 714), cielo=212, b=[(560, 20), (318, 16)], porte=[(338, 562), (562, 828)], tetto_su=(175, 712)),
    dict(id="coupe", nome="Coupé", marche=["BMW"],
         x0=140, x1=1065, xr=300, xa=890, R=78, pancia=46,
         profilo=[(142, 392), (146, 318), (170, 296), (275, 290), (365, 228), (455, 204), (618, 200), (698, 216), (793, 288), (980, 308), (1048, 328), (1065, 388)],
         cintura=298, c=(325, 405), a=(786, 710), cielo=220, b=[(520, 14)], porte=[(468, 798)], tetto_su=(415, 704)),
    dict(id="fastback", nome="Berlina coupé 4 porte", marche=["BMW"],
         x0=125, x1=1075, xr=290, xa=900, R=78, pancia=48,
         profilo=[(127, 390), (131, 318), (155, 300), (245, 292), (335, 238), (425, 204), (630, 198), (706, 212), (798, 284), (990, 306), (1058, 326), (1075, 386)],
         cintura=296, c=(295, 388), a=(790, 712), cielo=218, b=[(566, 20)], porte=[(345, 566), (566, 828)], tetto_su=(400, 708)),
    dict(id="suv", nome="SUV", marche=["BMW"],
         x0=120, x1=1080, xr=290, xa=910, R=92, pancia=86,
         profilo=[(122, 374), (124, 262), (134, 200), (170, 148), (220, 136), (650, 134), (725, 150), (810, 238), (995, 266), (1065, 290), (1080, 364)],
         cintura=256, c=(150, 190), a=(800, 724), cielo=154, b=[(560, 22), (305, 18)], porte=[(330, 562), (562, 836)], tetto_su=(170, 724),
         rivestimenti=True),
    dict(id="sportiva", nome="Coupé sportiva", marche=["Supra", "BMW"],
         x0=160, x1=1050, xr=320, xa=870, R=82, pancia=42,
         profilo=[(162, 382), (166, 300), (205, 276), (290, 262), (380, 228), (460, 214), (565, 214), (640, 232), (740, 300), (930, 328), (1030, 350), (1050, 396)],
         cintura=300, c=(395, 470), a=(735, 660), cielo=232, b=[], porte=[(450, 752)], tetto_su=(430, 652), sportivi=True),
]


def sotto_y(c):
    return SUOLO - c["pancia"]


def ruote(c):
    return [(c["xr"], SUOLO - c["R"]), (c["xa"], SUOLO - c["R"])]


def contorno(c):
    """il contorno della carrozzeria: profilo alto morbido, sotto la pancia con i passaruota"""
    alto = catmull(c["profilo"], 12)
    y0 = sotto_y(c)
    R = c["R"]
    basso = []
    # dal muso alla coda lungo il fondo, coi passaruota (cerchi di raggio R+14)
    for (cx, cy) in sorted(ruote(c), key=lambda p: -p[0]):
        pass
    xa, xr = c["xa"], c["xr"]
    ra = R + 13
    basso.append((c["x1"] - 22, y0 + 4))
    for (cx, cy) in [(xa, SUOLO - R), (xr, SUOLO - R)]:
        dy = y0 - cy
        dx = math.sqrt(max(ra * ra - dy * dy, 0))
        basso.append((cx + dx, y0))
        a0 = math.atan2(y0 - cy, dx)
        a1 = math.atan2(y0 - cy, -dx)
        n = 22
        for k in range(1, n):
            t = a0 + (a1 - a0 + (2 * math.pi if a1 < a0 else 0)) * k / n
            # l'arco passa sopra la ruota: angoli da destra (in basso) a sinistra (in basso) passando per l'alto
            ang = a0 - (a0 - (a1 - 2 * math.pi)) * k / n
            basso.append((cx + ra * math.cos(ang), cy + ra * math.sin(ang)))
        basso.append((cx - dx, y0))
    basso.append((c["x0"] + 18, y0 + 4))
    return alto + basso


def linea_alta(c):
    pts = catmull(c["profilo"], 12)
    xs = [p[0] for p in pts]
    ys = [p[1] for p in pts]
    order = np.argsort(xs)
    return np.array(xs)[order], np.array(ys)[order]


def vetri(c):
    """finestrature (DLO): poligono sotto il tetto, dentro i montanti A e C, sopra la cintura"""
    cb = c["cintura"]
    cx0, cx1 = c["c"]
    ax0, ax1 = c["a"]
    cielo = c["cielo"]
    if c.get("aperta"):
        # cabrio: il parabrezza visto di lato, una lama inclinata
        return arrotonda([(ax0 - 2, cb + 2), (ax1, cielo), (ax1 + 16, cielo + 2), (ax0 + 18, cb + 2)], 5)
    return arrotonda([(cx0, cb), (cx1, cielo), (ax1, cielo), (ax0, cb)], 26)


def disegna(c):
    xs, ys = linea_alta(c)
    corpo = poligono(contorno(c))
    dlo = poligono(vetri(c)) * corpo

    # ---- ombre (scala di grigi) della carrozzeria: base 0.80, fianco piu' chiaro in alto (spalla), scuro in basso
    yy = np.arange(H, dtype=np.float32)[:, None] * np.ones((1, W), np.float32)
    xx = np.ones((H, 1), np.float32) * np.arange(W, dtype=np.float32)[None, :]
    cb = c["cintura"]
    y0 = sotto_y(c)
    sh = np.full((H, W), 0.80, np.float32)
    spalla = cb + 26
    sh += 0.16 * np.exp(-((yy - spalla) / 16.0) ** 2)                       # spalla illuminata
    sh -= 0.10 * np.clip((yy - spalla) / (y0 - spalla), 0, 1)                 # fianco che scende in ombra
    sh -= 0.22 * np.clip((yy - (y0 - 26)) / 26.0, 0, 1)                       # sotto porta scuro
    sh = np.where(yy < cb, 0.86 - 0.18 * np.clip((cb - yy) / 120.0, 0, 1), sh)  # tetto/montanti
    # cofano e baule appena piu' chiari
    alto_y = np.interp(xx, xs, ys)
    sh += 0.08 * np.exp(-((yy - alto_y - 8) / 9.0) ** 2)
    # passaruota: anello scuro attorno al taglio
    for (wx, wy) in ruote(c):
        d = np.hypot(xx - wx, yy - wy)
        sh -= 0.20 * np.exp(-((d - (c["R"] + 16)) / 6.0) ** 2)
    sh = np.clip(sh, 0.05, 1.0)

    # tagli delle porte e del cofano (linee scure sottili), maniglie
    tagli = np.zeros((H, W), np.float32)
    for (p0, p1) in c["porte"]:
        for x in (p0, p1):
            tagli = np.maximum(tagli, linea([(x, cb - 2), (x - 6, y0 - 8)], 1.6))
        mx = p1 - 70
        tagli = np.maximum(tagli, linea([(mx, cb + 22), (mx + 36, cb + 22)], 4.0) * 0.8)
    tagli = np.maximum(tagli, linea([(c["a"][0] + 10, cb - 2), (c["a"][0] + 60, interp(xs, ys, c["a"][0] + 60) + 2)], 1.4))
    sh = sh * (1 - 0.55 * tagli)

    # ---- riflessi bianchi: una lama lungo la spalla e un riflesso morbido sul cofano
    luce = 0.45 * np.exp(-((yy - (spalla - 3)) / 3.2) ** 2) * np.clip((xx - c["x0"] - 30) / 80, 0, 1) * np.clip((c["x1"] - 40 - xx) / 80, 0, 1)
    luce += 0.22 * np.exp(-((yy - alto_y - 4) / 4.0) ** 2)
    luce = np.clip(luce, 0, 1) * corpo * (1 - dlo)

    # ---- tetto (per il tetto a contrasto): sopra la cintura, tra i montanti
    tetto = np.zeros((H, W), np.float32)
    if c.get("tetto_su"):
        t0, t1 = c["tetto_su"]
        tetto = poligono([(t0, 0), (t1, 0), (t1, c["cielo"] - 1), (t0, c["cielo"] - 1)]) * corpo
        tetto = tetto * (yy < c["cielo"] + 2)

    # ---- parti fisse (RGBA): vetri, montanti neri, fari, fanali, plastiche, ruote
    parti = np.zeros((H, W, 4), np.float32)

    def sopra(col, a):
        a = np.clip(a, 0, 1)[..., None]
        rgb = np.array(col[:3], np.float32)[None, None, :] / 255.0
        parti[..., :3] = rgb * a + parti[..., :3] * (1 - a)
        parti[..., 3:] = a + parti[..., 3:] * (1 - a)

    # vetri: blu-grigio scuro con un riflesso diagonale
    if not c.get("aperta"):
        sopra((30, 38, 48), dlo)
        rif = np.clip(1 - np.abs((xx - (c["a"][0] - 140)) + (yy - cb) * 1.1) / 60.0, 0, 1) * 0.35
        sopra((210, 225, 240), dlo * rif)
        # montanti B neri e sottile bordo vetro
        for (bx, bw) in c["b"]:
            sopra((14, 16, 20), poligono([(bx - bw / 2, cb), (bx - bw / 2 + 8, c["cielo"]), (bx + bw / 2 + 8, c["cielo"]), (bx + bw / 2, cb)]) * dlo)
        bordo = np.clip(sfoca(dlo, 1.2) - poligono(arrotonda([(c["c"][0] + 5, cb - 3), (c["c"][1] + 6, c["cielo"] + 5), (c["a"][1] - 6, c["cielo"] + 5), (c["a"][0] - 8, cb - 3)], 22)), 0, 1)
        sopra((10, 12, 14), bordo * 0.9)
    else:
        sopra((52, 62, 74), dlo * 0.9)
        sopra((14, 16, 20), np.clip(sfoca(dlo, 1.0) - poligono(arrotonda([(c["a"][0] + 3, cb), (c["a"][1] + 3, c["cielo"] + 4), (c["a"][1] + 12, c["cielo"] + 5), (c["a"][0] + 13, cb)], 4)), 0, 1))
        # abitacolo aperto: i due poggiatesta e il bordo dell'abitacolo, scuri
        for hx in (445, 590):
            sopra((26, 26, 29), poligono(arrotonda([(hx, cb - 18), (hx + 6, cb - 74), (hx + 46, cb - 80), (hx + 52, cb - 18)], 16)))
        sopra((26, 26, 29), poligono(arrotonda([(380, cb - 22), (735, cb - 34), (735, cb - 16), (380, cb - 8)], 5)))
    # specchietto
    mx = c["a"][0] - 14
    sopra((18, 20, 24), poligono(arrotonda([(mx - 18, cb - 34), (mx + 26, cb - 40), (mx + 30, cb - 14), (mx - 14, cb - 10)], 8)))
    # fanale posteriore e faro
    xr0 = c["x0"]
    yt = interp(xs, ys, xr0 + 12)
    sopra((190, 28, 30), poligono(arrotonda([(xr0 + 2, yt + 10), (xr0 + 46, yt + 14), (xr0 + 40, yt + 36), (xr0 + 4, yt + 36)], 7)) * corpo)
    sopra((255, 120, 110), poligono(arrotonda([(xr0 + 6, yt + 14), (xr0 + 34, yt + 17), (xr0 + 32, yt + 22), (xr0 + 6, yt + 22)], 3)) * corpo * 0.7)
    # faro: segue il profilo del muso (sotto il bordo del cofano), dentro la carrozzeria
    xf = c["x1"] - (24 if c.get("sportivi") else 0)
    y1 = interp(xs, ys, xf - 74) + 8
    y2 = interp(xs, ys, xf - 12) + 8
    sopra((235, 240, 245), poligono(arrotonda([(xf - 74, y1), (xf - 12, y2), (xf - 14, y2 + 20), (xf - 62, y1 + 22)], 9)) * corpo)
    sopra((120, 130, 140), poligono(arrotonda([(xf - 70, y1 + 14), (xf - 16, y2 + 12), (xf - 16, y2 + 18), (xf - 62, y1 + 22)], 3)) * corpo * 0.7)
    # plastiche: sottoporta e fondo paraurti
    soglia = np.clip((yy - (y0 - 14)) / 2.0, 0, 1) * corpo
    sopra((20, 22, 26), soglia)
    if c.get("rivestimenti"):
        # SUV: fascia nera attorno ai passaruota e lungo il fondo
        fascia = np.clip((yy - (y0 - 30)) / 2.0, 0, 1)
        for (wx, wy) in ruote(c):
            fascia = np.maximum(fascia, cerchio(wx, wy, c["R"] + 28))
        sopra((26, 28, 32), fascia * corpo)
    else:
        # labbro del passaruota appena piu' chiaro
        for (wx, wy) in ruote(c):
            d = np.hypot(xx - wx, yy - wy)
            sopra((255, 255, 255), np.exp(-((d - (c["R"] + 15)) / 1.6) ** 2) * corpo * 0.18 * (yy < wy + 4))
    # ruote: gomma, cerchio a 5 razze generico, mozzo, ombra del passaruota
    for (wx, wy) in ruote(c):
        R = c["R"]
        sopra((8, 8, 10), cerchio(wx, wy, R + 12) * 0.85 * (1 - cerchio(wx, wy, R)))           # buio del passaruota
        sopra((24, 24, 27), cerchio(wx, wy, R))
        sopra((44, 44, 48), cerchio(wx, wy, R - 3) - cerchio(wx, wy, R - 7))
        rr = R * (0.72 if c.get("sportivi") else 0.66)
        metallo = (92, 96, 104) if c.get("sportivi") else (196, 202, 210)
        sopra((176, 182, 190) if not c.get("sportivi") else (110, 114, 122), cerchio(wx, wy, rr))
        sopra((60, 62, 68) if not c.get("sportivi") else (30, 32, 36), cerchio(wx, wy, rr - 4))
        razze = np.zeros((H, W), np.float32)
        nr = 10 if c.get("sportivi") else 5
        for k in range(nr):
            ang = -math.pi / 2 + k * 2 * math.pi / nr
            razze = np.maximum(razze, linea([(wx, wy), (wx + (rr - 6) * math.cos(ang), wy + (rr - 6) * math.sin(ang))], 7 if c.get("sportivi") else 13))
        sopra(metallo, razze)
        if c.get("sportivi"):
            sopra((200, 30, 36), poligono(arrotonda([(wx - rr * 0.95, wy - 22), (wx - rr * 0.78, wy - 30), (wx - rr * 0.78, wy + 6), (wx - rr * 0.95, wy)], 4)) * 0.9)
        sopra((150, 156, 164), cerchio(wx, wy, 13))
        sopra((90, 94, 100), cerchio(wx, wy, 5))

    # ombra a terra (sotto tutto, nelle parti)
    ombra = np.zeros((H, W), np.float32)
    ombra = maschera(lambda d, s: d.ellipse([(c["x0"] + 10) * SS, (SUOLO - 10) * SS, (c["x1"] - 10) * SS, (SUOLO + 16) * SS], fill=255))
    ombra = sfoca(ombra, 8) * 0.55
    parti_ombra = np.zeros((H, W, 4), np.float32)
    parti_ombra[..., 3] = ombra * (1 - corpo)

    return dict(corpo=corpo, ombre=sh * corpo, luce=luce, tetto=tetto, parti=parti, ombra=parti_ombra)


def punti(c):
    """gli 11 punti: lato destro sul disegno, lato sinistro (non visibile) negli stessi posti, nascosto"""
    cb = c["cintura"]
    y0 = sotto_y(c)
    xs, ys = linea_alta(c)
    porte = c["porte"]
    if len(porte) == 2:
        pd = ((porte[0][0] + porte[0][1]) / 2, (cb + y0) / 2)
        ad = ((porte[1][0] + porte[1][1]) / 2, (cb + y0) / 2)
        fpost = ((porte[0][0] + porte[0][1]) / 2 + 10, (cb + c["cielo"]) / 2 + 6)
        fant = ((porte[1][0] + porte[1][1]) / 2 - 10, (cb + c["cielo"]) / 2 + 6)
    else:
        ad = ((porte[0][0] + porte[0][1]) / 2, (cb + y0) / 2)
        pd = (porte[0][0] - 70, (cb + y0) / 2)                                   # niente porta dietro: sul fianco
        fant = ((porte[0][0] + porte[0][1]) / 2, (cb + c["cielo"]) / 2 + 6)
        fpost = ((c["c"][0] + porte[0][0]) / 2 + 20, (cb + c["cielo"]) / 2 + 8)
    if c.get("aperta"):
        tetto = (540, c["cintura"] - 16)                         # capote: sull'abitacolo aperto
    else:
        tx = (c["c"][1] + c["a"][1]) / 2
        tetto = (tx, interp(xs, ys, tx) + 4)
    xc = c["x1"] - 160
    cofano = (xc, interp(xs, ys, xc) + 6)
    xp = c["x0"] + 28
    portellone = (xp, interp(xs, ys, xp) + 34)
    # lato sinistro (non visibile): sul bordo alto, sopra la parte destra corrispondente (anello vuoto, non copre il
    # pallino del lato destro); le porte un po' piu' avanti dei finestrini, cosi' i quattro anelli non si toccano
    def sopra_bordo(x):
        if c.get("aperta"):
            return (x, c["cintura"] - 6)
        return (x, interp(xs, ys, x) - 2)
    p = dict(portellone=(portellone, False), finestrino_post_dx=(fpost, False), porta_post_dx=(pd, False),
             finestrino_ant_dx=(fant, False), porta_ant_dx=(ad, False), tetto=(tetto, False), cofano=(cofano, False),
             finestrino_post_sx=(sopra_bordo(fpost[0] - 30), True), porta_post_sx=(sopra_bordo(pd[0] + 25), True),
             finestrino_ant_sx=(sopra_bordo(fant[0] - 30), True), porta_ant_sx=(sopra_bordo(ad[0] + 25), True))
    return {k: dict(x=round(v[0][0] / W, 4), y=round(v[0][1] / H, 4), nascosto=v[1]) for k, v in p.items()}


# ------------------------------------------------------------------------------------------------ colori
COLORI = [
    ("Bianco", (236, 238, 240)), ("Nero", (22, 24, 28)), ("Argento", (176, 182, 188)), ("Grigio scuro", (78, 82, 88)),
    ("Blu", (28, 58, 128)), ("Azzurro", (70, 150, 210)), ("Rosso", (186, 24, 32)), ("Verde scuro", (24, 70, 48)),
    ("Giallo", (240, 196, 30)), ("Arancio", (232, 110, 30)), ("Bordeaux", (110, 20, 36)), ("Sabbia", (196, 176, 142)),
]
TETTI = [("Come la carrozzeria", None), ("Bianco", (236, 238, 240)), ("Nero", (22, 24, 28))]


def componi_da_file(id_, colore, tetto=None):
    """ricompone dai PNG salvati con la stessa formula dell'app (Sagome.kt): e' quello che si vedra' nell'iDrive"""
    d = os.path.join(ASSETS, id_)
    om = np.asarray(Image.open(os.path.join(d, "ombre.png")).convert("RGBA"), np.float32) / 255.0
    lu = np.asarray(Image.open(os.path.join(d, "luci.png")).convert("RGBA"), np.float32) / 255.0
    te = np.asarray(Image.open(os.path.join(d, "tetto.png")).convert("L"), np.float32) / 255.0
    pa = np.asarray(Image.open(os.path.join(d, "parti.png")).convert("RGBA"), np.float32) / 255.0
    g = om[..., 0:1]
    a = om[..., 3:4]
    col = np.array(colore, np.float32)[None, None, :] / 255.0
    rgb = g * col
    if tetto is not None:
        m = te[..., None]
        rgb = rgb * (1 - m) + g * (np.array(tetto, np.float32)[None, None, :] / 255.0) * m
    l = lu[..., 3:4]
    rgb = rgb * (1 - l) + l
    p_a = pa[..., 3:4]
    out_a = p_a + a * (1 - p_a)
    out_rgb = np.where(out_a > 0, (pa[..., :3] * p_a + rgb * a * (1 - p_a)) / np.maximum(out_a, 1e-6), 0)
    return Image.fromarray((np.clip(np.concatenate([out_rgb, out_a], 2), 0, 1) * 255 + 0.5).astype(np.uint8), "RGBA")


def componi(liv, colore, tetto=None):
    """la stessa formula dell'app: ombre x colore (il tetto x il suo colore), poi luci (bianco), poi parti; + ombra a terra"""
    col = np.array(colore, np.float32) / 255.0
    rgb = liv["ombre"][..., None] * col[None, None, :]
    if tetto is not None:
        tc = np.array(tetto, np.float32) / 255.0
        m = liv["tetto"][..., None]
        rgb = rgb * (1 - m) + liv["ombre"][..., None] * tc[None, None, :] * m
    a = liv["corpo"][..., None]
    l = liv["luce"][..., None]
    rgb = rgb * (1 - l) + l
    p = liv["parti"]
    out_rgb = p[..., :3] * p[..., 3:] + rgb * (1 - p[..., 3:]) * a
    out_a = p[..., 3:] + a * (1 - p[..., 3:])
    o = liv["ombra"][..., 3:]
    out_rgb = out_rgb + 0 * o
    out_a2 = out_a + o * (1 - out_a)
    rgb_fin = np.where(out_a2 > 0, out_rgb / np.maximum(out_a2, 1e-6), 0)
    img = np.concatenate([np.clip(rgb_fin, 0, 1), np.clip(out_a2, 0, 1)], axis=2)
    return Image.fromarray((img * 255).astype(np.uint8), "RGBA")


def salva_livelli(c, liv):
    d = os.path.join(ASSETS, c["id"])
    os.makedirs(d, exist_ok=True)
    g = (np.clip(liv["ombre"], 0, 1) * 255).astype(np.uint8)
    a = (np.clip(liv["corpo"], 0, 1) * 255).astype(np.uint8)
    Image.fromarray(np.dstack([g, g, g, a]), "RGBA").save(os.path.join(d, "ombre.png"), optimize=True)
    l = (np.clip(liv["luce"], 0, 1) * 255).astype(np.uint8)
    w = np.full_like(l, 255)
    Image.fromarray(np.dstack([w, w, w, l]), "RGBA").save(os.path.join(d, "luci.png"), optimize=True)
    t = (np.clip(liv["tetto"], 0, 1) * 255).astype(np.uint8)
    Image.fromarray(t, "L").save(os.path.join(d, "tetto.png"), optimize=True)
    p = liv["parti"].copy()
    o = liv["ombra"][..., 3]
    # ombra a terra dentro le parti (nera, sotto)
    pa = p[..., 3]
    na = pa + o * (1 - pa)
    nrgb = np.where(na[..., None] > 0, p[..., :3] * pa[..., None] / np.maximum(na[..., None], 1e-6), 0)
    Image.fromarray((np.dstack([np.clip(nrgb, 0, 1), np.clip(na, 0, 1)]) * 255).astype(np.uint8), "RGBA").save(os.path.join(d, "parti.png"), optimize=True)
    with open(os.path.join(d, "punti.json"), "w", encoding="utf-8") as f:
        json.dump(dict(nome=c["nome"], punti=punti(c)), f, ensure_ascii=False, indent=1)


def main():
    os.makedirs(ANTEPRIMA, exist_ok=True)
    elenco = []
    tavola = []
    for i, c in enumerate(CARROZZERIE):
        liv = disegna(c)
        salva_livelli(c, liv)
        elenco.append(dict(id=c["id"], nome=c["nome"], marche=c["marche"]))
        col = COLORI[i % len(COLORI)][1]
        tavola.append((c, componi_da_file(c["id"], col), liv))
        print(c["id"], "ok")
    with open(os.path.join(ASSETS, "elenco.json"), "w", encoding="utf-8") as f:
        json.dump(dict(carrozzerie=elenco, colori=[dict(nome=n, rgb=list(v)) for n, v in COLORI],
                       tetti=[dict(nome=n, rgb=list(v) if v else None) for n, v in TETTI]), f, ensure_ascii=False, indent=1)
    # tavola di tutte le carrozzerie (sfondo scuro come l'iDrive)
    cw, ch = 600, 260
    col_n = 3
    righe = (len(tavola) + col_n - 1) // col_n
    foglio = Image.new("RGB", (cw * col_n, ch * righe), (14, 22, 34))
    d = ImageDraw.Draw(foglio)
    from PIL import ImageFont
    f = ImageFont.truetype("/usr/share/fonts/opentype/inter/Inter-Medium.otf", 20)
    for k, (c, im, liv) in enumerate(tavola):
        x, y = (k % col_n) * cw, (k // col_n) * ch
        foglio.paste(im.resize((cw, int(cw * H / W)), Image.LANCZOS), (x, y + 6), im.resize((cw, int(cw * H / W)), Image.LANCZOS))
        d.text((x + 16, y + 8), c["nome"], font=f, fill=(220, 226, 232))
    foglio.save(os.path.join(ANTEPRIMA, "carrozzerie.png"))
    # colori e tetti su una carrozzeria
    foglio = Image.new("RGB", (cw * 4, ch * 4), (14, 22, 34))
    d = ImageDraw.Draw(foglio)
    varianti = [(n, v, None) for n, v in COLORI] + [("Rosso, tetto bianco", COLORI[6][1], TETTI[1][1]), ("Azzurro, tetto bianco", COLORI[5][1], TETTI[1][1]),
                                                 ("Bianco, tetto nero", COLORI[0][1], TETTI[2][1]), ("Verde scuro, tetto bianco", COLORI[7][1], TETTI[1][1])]
    for k, (n, v, t) in enumerate(varianti):
        x, y = (k % 4) * cw, (k // 4) * ch
        im = componi_da_file("utilitaria3", v, t).resize((cw, int(cw * H / W)), Image.LANCZOS)
        foglio.paste(im, (x, y + 6), im)
        d.text((x + 16, y + 8), n, font=f, fill=(220, 226, 232))
    foglio.save(os.path.join(ANTEPRIMA, "colori.png"))
    # riferimento per il test dell'app (SagomeTest): la stessa composizione deve uscire uguale da Sagome.kt
    componi_da_file("utilitaria3", COLORI[6][1], TETTI[1][1]).save(os.path.join(ANTEPRIMA, "verifica_utilitaria3.png"))


if __name__ == "__main__":
    main()
