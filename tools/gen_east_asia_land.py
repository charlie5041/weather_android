"""產生 app/src/main/assets/east_asia_land.json（颱風路徑圖用的陸地輪廓）。

資料：Natural Earth 1:50m 陸地（公有領域），取自 npm 套件 world-atlas@2：
    npm pack world-atlas@2 && tar xzf world-atlas-*.tgz
    python3 tools/gen_east_asia_land.py package/land-50m.json
輸出：[[lon, lat, lon, lat, ...], ...] 每個陸地多邊形一組，已裁切到東亞範圍並簡化。
"""
import json
import sys
from pathlib import Path

BBOX = (95.0, -5.0, 165.0, 50.0)  # west, south, east, north
TOLERANCE = 0.04  # 簡化容許誤差（度）
OUT = Path(__file__).resolve().parent.parent / "app/src/main/assets/east_asia_land.json"


def decode_arcs(topo):
    sx, sy = topo["transform"]["scale"]
    tx, ty = topo["transform"]["translate"]
    arcs = []
    for arc in topo["arcs"]:
        x = y = 0
        pts = []
        for dx, dy in arc:
            x += dx
            y += dy
            pts.append((x * sx + tx, y * sy + ty))
        arcs.append(pts)
    return arcs


def ring_points(ring, arcs):
    pts = []
    for index in ring:
        arc = arcs[index] if index >= 0 else list(reversed(arcs[~index]))
        pts.extend(arc if not pts else arc[1:])
    return pts


def clip(poly, west, south, east, north):
    """Sutherland–Hodgman 矩形裁切"""
    def clip_edge(points, inside, intersect):
        out = []
        for i, cur in enumerate(points):
            prev = points[i - 1]
            if inside(cur):
                if not inside(prev):
                    out.append(intersect(prev, cur))
                out.append(cur)
            elif inside(prev):
                out.append(intersect(prev, cur))
        return out

    def ix(x):
        return lambda a, b: (x, a[1] + (b[1] - a[1]) * (x - a[0]) / (b[0] - a[0]))

    def iy(y):
        return lambda a, b: (a[0] + (b[0] - a[0]) * (y - a[1]) / (b[1] - a[1]), y)

    for inside, intersect in (
        (lambda p: p[0] >= west, ix(west)),
        (lambda p: p[0] <= east, ix(east)),
        (lambda p: p[1] >= south, iy(south)),
        (lambda p: p[1] <= north, iy(north)),
    ):
        if not poly:
            break
        poly = clip_edge(poly, inside, intersect)
    return poly


def simplify(points, tol):
    """Douglas–Peucker"""
    if len(points) < 3:
        return points

    def dist(p, a, b):
        (x, y), (x1, y1), (x2, y2) = p, a, b
        dx, dy = x2 - x1, y2 - y1
        if dx == dy == 0:
            return ((x - x1) ** 2 + (y - y1) ** 2) ** 0.5
        t = max(0, min(1, ((x - x1) * dx + (y - y1) * dy) / (dx * dx + dy * dy)))
        return ((x - x1 - t * dx) ** 2 + (y - y1 - t * dy) ** 2) ** 0.5

    keep = [False] * len(points)
    keep[0] = keep[-1] = True
    stack = [(0, len(points) - 1)]
    while stack:
        s, e = stack.pop()
        best, idx = 0, None
        for i in range(s + 1, e):
            d = dist(points[i], points[s], points[e])
            if d > best:
                best, idx = d, i
        if idx is not None and best > tol:
            keep[idx] = True
            stack += [(s, idx), (idx, e)]
    return [p for p, k in zip(points, keep) if k]


def main(path):
    topo = json.loads(Path(path).read_text())
    arcs = decode_arcs(topo)
    out = []
    for geom in topo["objects"]["land"]["geometries"]:
        polys = geom["arcs"] if geom["type"] == "MultiPolygon" else [geom["arcs"]]
        for poly in polys:
            outer = ring_points(poly[0], arcs)
            clipped = simplify(clip(outer, *BBOX), TOLERANCE)
            if len(clipped) >= 3:
                out.append([round(v, 2) for p in clipped for v in p])
    OUT.write_text(json.dumps(out, separators=(",", ":")))
    print(f"{len(out)} polygons, {sum(len(p) for p in out) // 2} points -> {OUT} ({OUT.stat().st_size} bytes)")


if __name__ == "__main__":
    main(sys.argv[1])
