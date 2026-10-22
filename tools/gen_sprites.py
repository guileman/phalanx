"""Genera los sprite sheets de explosión y humo para Phalanx (procedural, sin assets externos)."""
import sys
import numpy as np
from PIL import Image

OUT = sys.argv[1]
rng = np.random.default_rng(7)

def value_noise(size, cells, seed):
    g = np.random.default_rng(seed).random((cells + 1, cells + 1))
    x = np.linspace(0, cells, size, endpoint=False)
    xi = x.astype(int); xf = x - xi
    xf = xf * xf * (3 - 2 * xf)
    X0, Y0 = np.meshgrid(xi, xi); FX, FY = np.meshgrid(xf, xf)
    a = g[Y0, X0]; b = g[Y0, X0 + 1]; c = g[Y0 + 1, X0]; d = g[Y0 + 1, X0 + 1]
    return (a * (1 - FX) + b * FX) * (1 - FY) + (c * (1 - FX) + d * FX) * FY

def fbm(size, seed, base=4, octaves=5):
    out = np.zeros((size, size)); amp = 0.5; tot = 0
    for o in range(octaves):
        out += amp * value_noise(size, base * 2 ** o, seed + o * 31); tot += amp; amp *= 0.5
    return out / tot

def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)

def ramp(h):
    # heat 0..1 -> rgb: humo oscuro, rojo, naranja, amarillo, blanco
    stops = np.array([0.0, 0.18, 0.38, 0.6, 0.82, 1.0])
    cols = np.array([[52, 48, 46], [95, 45, 25], [205, 80, 18], [255, 150, 35], [255, 215, 110], [255, 250, 225]], float)
    r = np.interp(h, stops, cols[:, 0]); g = np.interp(h, stops, cols[:, 1]); b = np.interp(h, stops, cols[:, 2])
    return np.stack([r, g, b], -1)

S = 128
N = 16
yy, xx = np.mgrid[0:S, 0:S]
u = (xx + 0.5) / S * 2 - 1; v = (yy + 0.5) / S * 2 - 1
ang = np.arctan2(v, u); rr = np.sqrt(u * u + v * v)

# ---------------- explosión 4x4 ----------------
sheet = np.zeros((S * 4, S * 4, 4), np.uint8)
n_big = fbm(S, 100, 3, 5)
for k in range(N):
    t = k / (N - 1)
    n = 0.6 * fbm(S, 200 + k * 3, 4, 5) + 0.4 * n_big
    R = 0.34 + 0.56 * (1 - (1 - t) ** 2.2)
    # lóbulos irregulares que crecen con el tiempo
    lobes = 0.10 * np.sin(5 * ang + 1.3) + 0.07 * np.sin(9 * ang + 4.0)
    d = rr / R - (0.55 + 0.35 * t) * (n - 0.5) - lobes * t * smooth(0.0, 0.6, rr / R)
    dens = smooth(1.02, 0.55, d)
    core = smooth(0.95, 0.0, d)
    heat = np.clip((1.25 - 1.55 * t) * core + 0.55 * (n - 0.45) * (1 - t) + (0.6 if k == 0 else 0) * core, 0, 1)
    rgb = ramp(heat)
    # luz desde arriba sobre el humo
    shade = 1 + 0.22 * (-v) * (1 - heat)
    rgb = np.clip(rgb * shade[..., None], 0, 255)
    alpha = dens * (0.7 + 0.3 * np.clip(n * 1.6, 0, 1))
    alpha *= 1 - smooth(0.62, 1.0, t) * 0.9
    # chispas / escombros en los primeros cuadros
    if t < 0.45:
        sp = (value_noise(S, 40, 900 + k) > 0.93) & (rr < R * 1.35) & (rr > R * 0.6)
        rgb[sp] = [255, 220, 140]; alpha = np.where(sp, np.maximum(alpha, 0.9 * (1 - t / 0.45)), alpha)
    img = np.dstack([rgb, alpha[..., None] * 255]).astype(np.uint8)
    gy, gx = divmod(k, 4)
    sheet[gy * S:(gy + 1) * S, gx * S:(gx + 1) * S] = img
Image.fromarray(sheet, 'RGBA').save(f"{OUT}/explosion_sheet.png", optimize=True)

# ---------------- humo 2x2 ----------------
sm = np.zeros((S * 2, S * 2, 4), np.uint8)
for k in range(4):
    n = fbm(S, 500 + k * 17, 3, 5)
    d = rr / 0.78 - 0.6 * (n - 0.5)
    dens = smooth(1.0, 0.35, d) * (0.7 + 0.3 * n)
    g = 60 + 65 * n + 28 * (-v)
    rgb = np.stack([g, g * 0.98, g * 0.95], -1)
    img = np.dstack([np.clip(rgb, 0, 255), dens[..., None] * 245]).astype(np.uint8)
    gy, gx = divmod(k, 2)
    sm[gy * S:(gy + 1) * S, gx * S:(gx + 1) * S] = img
Image.fromarray(sm, 'RGBA').save(f"{OUT}/smoke_sheet.png", optimize=True)

# preview sobre cielo
bg = Image.new('RGBA', (S * 6, S * 4), (120, 165, 210, 255))
bg.alpha_composite(Image.fromarray(sheet, 'RGBA'), (0, 0))
bg.alpha_composite(Image.fromarray(sm, 'RGBA'), (S * 4, 0))
bg.convert('RGB').save(sys.argv[2])
