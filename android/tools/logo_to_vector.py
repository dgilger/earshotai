"""Trace the black-on-white logo PNG into Android VectorDrawable path data."""
import sys
import cv2
import numpy as np

png, out_prefix = sys.argv[1], sys.argv[2]
img = cv2.imread(png, cv2.IMREAD_GRAYSCALE)
_, ink = cv2.threshold(img, 128, 255, cv2.THRESH_BINARY_INV)
ink = cv2.morphologyEx(ink, cv2.MORPH_OPEN, np.ones((3, 3), np.uint8))
contours, hier = cv2.findContours(ink, cv2.RETR_CCOMP, cv2.CHAIN_APPROX_NONE)
contours = [c for c in contours if cv2.contourArea(c) > 50]
pts = np.vstack(contours).reshape(-1, 2)
x0, y0 = pts.min(0); x1, y1 = pts.max(0)
print("bbox", x0, y0, x1, y1, "contours", len(contours), file=sys.stderr)

def path(viewport, box):
    """Fit the logo's bbox into a centred square of side `box` inside `viewport`."""
    w, h = x1 - x0, y1 - y0
    s = box / max(w, h)
    ox = (viewport - w * s) / 2 - x0 * s
    oy = (viewport - h * s) / 2 - y0 * s
    d = []
    for c in contours:
        c = cv2.approxPolyDP(c, 1.2, True).reshape(-1, 2)
        p = ["M%.2f,%.2f" % (c[0][0] * s + ox, c[0][1] * s + oy)]
        p += ["L%.2f,%.2f" % (x * s + ox, y * s + oy) for x, y in c[1:]]
        d.append(" ".join(p) + " Z")
    return " ".join(d)

def vector(size_dp, viewport, box, color):
    return f'''<?xml version="1.0" encoding="utf-8"?>
<!-- Generated from hardware/earshot-revA/logo.png by android/tools/logo_to_vector.py -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{size_dp}dp" android:height="{size_dp}dp"
    android:viewportWidth="{viewport}" android:viewportHeight="{viewport}">
    <path android:fillColor="{color}" android:fillType="evenOdd"
        android:pathData="{path(viewport, box)}" />
</vector>
'''

# Adaptive icon foreground: 108 dp canvas, logo inside the 66 dp safe zone.
open(out_prefix + "ic_launcher_foreground.xml", "w").write(vector(108, 108, 58, "#FF000000"))
# Status bar icon: 24 dp, white (Android uses only the alpha).
open(out_prefix + "ic_notification.xml", "w").write(vector(24, 24, 22, "#FFFFFFFF"))
# In-app header logo; tinted to the text colour at runtime.
open(out_prefix + "ic_logo.xml", "w").write(vector(48, 48, 48, "#FF000000"))
