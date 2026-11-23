"""Draw 2D cross-sections of the enclosure with the board and battery (img/section-*.png).
Run after earshot_case.py; needs matplotlib."""
import os
import cadquery as cq
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
from matplotlib.path import Path
from matplotlib.patches import PathPatch
import earshot_case as ec

COLORS = {"shell": "#2a2d30", "back": "#8a8f8a", "button": "#d9822b", "pcb": "#1f6b45", "parts": "#5f8f74",
          "battery": "#c8b98a", "pipe": "#9fd3ef"}


def section(shape, axis, at):
    if axis == "x":
        plane = cq.Face.makePlane(400, 400, cq.Vector(at, 0, 0), cq.Vector(1, 0, 0))
    else:
        plane = cq.Face.makePlane(400, 400, cq.Vector(0, at, 0), cq.Vector(0, 1, 0))
    try:
        return shape.val().intersect(plane).Faces()
    except Exception:
        return []


def draw(ax, faces, color, axis, alpha=1.0):
    for f in faces:
        verts, codes = [], []
        for w in [f.outerWire()] + f.innerWires():
            n = max(8, int(w.Length() / 0.03))
            pts = [((p.y if axis == "x" else p.x), p.z) for p in w.positions([i / n for i in range(n)], mode="length")]
            if len(pts) < 3:
                continue
            verts += pts + [pts[0]]
            codes += [Path.MOVETO] + [Path.LINETO] * (len(pts) - 1) + [Path.CLOSEPOLY]
        if verts:
            ax.add_patch(PathPatch(Path(verts, codes), facecolor=color, edgecolor="black", lw=0.3, alpha=alpha))


def main():
    here = os.path.dirname(os.path.abspath(__file__))
    shell, back, button, pipe = ec.make_shell(), ec.make_back(), ec.make_button(), ec.make_lightpipe()
    pcb, parts = ec.make_board()
    bat = ec.make_battery()
    views = [
        ("usb", "x", 0.0, (-28, -12), (-13, 8), "Section at x = 0: USB-C, board pocket, ring and O-ring groove"),
        ("mic", "x", ec.MIC_X, (12, 28), (-13, 8), "Section through the right mic: gasket seat, 0.9 mm duct, membrane recess"),
        ("button", "y", ec.BTN_Y, (-20, 20), (-13, 8), "Section at the button: plunger + O-ring, crush ribs, battery cradle"),
        ("led", "y", ec.LED_Y, (0, 12), (-2, 8), "Section at the LED: printed light pipe with O-ring"),
    ]
    for name, axis, at, xl, zl, title in views:
        fig, ax = plt.subplots(figsize=(10, 10 * (zl[1] - zl[0]) / (xl[1] - xl[0]) + 0.6), dpi=160)
        draw(ax, section(bat, axis, at), COLORS["battery"], axis)
        draw(ax, section(pcb, axis, at), COLORS["pcb"], axis)
        for p in parts:
            draw(ax, section(p, axis, at), COLORS["parts"], axis)
        draw(ax, section(back, axis, at), COLORS["back"], axis)
        draw(ax, section(shell, axis, at), COLORS["shell"], axis)
        draw(ax, section(button, axis, at), COLORS["button"], axis)
        draw(ax, section(pipe, axis, at), COLORS["pipe"], axis)
        ax.set_xlim(*xl); ax.set_ylim(*zl); ax.set_aspect("equal")
        ax.set_xlabel(("y" if axis == "x" else "x") + " (mm)"); ax.set_ylabel("z (mm)")
        ax.grid(True, lw=0.2); ax.set_title(title, fontsize=10)
        fig.tight_layout(); fig.savefig(os.path.join(here, "img", f"section-{name}.png")); plt.close(fig)
        print("wrote", name)


if __name__ == "__main__":
    main()
