"""Trace a black-on-white logo PNG into a KiCad footprint of filled silkscreen polygons.

Usage (OpenCV is pulled in on the fly):
    uvx --with opencv-python-headless --with numpy python hardware/tools/logo_to_footprint.py \
        hardware/earshot-revA/logo.png hardware/earshot-revA/lib/earshot.pretty/Earshot_Logo_10mm.kicad_mod --height 10

The footprint is drawn on F.SilkS; place it on B.Cu and KiCad mirrors it so it reads correctly from the back.
Shapes with holes are not supported (KiCad fp_poly has no holes); the script stops if it finds one.
Run the KiCad MCP `upgrade_footprint_lib` on the library afterwards so KiCad rewrites the file in its own format.
"""
import argparse
import uuid

import cv2
import numpy as np


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("png")
    ap.add_argument("out")
    ap.add_argument("--height", type=float, default=10.0, help="logo height in mm")
    ap.add_argument("--layer", default="F.SilkS")
    ap.add_argument("--tolerance", type=float, default=0.02, help="max deviation from the traced outline, mm")
    args = ap.parse_args()

    img = cv2.imread(args.png, cv2.IMREAD_GRAYSCALE)
    if img is None:
        raise SystemExit(f"cannot read {args.png}")
    _, ink = cv2.threshold(img, 128, 255, cv2.THRESH_BINARY_INV)
    ink = cv2.morphologyEx(ink, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))  # drop specks

    contours, hierarchy = cv2.findContours(ink, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_NONE)
    if hierarchy is None:
        raise SystemExit("no ink found")
    outer = [c for c, h in zip(contours, hierarchy[0]) if h[3] == -1 and cv2.contourArea(c) > 50]
    holes = [c for c, h in zip(contours, hierarchy[0]) if h[3] != -1 and cv2.contourArea(c) > 50]
    if holes:
        raise SystemExit(f"{len(holes)} shape(s) with holes; not supported")

    x, y, w, h = cv2.boundingRect(np.vstack(outer))
    scale = args.height / h  # mm per pixel
    cx, cy = x + w / 2, y + h / 2
    eps_px = args.tolerance / scale

    name = args.out.rsplit("/", 1)[-1].removesuffix(".kicad_mod")
    lines = [
        f'(footprint "{name}" (version 20221018) (generator logo_to_footprint)',
        f'  (layer "F.Cu")',
        f'  (descr "Earshot logo, {args.height:g} mm tall, traced from {args.png.rsplit("/", 1)[-1]}")',
        '  (attr board_only exclude_from_pos_files exclude_from_bom)',
        f'  (fp_text reference "LOGO**" (at 0 0) (layer "F.SilkS") hide (effects (font (size 1 1) (thickness 0.15))) (tstamp {uuid.uuid4()}))',
        f'  (fp_text value "{name}" (at 0 0) (layer "F.Fab") hide (effects (font (size 1 1) (thickness 0.15))) (tstamp {uuid.uuid4()}))',
    ]
    for c in outer:
        approx = cv2.approxPolyDP(c, eps_px, True).reshape(-1, 2)
        pts = " ".join(f"(xy {(px - cx) * scale:.4f} {(py - cy) * scale:.4f})" for px, py in approx)
        lines.append(f'  (fp_poly (pts {pts}) (stroke (width 0) (type solid)) (fill solid) (layer "{args.layer}") (tstamp {uuid.uuid4()}))')
    lines.append(")")
    with open(args.out, "w") as f:
        f.write("\n".join(lines) + "\n")

    # Thinnest stroke, for the fab's minimum silkscreen width check.
    dist = cv2.distanceTransform(ink, cv2.DIST_L2, 5)
    skeleton_widths = 2 * dist[dist > 0]
    print(f"{name}: {len(outer)} polygons, {w * scale:.2f} x {h * scale:.2f} mm, "
          f"typical stroke {np.percentile(skeleton_widths, 99) * scale:.2f} mm")


if __name__ == "__main__":
    main()
