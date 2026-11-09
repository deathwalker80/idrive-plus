#!/usr/bin/env python3
"""
Genera i file RHMI dei plugin "da scegliere con la manopola" (Notizie, Notizie locali, Carburanti v13): sfondo
(raImageModel 71) che il telefono ridisegna con la riga scelta, sopra un pulsante per riga con posizione e misura
uguali a Disegno.rettangoloRigaNotizia / rettangoloDistributore (stessi conti in virgola mobile a 32 bit).
Uso: python3 plugin/genera_xml_scelte.py   (scrive chiavetta/Notizie/Notizie.xml, Locali/Locali.xml, Carburanti/Carburanti.xml)
"""
import os
import struct

QUI = os.path.dirname(os.path.abspath(__file__))
CHIAVETTA = os.path.join(QUI, "..", "chiavetta")


def f32(x):
    return struct.unpack("f", struct.pack("f", x))[0]


W, H, PL, PR, PT, PB, TELA_W, TELA_H = 1280.0, 480.0, 10.0, 140.0, 6.0, 12.0, 1200.0, 440.0
SCALA = min(f32(f32(W - PL - PR) / TELA_W), f32(f32(H - PT - PB) / TELA_H))


def su_schermo(x, y, w, h):
    """Disegno.suSchermo: (PL + x * scala).toInt() ecc."""
    return [int(f32(PL + f32(f32(x) * SCALA))), int(f32(PT + f32(f32(y) * SCALA))), int(f32(f32(w) * SCALA)), int(f32(f32(h) * SCALA))]


def riga_notizia(i):
    pp_x, pp_y, pp_riga = 606.0, 66.0, 46.5
    return su_schermo(pp_x, f32(pp_y + f32(i * pp_riga)), f32(TELA_W - pp_x), pp_riga)


def distributore(i):
    return su_schermo(f32((i % 3) * f32(388.0 + 18.0)), f32(74.0 + f32((i // 3) * f32(172.0 + 18.0))), 388.0, 172.0)


def xml(nome, testo, focus, icona, righe, rettangolo, etichetta, commento, anteprima=True):
    a = []
    p = a.append
    p('<?xml version="1.0" encoding="UTF-8"?>')
    p("<pluginApps>")
    p(f'  <pluginApp applicationType="Custom" applicationWeight="200" entryButtonFocusID="{focus}" insertStateID="50" text="{testo}">')
    p(f"    <comment>{commento}</comment>")
    p(f'    <entryButton action="5" id="1" model="3" imageModel="4"{" previewModel=\"13\"" if anteprima else ""}>')
    p("      <properties/>")
    p("    </entryButton>")
    p("    <actions>")
    p('      <hmiAction id="5" target="100"/>')
    for i in range(righe):
        p(f'      <hmiAction id="{6 + i}" target="{110 + i}"/>')
    for i in range(righe):
        p(f'      <raAction id="{20 + i}"/>')
    p("    </actions>")
    p("    <models>")
    p('      <textIdModel id="3" textId="1">')
    p("        <synonyms/>")
    p("      </textIdModel>")
    p(f'      <imageIdModel id="4" imageId="{icona}"/>')
    for i in range(righe):
        p(f'      <raDataModel id="{41 + i}"/>')
    for i in range(righe):
        p(f'      <raImageModel id="{51 + i}"/>')
    for i in range(righe):
        p(f'      <raImageModel id="{61 + i}"/>')
    p('      <raImageModel id="71"/>')
    if anteprima:
        p('      <raPreviewModel id="13" modelType="StatePreview" previewState="100"/>')
    p("    </models>")
    p("    <events/>")
    p("    <hmiStates>")
    p(f'      <hmiState id="100" text="{testo}" textModel="3">')
    p("        <properties>")
    p('          <property id="24" value="3"/>')
    p("        </properties>")
    p("        <components>")
    p('          <image id="300" model="71">')
    p("            <properties>")
    for k, v in [(2, "false"), (3, "true"), (9, 1280), (10, 480), (20, 0), (21, 0)]:
        p(f'              <property id="{k}" value="{v}"/>')
    p("            </properties>")
    p("          </image>")
    for i in range(righe):
        x, y, w, h = rettangolo(i)
        p(f'          <button action="{6 + i}" selectAction="{20 + i}" id="{200 + i}" model="{41 + i}" imageModel="{61 + i}" text="{etichetta} {i + 1}">')
        p("            <properties>")
        for k, v in [(9, w), (10, h), (20, x), (21, y)]:
            p(f'              <property id="{k}" value="{v}"/>')
        p("            </properties>")
        p("          </button>")
    p("        </components>")
    p("      </hmiState>")
    for i in range(righe):
        p(f'      <hmiState id="{110 + i}" parentId="100" text="{testo}" textModel="3">')
        p("        <properties>")
        p('          <property id="24" value="3"/>')
        p("        </properties>")
        p("        <components>")
        p(f'          <image id="{210 + i}" model="{51 + i}">')
        p("            <properties>")
        for k, v in [(3, "true"), (9, 1280), (10, 480), (20, 0), (21, 0)]:
            p(f'              <property id="{k}" value="{v}"/>')
        p("            </properties>")
        p("          </image>")
        p("        </components>")
        p("      </hmiState>")
    p("    </hmiStates>")
    p("  </pluginApp>")
    p("</pluginApps>")
    percorso = os.path.join(CHIAVETTA, nome, nome + ".xml")
    with open(percorso, "w", encoding="utf-8") as f:
        f.write("\n".join(a) + "\n")
    print(percorso)


COMM_NOTIZIE = ("{} v13 (pulsanti: x,y,w,h come Disegno.rettangoloRigaNotizia) - in primo piano: a destra otto righe "
                "disegnate dal telefono, girando la manopola a sinistra compare la notizia scelta in grande, premendo si apre")
xml("Notizie", "Notizie", 32764, 58001, 8, riga_notizia, "Notizia", COMM_NOTIZIE.format("Notizie"), anteprima=False)
xml("Locali", "Notizie locali", 32765, 60001, 8, riga_notizia, "Notizia", COMM_NOTIZIE.format("Notizie locali"), anteprima=False)
xml("Carburanti", "Carburanti", 32766, 61001, 6, distributore, "Distributore",
    "Carburanti v13 (pulsanti: x,y,w,h come Disegno.rettangoloDistributore) - sei schede: girando la manopola il telefono "
    "ridisegna la pagina col distributore scelto evidenziato, premendo si apre la sua scheda con tutti i prezzi")
