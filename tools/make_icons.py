# -*- coding: utf-8 -*-
"""生成 StudyBuddy 的 PWA 图标（纯标准库，无需 Pillow）。

用法：  python tools/make_icons.py
输出：  icons/icon-192.png  icons/icon-512.png  icons/favicon-64.png
"""
import os
import struct
import zlib

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "icons")

# 配色（与 index.html 的主色一致）
C1 = (0x5b, 0x6c, 0xff)   # 靛蓝
C2 = (0x8a, 0x5c, 0xff)   # 紫


def lerp(a, b, t):
    return a + (b - a) * t


def in_round_rect(u, v, x0, y0, x1, y1, r):
    """点 (u,v) 是否落在圆角矩形内。u/v 为归一化坐标 0~1。"""
    if u < x0 or u > x1 or v < y0 or v > y1:
        return False
    dx = max(x0 + r - u, u - (x1 - r), 0.0)
    dy = max(y0 + r - v, v - (y1 - r), 0.0)
    return dx * dx + dy * dy <= r * r


def sample(u, v):
    """返回该点的 RGBA。"""
    # 底板：整个方块（圆角 22%，系统会裁切）
    if not in_round_rect(u, v, 0.0, 0.0, 1.0, 1.0, 0.22):
        return (0, 0, 0, 0)
    t = min(max((u + v) / 2.0, 0.0), 1.0)          # 对角渐变
    r = int(lerp(C1[0], C2[0], t))
    g = int(lerp(C1[1], C2[1], t))
    b = int(lerp(C1[2], C2[2], t))

    # 三条"笔记线条"，最后一条短一些
    lines = [(0.30, 0.545), (0.445, 0.70), (0.59, 0.62)]
    for y0, x1 in lines:
        if in_round_rect(u, v, 0.26, y0, x1, y0 + 0.075, 0.0375):
            return (255, 255, 255, 255)
    return (r, g, b, 255)


def make_png(path, size, ss=3):
    """ss = 每边超采样倍数（抗锯齿）。"""
    rows = []
    for y in range(size):
        row = bytearray()
        row.append(0)                              # PNG 每行的 filter type
        for x in range(size):
            acc = [0, 0, 0, 0]
            for sy in range(ss):
                for sx in range(ss):
                    u = (x + (sx + 0.5) / ss) / size
                    v = (y + (sy + 0.5) / ss) / size
                    px = sample(u, v)
                    for i in range(4):
                        acc[i] += px[i]
            n = ss * ss
            row += bytes((acc[0] // n, acc[1] // n, acc[2] // n, acc[3] // n))
        rows.append(bytes(row))
    raw = b"".join(rows)

    def chunk(tag, data):
        return (struct.pack(">I", len(data)) + tag + data +
                struct.pack(">I", zlib.crc32(tag + data) & 0xffffffff))

    ihdr = struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0)
    png = (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr) +
           chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b""))
    with open(path, "wb") as f:
        f.write(png)
    return len(png)


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, size, ss in (("icon-192.png", 192, 3), ("icon-512.png", 512, 2), ("favicon-64.png", 64, 4)):
        n = make_png(os.path.join(OUT, name), size, ss)
        print("  %-16s %4dpx  %6.1f KB" % (name, size, n / 1024))
    print("图标已生成到", OUT)


if __name__ == "__main__":
    main()
