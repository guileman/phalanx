"""Ilustraciones low-poly de los sistemas de armas para el menú (render propio, sin imágenes externas)."""
import math, sys
import numpy as np
from PIL import Image, ImageDraw, ImageFilter

OUT = sys.argv[1]
W, H = 720, 440
SS = 2  # supersampling


class Scene:
    def __init__(self):
        self.polys = []  # (pts Nx3, rgb)

    def poly(self, pts, col):
        self.polys.append((np.array(pts, float), col))

    def box(self, c, s, col, rz=0.0, ry=0.0):
        cx, cy, cz = c; sx, sy, sz = [v / 2 for v in s]
        v = np.array([[x, y, z] for x in (-sx, sx) for y in (-sy, sy) for z in (-sz, sz)])
        v = v @ rot(rz, ry).T + np.array(c)
        faces = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
        for f in faces:
            self.poly(v[list(f)], col)

    def wedge(self, pts8, col):
        """Caja deformada: 8 vértices en el orden de box()."""
        v = np.array(pts8, float)
        faces = [(0, 1, 3, 2), (4, 6, 7, 5), (0, 4, 5, 1), (2, 3, 7, 6), (0, 2, 6, 4), (1, 5, 7, 3)]
        for f in faces:
            self.poly(v[list(f)], col)

    def cyl(self, p0, p1, r0, col, n=14, r1=None, caps=True):
        r1 = r0 if r1 is None else r1
        p0 = np.array(p0, float); p1 = np.array(p1, float)
        a = p1 - p0; a /= np.linalg.norm(a)
        t = np.array([0, 0, 1.0]) if abs(a[2]) < 0.9 else np.array([1.0, 0, 0])
        u = np.cross(a, t); u /= np.linalg.norm(u); w = np.cross(a, u)
        ring = lambda p, r: [p + r * (math.cos(2 * math.pi * k / n) * u + math.sin(2 * math.pi * k / n) * w) for k in range(n)]
        A = ring(p0, r0); B = ring(p1, r1)
        for k in range(n):
            self.poly([A[k], A[(k + 1) % n], B[(k + 1) % n], B[k]], col)
        if caps:
            self.poly(A, col); self.poly(B, col)

    def dome(self, c, r, col, n=16, m=6, squash=1.0):
        c = np.array(c, float)
        for i in range(m):
            t0 = math.pi / 2 * i / m; t1 = math.pi / 2 * (i + 1) / m
            for k in range(n):
                p0 = 2 * math.pi * k / n; p1 = 2 * math.pi * (k + 1) / n
                q = lambda t, p: c + r * np.array([math.cos(t) * math.cos(p), math.cos(t) * math.sin(p), squash * math.sin(t)])
                self.poly([q(t0, p0), q(t0, p1), q(t1, p1), q(t1, p0)], col)

    def disc(self, c, r, normal, col, n=20):
        c = np.array(c, float); a = np.array(normal, float); a /= np.linalg.norm(a)
        t = np.array([0, 0, 1.0]) if abs(a[2]) < 0.9 else np.array([1.0, 0, 0])
        u = np.cross(a, t); u /= np.linalg.norm(u); w = np.cross(a, u)
        self.poly([c + r * (math.cos(2 * math.pi * k / n) * u + math.sin(2 * math.pi * k / n) * w) for k in range(n)], col)


def rot(rz, ry=0.0):
    cz, sz = math.cos(rz), math.sin(rz); cy, sy = math.cos(ry), math.sin(ry)
    Rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    Ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    return Rz @ Ry


def frame(scene, az_deg, el_deg, fov_deg, margin=0.6):
    pts = np.vstack([p for p, _ in scene.polys])
    lo, hi = pts.min(0), pts.max(0)
    c = (lo + hi) / 2
    R = np.linalg.norm(pts - c, axis=1).max()
    vfov = 2 * math.atan(math.tan(math.radians(fov_deg) / 2) * H / W)
    dist = R / math.sin(vfov / 2) * margin
    a = math.radians(az_deg); e = math.radians(el_deg)
    eye = c + dist * np.array([math.cos(e) * math.cos(a), math.cos(e) * math.sin(a), math.sin(e)])
    return eye, c


def render(scene, eye, target, fov_deg, bg, name, ground=None, glow=None):
    if eye is None:
        az, el, mg = (list(target) + [0.6])[:3]
        eye, target = frame(scene, az, el, fov_deg, mg)
    w, h = W * SS, H * SS
    img = Image.new('RGB', (w, h))
    # fondo degradado
    top, bot = np.array(bg[0], float), np.array(bg[1], float)
    grad = np.linspace(0, 1, h)[:, None] * (bot - top) + top
    img = Image.fromarray(np.repeat(grad[:, None, :], w, axis=1).astype(np.uint8))
    d = ImageDraw.Draw(img)
    eye = np.array(eye, float); target = np.array(target, float)
    f = target - eye; f /= np.linalg.norm(f)
    r = np.cross(f, [0, 0, 1.0]); r /= np.linalg.norm(r)
    u = np.cross(r, f)
    foc = (w / 2) / math.tan(math.radians(fov_deg) / 2)
    sun = np.array([-0.45, -0.55, 0.75]); sun /= np.linalg.norm(sun)

    def proj(p):
        q = p - eye
        x, y, z = q @ r, q @ u, q @ f
        return (w / 2 + foc * x / z, h / 2 - foc * y / z), z

    if ground is not None:
        # suelo con grilla tipo HUD
        gz, gcol, lcol = ground
        for gx in np.arange(-40, 41, 4.0):
            a, za = proj(np.array([gx, -40, gz])); b, zb = proj(np.array([gx, 40, gz]))
            if za > 0.5 and zb > 0.5:
                d.line([a, b], fill=lcol, width=SS)
        for gy in np.arange(-40, 41, 4.0):
            a, za = proj(np.array([-40, gy, gz])); b, zb = proj(np.array([40, gy, gz]))
            if za > 0.5 and zb > 0.5:
                d.line([a, b], fill=lcol, width=SS)

    items = []
    for pts, col in scene.polys:
        P = []; ok = True; depth = 0
        for p in pts:
            s, z = proj(p)
            if z < 0.1: ok = False; break
            P.append(s); depth += np.linalg.norm(p - eye)
        if not ok: continue
        m = len(pts)
        i2 = min(2, m - 1); i3 = 3 if m >= 4 else 0
        n = np.cross(pts[i2] - pts[0], pts[i3] - pts[1]); nl = np.linalg.norm(n)
        if nl < 1e-9: continue
        n /= nl
        # orientar la normal hacia la cámara
        if n @ (eye - pts.mean(0)) < 0: n = -n
        lam = 0.38 + 0.62 * max(0.0, n @ sun) + 0.12 * max(0.0, n[2])
        c = tuple(int(min(255, v * lam)) for v in col)
        items.append((depth / m, P, c))
    items.sort(key=lambda t: -t[0])
    for _, P, c in items:
        d.polygon(P, fill=c, outline=tuple(max(0, v - 28) for v in c))
    if glow:
        for (gp, gr, gc) in glow:
            s, z = proj(np.array(gp, float))
            rr = float(gr * foc / z); s = (float(s[0]), float(s[1]))
            g = Image.new('RGBA', img.size, (0, 0, 0, 0)); gd = ImageDraw.Draw(g)
            gd.ellipse([s[0] - rr, s[1] - rr, s[0] + rr, s[1] + rr], fill=gc)
            g = g.filter(ImageFilter.GaussianBlur(rr * 0.6))
            img = Image.alpha_composite(img.convert('RGBA'), g).convert('RGB')
    img = img.resize((W, H), Image.LANCZOS)
    # viñeta
    vg = Image.new('L', (W, H), 0); vd = ImageDraw.Draw(vg)
    vd.ellipse([-W * 0.25, -H * 0.35, W * 1.25, H * 1.35], fill=255)
    vg = vg.filter(ImageFilter.GaussianBlur(60))
    dark = Image.new('RGB', (W, H), (6, 10, 8))
    img = Image.composite(img, dark, vg)
    img.save(f"{OUT}/{name}.png", optimize=True)


# ===================== PHALANX =====================
s = Scene()
navy = (178, 184, 190); white = (226, 229, 231); dark = (60, 64, 68); deck = (95, 102, 108)
s.cyl([0, 0, 0], [0, 0, 0.9], 1.25, deck, n=20)                 # pedestal/base
s.cyl([0, 0, 0.9], [0, 0, 1.25], 1.05, navy, n=20)
# horquilla del montaje
s.box([0, -0.95, 1.9], [1.1, 0.35, 1.5], navy)
s.box([0, 0.95, 1.9], [1.1, 0.35, 1.5], navy)
s.box([0, 0, 1.3], [1.2, 2.2, 0.3], navy)
el = math.radians(18)
def gunpt(x, z=2.2):  # punto a lo largo del eje del cañón elevado
    return [x * math.cos(el), 0, z + x * math.sin(el)]
# cuerpo del arma + tambor
s.cyl([-1.3 * math.cos(el), 0, 2.2 - 1.3 * math.sin(el)], gunpt(0.6), 0.62, navy, n=18)
# radomo (el "R2-D2")
s.cyl([-0.85, 0, 2.6], [-0.85, 0, 3.75], 0.85, white, n=22, caps=False)
s.dome([-0.85, 0, 3.75], 0.85, white, n=22, m=7, squash=1.05)
s.poly([[-0.85 + 0.85 * math.cos(a), 0.85 * math.sin(a), 2.6] for a in np.linspace(0, 2 * math.pi, 22, endpoint=False)], white)
# racimo de 6 cañones con funda
s.cyl(gunpt(0.5), gunpt(1.4), 0.36, white, n=16)              # funda de los cañones
for k in range(6):
    a = 2 * math.pi * k / 6
    off = np.array([0, 0.16 * math.cos(a), 0.16 * math.sin(a)])
    p0 = np.array(gunpt(1.4)) + off; p1 = np.array(gunpt(4.2)) + off
    s.cyl(p0, p1, 0.045, dark, n=6)
s.cyl(gunpt(3.9), gunpt(4.05), 0.25, dark, n=12)               # abrazadera
# antena de búsqueda (caja en la parte trasera del domo)
s.box([-0.85, 0, 4.72], [0.5, 0.9, 0.16], white)
render(s, eye=None, target=(-38, 12, 0.8), fov_deg=30,
       bg=[(40, 62, 82), (12, 22, 30)], name="weapon_phalanx",
       ground=(0.0, None, (40, 90, 60)))

# ===================== GEPARD =====================
s = Scene()
camo = (98, 108, 72); camo2 = (78, 86, 58); trackc = (40, 40, 38); gun = (55, 58, 52)
# casco con frente inclinado
L, Wd = 7.7, 3.25
s.wedge([[-L / 2, -Wd / 2, 0.45], [-L / 2, -Wd / 2, 1.75], [-L / 2, Wd / 2, 0.45], [-L / 2, Wd / 2, 1.75],
         [L / 2, -Wd / 2 + 0.3, 0.75], [L / 2 - 1.4, -Wd / 2, 1.75], [L / 2, Wd / 2 - 0.3, 0.75], [L / 2 - 1.4, Wd / 2, 1.75]], camo)
# orugas y ruedas
for side in (-1, 1):
    y = side * (Wd / 2 + 0.25)
    s.box([0, y, 0.55], [L - 0.2, 0.55, 1.0], trackc)
    for x in np.linspace(-2.9, 2.9, 7):
        s.disc([x, y + side * 0.29, 0.5], 0.36, [0, side, 0], (70, 72, 66), n=14)
    s.box([0, side * (Wd / 2 + 0.3), 1.1], [L - 0.4, 0.62, 0.08], camo2)   # guardabarros
# torreta
s.wedge([[-2.2, -1.45, 1.75], [-2.2, -1.45, 2.95], [-2.2, 1.45, 1.75], [-2.2, 1.45, 2.95],
         [1.3, -1.45, 1.75], [0.9, -1.3, 2.95], [1.3, 1.45, 1.75], [0.9, 1.3, 2.95]], camo2)
# cañones 35 mm a ambos lados
for side in (-1, 1):
    y = side * 1.75
    s.box([0.0, y, 2.35], [2.4, 0.5, 0.7], camo)                 # cuna del cañón
    s.cyl([1.2, y, 2.4], [5.6, y, 2.95], 0.07, gun, n=8)          # caño
    s.cyl([5.4, y, 2.93], [5.75, y, 2.97], 0.13, gun, n=10)       # freno de boca
# radar de búsqueda (plato trasero)
s.cyl([-1.6, 0, 2.95], [-1.6, 0, 3.6], 0.08, gun, n=8)
s.wedge([[-1.85, -1.15, 3.55], [-1.75, -1.15, 4.05], [-1.85, 1.15, 3.55], [-1.75, 1.15, 4.05],
         [-1.55, -1.15, 3.55], [-1.45, -1.15, 4.05], [-1.55, 1.15, 3.55], [-1.45, 1.15, 4.05]], (150, 156, 140))
# radar de seguimiento (plato frontal)
s.disc([1.38, 0, 2.45], 0.5, [1, 0, 0.1], (120, 126, 110))
s.cyl([1.15, 0, 2.45], [1.35, 0, 2.45], 0.5, camo2, n=16, caps=False)
render(s, eye=None, target=(-40, 20), fov_deg=30,
       bg=[(70, 92, 104), (22, 30, 26)], name="weapon_gepard",
       ground=(0.0, None, (60, 110, 70)))

# ===================== JAVELIN =====================
s = Scene()
olive = (92, 100, 70); olive2 = (70, 76, 54); black = (35, 36, 34); tan = (150, 140, 110)
# tubo de lanzamiento
s.cyl([-0.55, 0, 1.0], [0.65, 0, 1.0], 0.085, olive, n=18)
s.cyl([-0.62, 0, 1.0], [-0.55, 0, 1.0], 0.11, black, n=18)     # tapa trasera
s.cyl([0.65, 0, 1.0], [0.72, 0, 1.0], 0.105, black, n=18)      # tapa delantera
s.cyl([-0.35, 0, 1.0], [-0.25, 0, 1.0], 0.1, olive2, n=18)       # anillos
s.cyl([0.2, 0, 1.0], [0.3, 0, 1.0], 0.1, olive2, n=18)
# batería/BCU
s.cyl([-0.2, 0, 0.9], [-0.05, 0, 0.9], 0.06, tan, n=10)
# CLU (unidad de comando) al costado izquierdo
s.box([-0.05, -0.2, 1.02], [0.36, 0.2, 0.24], olive2)
s.box([-0.2, -0.2, 1.02], [0.06, 0.22, 0.26], black)               # frente de ópticas
s.cyl([0.12, -0.2, 1.05], [0.24, -0.2, 1.05], 0.045, black, n=12) # ocular
s.cyl([-0.24, -0.23, 1.07], [-0.27, -0.23, 1.07], 0.05, (20, 40, 60), n=12)  # lente
for yy in (-0.33, -0.07):
    s.box([0.03, yy, 0.95], [0.05, 0.03, 0.14], black)                # empuñaduras
s.box([0.0, -0.06, 1.0], [0.2, 0.06, 0.06], olive2)
render(s, eye=None, target=(-118, 22, 0.78), fov_deg=30,
       bg=[(84, 96, 78), (24, 30, 24)], name="weapon_javelin",
       glow=[([-0.27, -0.23, 1.07], 0.02, (90, 160, 255, 160))])
print("ok")
