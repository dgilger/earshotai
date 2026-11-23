"""Earshot rev A enclosure: sealed, resealable, parametric (CadQuery).

Run from this folder:   python3 earshot_case.py          (writes out/*.step, out/*.stl)
                        python3 earshot_case.py --check  (also builds the board and battery and
                                                          reports any interference)

Coordinates (mm): origin at the centre of the PCB's back face.
  x = KiCad x - 115   (right, seen from the front)
  y = 125 - KiCad y   (up: mics at +y, USB-C at -y)
  z = up out of the front face (components side). PCB spans z 0..0.8, battery is below it.

Parts:
  shell    front shell: face, walls, board pocket, USB-C opening, mic ports, button bore, LED pipe
  back     back plug: radial O-ring seal, battery cradle, mic ducts, magnet pockets, board support
  button   plunger with its own O-ring
Bought parts are listed in README.md.
"""
import math
import os
import sys

import cadquery as cq

# ---------------------------------------------------------------- parameters
PCB_T = 0.8
BOARD_HX, BOARD_HY = 15.0, 25.0          # 30 x 50 board
POCKET_GAP = 0.15                        # board to pocket wall, each side
CEIL_Z = 4.5                             # inside of the front wall (USB-C shell top is z 4.05)
FRONT_Z = 6.0                            # outside of the front face
PART_Z = -9.4                            # parting line = inside of the back floor
BACK_Z = -11.8                           # outside of the back

# battery LP803040 (8 x 30 x 42) sits under the board, top end 6.25 mm below the board's top edge
BAT_W, BAT_L, BAT_T = 30.0, 42.0, 8.0
BAT_Y0, BAT_Y1 = -23.25, 18.75
BAT_Z1 = -1.4                            # top face (1.5 mm foam pad above it, squeezed to 1.4)

# back plug ring (carries the O-ring) and the shell bore it slides into
RING_IN_HX = 15.3
RING_IN_Y0, RING_IN_Y1 = -23.45, 23.55
RING_T = 1.75
RING_R_OUT = 3.55                        # groove root radius 2.4; inner corner 1.8 clears the cell's corners
FIT = 0.05                               # ring to bore, radial
WALL_SIDE, WALL_TOP, WALL_BOT = 1.2, 1.2, 0.8   # shell walls around the bore (bottom is thin: USB-C)

ORING_CS, ORING_ID = 1.5, 48.0          # stock metric O-ring 48 x 1.5 mm (51 OD), NBR70 or silicone
GROOVE_DEPTH = 1.15                      # 20 % squeeze with FIT
GROOVE_W = 2.0
GROOVE_Z = -8.15                         # centre; close to the mouth so it seals in the last ~1.2 mm

FRONT_FILLET = 2.0
CROWN_R = 280.0                          # front face is a cylinder across the width: 0.6 mm crown
LABEL = ("earshot", "rev A")             # debossed on the back, 0.3 mm deep
BACK_FILLET = 1.6
REVEAL = 0.4                             # chamfer on both sides of the parting line

# USB-C (Hroparts TYPE-C-31-M-12 at KiCad 115,145): shell front face is at y -24.7
USB_Z = PCB_T + 1.65                     # receptacle centre line
USB_W, USB_H = 8.65, 2.85                # plug shell is 8.25 x 2.40: 0.2 mm all round
USB_CHAMFER = 0.35

# mics (bottom-port, sound holes at KiCad (103.5|126.5, 104.8))
MIC_X, MIC_Y = 11.5, 20.2
DUCT_D = 0.9
DUCT_Z = -2.2
MEMBRANE_D, MEMBRANE_DEPTH = 3.6, 0.3    # ePTFE acoustic vent, inside face of the shell
GASKET_D, GASKET_IN, GASKET_SEAT = 4.2, 2.0, 0.25   # face-seal groove for a 4 x 2 x 1 mm O-ring (20 % squeeze)
COL_TOP = -0.55                          # column top; groove floor at -0.8, 1.0 mm O-ring squeezed to 0.8

# button SW1 (KiCad 125.5,124) and LED D2 (KiCad 120.5,127.8)
BTN_X, BTN_Y = 10.5, 1.0
BTN_ACT_Z = PCB_T + 1.5
BTN_BORE = 3.6
BTN_STEM = 3.5
BTN_BOSS_D, BTN_BOSS_Z = 6.0, 3.0
BTN_DISH_D, BTN_DISH_DEPTH = 7.0, 0.5
LED_X, LED_Y = 5.5, -2.8
# printed clear light pipe, same stem and O-ring (4 x 2 x 1 mm) as the button; its foot hoods the LED
LP_BOSS_D, LP_BOSS_Z = 5.0, 2.4
LP_STEP_Z = 5.2                          # bore steps down to the Ø2.1 window here
LP_TIP = 2.0

# magnets 10 x 5 x 2 N52, back floor, either side of the antenna, below it
MAG = (5.2, 10.2, 2.0)
MAG_X, MAG_Y = 12.6, 10.0

# ---------------------------------------------------------------- derived
RING_OUT_HX = RING_IN_HX + RING_T
RING_OUT_Y0, RING_OUT_Y1 = RING_IN_Y0 - RING_T, RING_IN_Y1 + RING_T
BORE_HX = RING_OUT_HX + FIT
BORE_Y0, BORE_Y1 = RING_OUT_Y0 - FIT, RING_OUT_Y1 + FIT
BORE_R = RING_R_OUT + FIT
OUT_HX = BORE_HX + WALL_SIDE
OUT_Y0, OUT_Y1 = BORE_Y0 - WALL_BOT, BORE_Y1 + WALL_TOP
OUT_R = BORE_R + WALL_SIDE
PKT_HX = BOARD_HX + POCKET_GAP
PKT_Y0, PKT_Y1 = -BOARD_HY - POCKET_GAP, BOARD_HY + POCKET_GAP


# ---------------------------------------------------------------- helpers
def rrect_face(x0, y0, x1, y1, r, z=0.0):
    sk = cq.Sketch().rect(x1 - x0, y1 - y0)
    if r > 0:
        sk = sk.vertices().fillet(r)
    f = sk._faces.Faces()[0]
    return f.moved(cq.Location(cq.Vector((x0 + x1) / 2, (y0 + y1) / 2, z)))


def rrect(x0, y0, x1, y1, r, z0, z1):
    return cq.Workplane().add(cq.Solid.extrudeLinear(rrect_face(x0, y0, x1, y1, r, z0), cq.Vector(0, 0, z1 - z0)))


def box(x0, y0, z0, x1, y1, z1):
    return cq.Workplane().add(cq.Solid.makeBox(x1 - x0, y1 - y0, z1 - z0, cq.Vector(x0, y0, z0)))


def cyl(d, x, y, z0, z1):
    return cq.Workplane().add(cq.Solid.makeCylinder(d / 2, z1 - z0, cq.Vector(x, y, z0)))


def ycyl(d, x, z, y0, y1):
    return cq.Workplane().add(cq.Solid.makeCylinder(d / 2, y1 - y0, cq.Vector(x, y0, z), cq.Vector(0, 1, 0)))


def cone_y(d0, d1, x, z, y0, y1):
    return cq.Workplane().add(cq.Solid.makeCone(d0 / 2, d1 / 2, y1 - y0, cq.Vector(x, y0, z), cq.Vector(0, 1, 0)))


def slot_y(w, h, x, z, y0, grow=0.0):
    """Stadium (w x h, round ends left/right) in the XZ plane, extruded along +y."""
    w, h = w + 2 * grow, h + 2 * grow
    f = cq.Sketch().slot(w - h, h).reset()._faces.Faces()[0]
    f = f.rotate(cq.Vector(), cq.Vector(1, 0, 0), 90).moved(cq.Location(cq.Vector(x, y0, z)))
    return f


# ---------------------------------------------------------------- shell
def face_z(x):
    """Height of the crowned front face at x."""
    return FRONT_Z - CROWN_R + math.sqrt(CROWN_R ** 2 - x ** 2)


def make_shell():
    s = rrect(-OUT_HX, OUT_Y0, OUT_HX, OUT_Y1, OUT_R, PART_Z, FRONT_Z)
    crown = cq.Solid.makeCylinder(CROWN_R, 200, cq.Vector(0, -100, FRONT_Z - CROWN_R), cq.Vector(0, 1, 0))
    s = s.intersect(cq.Workplane().add(crown))
    s = s.edges(cq.selectors.BoxSelector((-50, -50, FRONT_Z - 1.0), (50, 50, FRONT_Z + 1))).fillet(FRONT_FILLET)
    s = s.faces("<Z").edges().chamfer(REVEAL)

    # inside: bore for the back plug, then the board pocket / component space above it
    s = s.cut(rrect(-BORE_HX, BORE_Y0, BORE_HX, BORE_Y1, BORE_R, PART_Z - 1, 0))
    s = s.cut(box(-PKT_HX, PKT_Y0, -0.01, PKT_HX, PKT_Y1, CEIL_Z))
    for sx in (-1, 1):                                   # corner reliefs (board corners are sharp)
        for sy, yy in ((-1, PKT_Y0), (1, PKT_Y1)):
            s = s.cut(cyl(0.9, sx * (PKT_HX - 0.15), yy - sy * 0.15, -0.01, CEIL_Z))

    # bore mouth lead-in for the O-ring
    mouth = cq.Solid.makeLoft([
        rrect_face(-BORE_HX - 0.45, BORE_Y0 - 0.45, BORE_HX + 0.45, BORE_Y1 + 0.45, BORE_R + 0.45, PART_Z - 0.01).outerWire(),
        rrect_face(-BORE_HX, BORE_Y0, BORE_HX, BORE_Y1, BORE_R, PART_Z + 0.45).outerWire()], True)
    s = s.cut(cq.Workplane().add(mouth))

    # battery wire passes over the right board edge beside J3
    s = s.cut(box(PKT_HX - 0.2, -20.0, -0.01, BORE_HX - 0.4, -10.0, 4.2))

    # bosses hanging from the front wall: button guide and LED light-pipe tube
    s = s.union(cyl(BTN_BOSS_D, BTN_X, BTN_Y, BTN_BOSS_Z, CEIL_Z + 0.1))
    s = s.union(cyl(LP_BOSS_D, LED_X, LED_Y, LP_BOSS_Z, CEIL_Z + 0.1))

    # crush ribs that press the board's top face along its edges (all in part-free strips)
    rib_w, rib_d, rib_z = 2.0, 0.55, PCB_T - 0.03
    ribs = []
    for y in (20.0, 11.0, -16.0):      # y 11 (KiCad 114): between the zone-A parts; microSD J4 covers KiCad y 119.45-133.05
        ribs.append((-PKT_HX, y - rib_w / 2, -PKT_HX + rib_d + POCKET_GAP, y + rib_w / 2))
    for y in (16.0, 4.0, -5.0, -23.0):
        ribs.append((PKT_HX - rib_d - POCKET_GAP, y - rib_w / 2, PKT_HX, y + rib_w / 2))
    for x in (-13.5, -8.0, 9.5, 13.5):  # top edge: x 9.5 clears the E73 module (KiCad x 110-123)
        ribs.append((x - rib_w / 2, PKT_Y1 - rib_d - POCKET_GAP, x + rib_w / 2, PKT_Y1))
    for x in (-13.5, -8.0, 8.0, 13.5):
        ribs.append((x - rib_w / 2, PKT_Y0, x + rib_w / 2, PKT_Y0 + rib_d + POCKET_GAP))
    for x0, y0, x1, y1 in ribs:
        s = s.union(box(x0, y0, rib_z, x1, y1, CEIL_Z + 0.1))

    # USB-C: plug-sized opening through the thin bottom wall, chamfered outside
    s = s.cut(cq.Workplane().add(cq.Solid.extrudeLinear(slot_y(USB_W, USB_H, 0, USB_Z, OUT_Y0 - 1), cq.Vector(0, 3, 0))))
    s = s.cut(cq.Workplane().add(cq.Solid.makeLoft([
        slot_y(USB_W, USB_H, 0, USB_Z, OUT_Y0 - 0.01, grow=USB_CHAMFER + 0.01).outerWire(),
        slot_y(USB_W, USB_H, 0, USB_Z, OUT_Y0 + USB_CHAMFER).outerWire()], True)))
    s = s.cut(box(-3.6, PKT_Y0 - 0.3, PCB_T - 0.1, 3.6, PKT_Y0 + 0.1, PCB_T + 0.35))   # receptacle front tab

    # mic ports: straight 0.9 mm holes, outer chamfer, ePTFE membrane recess on the inside face
    for sx in (-1, 1):
        x = sx * MIC_X
        s = s.cut(ycyl(DUCT_D, x, DUCT_Z, BORE_Y1 - 0.1, OUT_Y1 + 0.1))
        s = s.cut(cone_y(DUCT_D, DUCT_D + 0.7, x, DUCT_Z, OUT_Y1 - 0.35, OUT_Y1 + 0.001))
        s = s.cut(ycyl(MEMBRANE_D, x, DUCT_Z, BORE_Y1 - 0.1, BORE_Y1 + MEMBRANE_DEPTH))

    # button bore + finger dish; LED rod bore
    s = s.cut(cyl(BTN_BORE, BTN_X, BTN_Y, BTN_BOSS_Z - 0.1, FRONT_Z + 1))
    bz = face_z(BTN_X)
    s = s.cut(cq.Workplane().add(cq.Solid.makeCone(BTN_BORE / 2 + 0.3, BTN_BORE / 2, 0.3, cq.Vector(BTN_X, BTN_Y, BTN_BOSS_Z - 0.001))))
    rs = ((BTN_DISH_D / 2) ** 2 + BTN_DISH_DEPTH ** 2) / (2 * BTN_DISH_DEPTH)
    s = s.cut(cq.Workplane().add(cq.Solid.makeSphere(rs, cq.Vector(BTN_X, BTN_Y, bz - BTN_DISH_DEPTH + rs))))
    s = s.cut(cyl(BTN_BORE, LED_X, LED_Y, LP_BOSS_Z - 0.1, LP_STEP_Z))
    s = s.cut(cq.Workplane().add(cq.Solid.makeCone(BTN_BORE / 2 + 0.3, BTN_BORE / 2, 0.3, cq.Vector(LED_X, LED_Y, LP_BOSS_Z - 0.001))))
    s = s.cut(cyl(LP_TIP + 0.1, LED_X, LED_Y, LP_STEP_Z - 0.01, FRONT_Z + 1))

    # detent pockets for the back plug's latch bumps
    for sx in (-1, 1):
        for y in (-10.0, 10.0):
            x0 = BORE_HX - 0.1 if sx > 0 else -BORE_HX - 0.35
            s = s.cut(box(x0, y - 2.8, -2.7, x0 + 0.45, y + 2.8, -1.3))
    return s


# ---------------------------------------------------------------- back plug
def make_back():
    b = rrect(-OUT_HX, OUT_Y0, OUT_HX, OUT_Y1, OUT_R, BACK_Z, PART_Z)
    b = b.edges("<Z").fillet(BACK_FILLET)
    b = b.faces(">Z").edges().chamfer(REVEAL)

    ring = rrect(-RING_OUT_HX, RING_OUT_Y0, RING_OUT_HX, RING_OUT_Y1, RING_R_OUT, PART_Z - 0.01, 0)
    ring = ring.cut(rrect(-RING_IN_HX, RING_IN_Y0, RING_IN_HX, RING_IN_Y1, RING_R_OUT - RING_T, PART_Z - 1, 1))
    # lead-in on the ring's leading edge
    lead = cq.Solid.makeLoft([
        rrect_face(-RING_OUT_HX - 0.01, RING_OUT_Y0 - 0.01, RING_OUT_HX + 0.01, RING_OUT_Y1 + 0.01, RING_R_OUT, -0.5).outerWire(),
        rrect_face(-RING_OUT_HX - 0.01, RING_OUT_Y0 - 0.01, RING_OUT_HX + 0.01, RING_OUT_Y1 + 0.01, RING_R_OUT, 0.01).outerWire(),
    ], True)
    lead_keep = cq.Solid.makeLoft([
        rrect_face(-RING_OUT_HX, RING_OUT_Y0, RING_OUT_HX, RING_OUT_Y1, RING_R_OUT, -0.5).outerWire(),
        rrect_face(-RING_OUT_HX + 0.4, RING_OUT_Y0 + 0.4, RING_OUT_HX - 0.4, RING_OUT_Y1 - 0.4, RING_R_OUT - 0.4, 0.01).outerWire(),
    ], True)
    ring = ring.cut(cq.Workplane().add(lead).cut(cq.Workplane().add(lead_keep)))
    # O-ring groove
    g = rrect(-RING_OUT_HX - 1, RING_OUT_Y0 - 1, RING_OUT_HX + 1, RING_OUT_Y1 + 1, 0, GROOVE_Z - GROOVE_W / 2, GROOVE_Z + GROOVE_W / 2)
    g = g.cut(rrect(-RING_OUT_HX + GROOVE_DEPTH, RING_OUT_Y0 + GROOVE_DEPTH, RING_OUT_HX - GROOVE_DEPTH,
                    RING_OUT_Y1 - GROOVE_DEPTH, RING_R_OUT - GROOVE_DEPTH, GROOVE_Z - 1, GROOVE_Z + 1))
    ring = ring.cut(g)
    # latch bumps (ramped on the leading side) that click into the shell's detent pockets
    for sx in (-1, 1):
        for y in (-10.0, 10.0):
            prof = (cq.Workplane("XZ").polyline([(0, -2.45), (0.22, -2.25), (0.22, -2.05), (0, -1.35)]).close()
                    .extrude(2.5, both=True))
            prof = prof.translate((RING_OUT_HX - 0.05, y, 0))
            if sx < 0:
                prof = prof.mirror("YZ")
            ring = ring.union(prof)
    # notch where the battery wire comes over the board edge
    ring = ring.cut(box(RING_IN_HX - 0.1, -20.0, -3.0, RING_OUT_HX + 0.5, -10.0, 0.1))
    # clearance for the USB-C shell legs that poke through the PCB
    ring = ring.cut(box(-5.4, RING_OUT_Y0 - 0.1, -0.5, 5.4, RING_IN_Y0 + 0.1, 0.1))
    b = b.union(ring)

    # mic duct columns: gasket seat at the PCB sound hole, 0.9 mm duct out through the ring
    for sx in (-1, 1):
        x = sx * MIC_X
        col = rrect(x - 2.5, BAT_Y1 + 0.2, x + 2.5, RING_IN_Y1 + 0.2, 0.0, PART_Z - 0.01, COL_TOP)
        b = b.union(col)
        # shelf over the cell's top edge so the gasket seat is fully supported
        b = b.union(box(x - 2.5, MIC_Y - GASKET_D / 2 - 0.4, BAT_Z1 + 0.15, x + 2.5, BAT_Y1 + 0.3, COL_TOP))
        b = b.cut(cyl(GASKET_D, x, MIC_Y, COL_TOP - GASKET_SEAT, COL_TOP + 1).cut(cyl(GASKET_IN, x, MIC_Y, COL_TOP - 1, COL_TOP + 2)))
        b = b.cut(cyl(DUCT_D, x, MIC_Y, DUCT_Z - DUCT_D / 2, COL_TOP))
        b = b.cut(ycyl(DUCT_D, x, DUCT_Z, MIC_Y, RING_OUT_Y1 + 0.5))
    # board support tabs under the side edges (the ring top supports the top and bottom edges)
    for sx in (-1, 1):
        for y in ((10.0, -4.0) if sx > 0 else (10.0, -4.0, -18.0)):
            x0, x1 = (BOARD_HX - 0.6, RING_IN_HX + 0.1) if sx > 0 else (-RING_IN_HX - 0.1, -BOARD_HX + 0.6)
            b = b.union(box(x0, y - 2.0, -1.0, x1, y + 2.0, 0.0))
    # battery cradle ribs (crush ribs, 0.1 mm interference with the nominal cell)
    for sx in (-1, 1):
        for y in (12.0, 0.0, -21.0):
            x0, x1 = (BAT_W / 2 - 0.1, RING_IN_HX + 0.1) if sx > 0 else (-RING_IN_HX - 0.1, -BAT_W / 2 + 0.1)
            b = b.union(box(x0, y - 0.6, PART_Z, x1, y + 0.6, -4.0))
    for x in (-8.0, 8.0):
        b = b.union(box(x - 0.6, RING_IN_Y0 - 0.1, PART_Z, x + 0.6, BAT_Y0 + 0.1, -4.0))
    # magnet pockets from the inside, 0.4 mm skin
    for sx in (-1, 1):
        b = b.cut(box(sx * MAG_X - MAG[0] / 2, MAG_Y - MAG[1] / 2, BACK_Z + 0.4, sx * MAG_X + MAG[0] / 2, MAG_Y + MAG[1] / 2, PART_Z + 0.01))
    # label on the back (mirrored so it reads from behind)
    try:
        for txt, size, y in ((LABEL[0], 5.0, -6.0), (LABEL[1], 2.2, -11.5)):
            t = cq.Workplane("XY").text(txt, size, 0.31, kind="bold", halign="center", valign="center")
            t = t.mirror("YZ").translate((0, y, BACK_Z - 0.01))
            b = b.cut(t)
    except Exception as e:                    # fonts differ between machines; the label is cosmetic
        print("label skipped:", e)
    # pry notch at the bottom edge of the parting line
    b = b.cut(box(-2.0, OUT_Y0 - 0.5, PART_Z - 0.7, 2.0, OUT_Y0 + 0.6, PART_Z + 0.01))
    return b


# ---------------------------------------------------------------- button plunger
def make_button():
    foot = cyl(1.8, BTN_X, BTN_Y, BTN_ACT_Z + 0.05, BTN_ACT_Z + 0.35)
    flange = cyl(4.6, BTN_X, BTN_Y, BTN_ACT_Z + 0.35, BTN_BOSS_Z - 0.15)
    top = face_z(BTN_X) - 0.25               # 0.25 proud of the dish floor, 0.25 below the face
    stem = cyl(BTN_STEM, BTN_X, BTN_Y, BTN_BOSS_Z - 0.15, top)
    p = foot.union(flange).union(stem)
    p = p.faces(">Z").edges().fillet(0.4)
    gz = BTN_BOSS_Z + 1.2
    p = p.cut(cyl(BTN_STEM + 1, BTN_X, BTN_Y, gz, gz + 1.25).cut(cyl(2.1, BTN_X, BTN_Y, gz - 1, gz + 2)))
    return p


# ---------------------------------------------------------------- light pipe (print in clear resin)
def make_lightpipe():
    foot_z = PCB_T + 0.02
    p = cyl(3.3, LED_X, LED_Y, foot_z, 1.9)                       # hood over the LED, blocks side glow
    p = p.union(cyl(BTN_STEM, LED_X, LED_Y, 1.9, LP_STEP_Z - 0.05))
    p = p.union(cyl(LP_TIP, LED_X, LED_Y, LP_STEP_Z - 0.1, face_z(LED_X)))
    p = p.cut(cyl(2.1, LED_X, LED_Y, foot_z - 0.1, PCB_T + 1.0))  # LED pocket (LED is 0.8 tall)
    gz = 3.0
    p = p.cut(cyl(BTN_STEM + 1, LED_X, LED_Y, gz, gz + 1.25).cut(cyl(2.1, LED_X, LED_Y, gz - 1, gz + 2)))
    return p


# ---------------------------------------------------------------- board + battery (for checks/renders)
def make_board(with_usb_step=True):
    here = os.path.dirname(os.path.abspath(__file__))
    pcb = box(-BOARD_HX, -BOARD_HY, 0, BOARD_HX, BOARD_HY, PCB_T)
    for x in (-MIC_X, MIC_X):
        pcb = pcb.cut(cyl(0.5, x, MIC_Y, -1, 2))
    parts = []
    # (name, KiCad bbox x0,y0,x1,y1 of the body, height above the PCB)
    bodies = [
        ("U1 module", 110.0, 100.0, 123.0, 118.0, 2.2),
        ("SW1", 122.95, 121.45, 128.05, 126.55, 0.9), ("SW1 actuator", 124.5, 123.0, 126.5, 125.0, 1.5),
        ("J3+plug", 120.85, 136.25, 129.6, 143.75, 3.5),
        ("J4 microSD", 100.05, 119.45, 114.55, 133.05, 1.8),
        ("U6", 101.75, 102.2, 105.25, 105.8, 1.0), ("U7", 124.75, 102.2, 128.25, 105.8, 1.0),
        ("D1", 113.6, 138.6, 116.4, 140.0, 1.6), ("U3", 117.5, 123.15, 119.1, 126.05, 1.1),
        ("Q1", 101.35, 109.75, 102.65, 112.65, 1.1), ("U4", 122.0, 132.5, 124.0, 134.5, 0.8),
        ("Y1", 105.9, 116.25, 109.1, 117.75, 0.9), ("D2 LED", 120.1, 127.0, 120.9, 128.6, 0.8),
        ("R12", 120.25, 124.5, 120.75, 125.5, 0.35), ("R5", 121.55, 130.1, 122.05, 131.1, 0.35),
        ("R6", 123.95, 130.1, 124.45, 131.1, 0.35),
        ("C2", 125.1, 111.9, 126.7, 112.7, 0.9), ("C6", 118.0, 119.9, 118.8, 121.5, 0.9),
        ("C9", 117.5, 130.6, 119.1, 131.4, 0.9),
    ]
    for name, x0, y0, x1, y1, h in bodies:
        parts.append(box(x0 - 115, 125 - y1, PCB_T, x1 - 115, 125 - y0, PCB_T + h))
    if with_usb_step:
        st = cq.importers.importStep(os.path.join(here, "../earshot-revA/lib/earshot.3dshapes/USB-C_SMD-TYPE-C-31-M-12_1.step"))
        # KiCad footprint (y down) -> model (y up) is identity here; matches the courtyard
        usb = st.val().Solids()[0].moved(cq.Location(cq.Vector(0, 125 - 145, PCB_T)))
        parts.append(cq.Workplane().add(usb))
    else:
        parts.append(box(-4.47, -24.7, PCB_T + 0.05, 4.47, -17.4, PCB_T + 3.25))
    return pcb, parts


def make_battery():
    return box(-BAT_W / 2, BAT_Y0, BAT_Z1 - BAT_T, BAT_W / 2, BAT_Y1, BAT_Z1).edges("|Z").fillet(1.0)


def vol(a, b):
    try:
        return a.intersect(b).val().Volume()
    except Exception:
        return 0.0


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    out = os.path.join(here, "out")
    os.makedirs(out, exist_ok=True)
    shell, back, button, pipe = make_shell(), make_back(), make_button(), make_lightpipe()
    for name, part in (("shell", shell), ("back", back), ("button", button), ("lightpipe", pipe)):
        cq.exporters.export(part, os.path.join(out, f"earshot-{name}.step"))
        cq.exporters.export(part, os.path.join(out, f"earshot-{name}.stl"), tolerance=0.01, angularTolerance=0.1)
        bb = part.val().BoundingBox()
        print(f"{name:7s} {bb.xlen:6.2f} x {bb.ylen:6.2f} x {bb.zlen:6.2f} mm  vol {part.val().Volume()/1000:5.2f} cm3")
    print(f"outside: {2*OUT_HX:.1f} x {OUT_Y1-OUT_Y0:.1f} x {FRONT_Z-BACK_Z:.1f} mm")
    print(f"USB-C receptacle face to outside: {-24.7 - OUT_Y0:.2f} mm")
    r = RING_R_OUT - GROOVE_DEPTH
    root = 2 * (2 * (RING_OUT_HX - GROOVE_DEPTH) + (RING_OUT_Y1 - RING_OUT_Y0 - 2 * GROOVE_DEPTH)) - 8 * r + 2 * math.pi * r
    gland = GROOVE_DEPTH + FIT
    print(f"O-ring {ORING_ID:g} x {ORING_CS:g}: stretch {100 * (root / (math.pi * ORING_ID) - 1):.1f} %, "
          f"squeeze {100 * (1 - gland / ORING_CS):.0f} %, groove fill {100 * math.pi * ORING_CS**2 / 4 / (GROOVE_W * gland):.0f} %")
    if "--check" in sys.argv:
        pcb, parts = make_board()
        bat = make_battery()
        checks = [("shell x back", shell, back), ("shell x button", shell, button), ("back x button", back, button),
                  ("shell x lightpipe", shell, pipe), ("back x lightpipe", back, pipe),
                  ("shell x pcb", shell, pcb), ("back x pcb", back, pcb), ("back x battery", back, bat),
                  ("shell x battery", shell, bat), ("button x pcb", button, pcb)]
        for i, p in enumerate(parts):
            checks += [(f"shell x part{i}", shell, p), (f"back x part{i}", back, p), (f"button x part{i}", button, p),
                       (f"lightpipe x part{i}", pipe, p)]
        checks.append(("lightpipe x pcb", pipe, pcb))
        for n, a, b in checks:
            v = vol(a, b)
            if v > 1e-4:
                bb = a.intersect(b).val().BoundingBox()
                print(f"  overlap {n}: {v:.4f} mm3 at x {bb.xmin:.2f}..{bb.xmax:.2f} y {bb.ymin:.2f}..{bb.ymax:.2f} z {bb.zmin:.2f}..{bb.zmax:.2f}")
        board = pcb
        for p in parts:
            board = board.union(p)
        cq.exporters.export(board, os.path.join(out, "ref-board.stl"), tolerance=0.02)
        cq.exporters.export(bat, os.path.join(out, "ref-battery.stl"), tolerance=0.02)
        asm = cq.Assembly()
        asm.add(shell, name="shell", color=cq.Color(0.15, 0.16, 0.17))
        asm.add(back, name="back", color=cq.Color(0.55, 0.57, 0.55))
        asm.add(button, name="button", color=cq.Color(0.85, 0.45, 0.1))
        asm.add(pipe, name="lightpipe", color=cq.Color(0.8, 0.9, 1.0))
        asm.add(pcb, name="pcb", color=cq.Color(0.1, 0.35, 0.2))
        for i, p in enumerate(parts):
            asm.add(p, name=f"part{i}", color=cq.Color(0.6, 0.6, 0.6))
        asm.add(bat, name="battery", color=cq.Color(0.75, 0.7, 0.55))
        asm.save(os.path.join(out, "earshot-assembly.step"))


if __name__ == "__main__":
    main()
