package io.github.adhamura.gitphindroid.qr;

import java.nio.charset.Charset;

/** QR Code encoder (ISO/IEC 18004): byte mode, error correction level M, versions 1-10. */
public final class QrCode {
    private QrCode() {}

    // Level M, versions 1..10: {EC codewords per block, blocks in group 1, data per block in
    // group 1, blocks in group 2, data per block in group 2}.
    private static final int[][] M_TABLE = {
        {10, 1, 16, 0, 0}, {16, 1, 28, 0, 0}, {26, 1, 44, 0, 0}, {18, 2, 32, 0, 0},
        {24, 2, 43, 0, 0}, {16, 4, 27, 0, 0}, {18, 4, 31, 0, 0}, {22, 2, 38, 2, 39},
        {22, 3, 36, 2, 37}, {26, 4, 43, 1, 44},
    };
    private static final int[][] ALIGN = {
        {}, {6, 18}, {6, 22}, {6, 26}, {6, 30}, {6, 34}, {6, 22, 38}, {6, 24, 42},
        {6, 26, 46}, {6, 28, 50},
    };

    private static int dataCapacity(int ver) {
        int[] t = M_TABLE[ver - 1];
        return t[1] * t[2] + t[3] * t[4];
    }

    /** Encodes text (UTF-8, byte mode, level M). true = dark module. */
    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(Charset.forName("UTF-8"));
        int ver = 0;
        for (int v = 1; v <= 10; v++) {
            int cc = v < 10 ? 8 : 16;
            int bits = 4 + cc + data.length * 8;
            if (data.length < (1 << cc) && bits <= dataCapacity(v) * 8) { ver = v; break; }
        }
        if (ver == 0) throw new IllegalArgumentException("Text too long for a QR code");

        // bit stream
        int cap = dataCapacity(ver);
        byte[] cw = new byte[cap];
        int[] pos = {0};
        put(cw, pos, 4, 4); // byte mode
        put(cw, pos, data.length, ver < 10 ? 8 : 16);
        for (byte b : data) put(cw, pos, b & 0xFF, 8);
        // terminator and byte padding are implicit: unused bits are zero
        int used = (pos[0] + 7) / 8;
        for (int i = used, pad = 0; i < cap; i++, pad++) cw[i] = (byte) (pad % 2 == 0 ? 0xEC : 0x11);

        byte[] all = interleave(cw, ver);
        int size = ver * 4 + 17;
        boolean[][] mod = new boolean[size][size];
        boolean[][] fn = new boolean[size][size];
        drawFunctionPatterns(mod, fn, ver);
        drawCodewords(mod, fn, all);

        int best = 0;
        long bestPen = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(mod, fn, m);
            drawFormat(mod, fn, m);
            long pen = penalty(mod);
            if (pen < bestPen) { bestPen = pen; best = m; }
            applyMask(mod, fn, m); // undo
        }
        applyMask(mod, fn, best);
        drawFormat(mod, fn, best);
        return mod;
    }

    private static void put(byte[] buf, int[] pos, int val, int len) {
        for (int i = len - 1; i >= 0; i--) {
            if (((val >>> i) & 1) != 0) buf[pos[0] >> 3] |= (byte) (0x80 >> (pos[0] & 7));
            pos[0]++;
        }
    }

    // ---------------------------------------------------------------- Reed-Solomon

    private static int gfMul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static byte[] rsDivisor(int degree) {
        byte[] r = new byte[degree];
        r[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                r[j] = (byte) gfMul(r[j] & 0xFF, root);
                if (j + 1 < degree) r[j] ^= r[j + 1];
            }
            root = gfMul(root, 0x02);
        }
        return r;
    }

    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) gfMul(div[i] & 0xFF, f);
        }
        return r;
    }

    private static byte[] interleave(byte[] data, int ver) {
        int[] t = M_TABLE[ver - 1];
        int ecLen = t[0], nBlocks = t[1] + t[3];
        byte[][] dBlk = new byte[nBlocks][];
        byte[][] eBlk = new byte[nBlocks][];
        byte[] div = rsDivisor(ecLen);
        int off = 0, maxData = 0;
        for (int i = 0; i < nBlocks; i++) {
            int len = i < t[1] ? t[2] : t[4];
            dBlk[i] = new byte[len];
            System.arraycopy(data, off, dBlk[i], 0, len);
            off += len;
            eBlk[i] = rsRemainder(dBlk[i], div);
            maxData = Math.max(maxData, len);
        }
        byte[] out = new byte[off + nBlocks * ecLen];
        int o = 0;
        for (int i = 0; i < maxData; i++)
            for (int b = 0; b < nBlocks; b++)
                if (i < dBlk[b].length) out[o++] = dBlk[b][i];
        for (int i = 0; i < ecLen; i++)
            for (int b = 0; b < nBlocks; b++) out[o++] = eBlk[b][i];
        return out;
    }

    // ---------------------------------------------------------------- drawing

    private static void setFn(boolean[][] mod, boolean[][] fn, int x, int y, boolean dark) {
        mod[y][x] = dark;
        fn[y][x] = true;
    }

    private static boolean bit(int v, int i) { return ((v >>> i) & 1) != 0; }

    private static void drawFunctionPatterns(boolean[][] mod, boolean[][] fn, int ver) {
        int size = mod.length;
        for (int i = 0; i < size; i++) {
            setFn(mod, fn, 6, i, i % 2 == 0);
            setFn(mod, fn, i, 6, i % 2 == 0);
        }
        drawFinder(mod, fn, 3, 3);
        drawFinder(mod, fn, size - 4, 3);
        drawFinder(mod, fn, 3, size - 4);
        int[] ap = ALIGN[ver - 1];
        int n = ap.length;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                for (int dy = -2; dy <= 2; dy++)
                    for (int dx = -2; dx <= 2; dx++)
                        setFn(mod, fn, ap[i] + dx, ap[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
            }
        }
        drawFormat(mod, fn, 0); // reserve the area
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = bit(bits, i);
                int a = size - 11 + i % 3, c = i / 3;
                setFn(mod, fn, a, c, b);
                setFn(mod, fn, c, a, b);
            }
        }
    }

    private static void drawFinder(boolean[][] mod, boolean[][] fn, int cx, int cy) {
        int size = mod.length;
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int dist = Math.max(Math.abs(dx), Math.abs(dy));
                int x = cx + dx, y = cy + dy;
                if (x >= 0 && x < size && y >= 0 && y < size) setFn(mod, fn, x, y, dist != 2 && dist != 4);
            }
        }
    }

    private static void drawFormat(boolean[][] mod, boolean[][] fn, int mask) {
        int size = mod.length;
        int data = (0 << 3) | mask; // level M = 00
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) setFn(mod, fn, 8, i, bit(bits, i));
        setFn(mod, fn, 8, 7, bit(bits, 6));
        setFn(mod, fn, 8, 8, bit(bits, 7));
        setFn(mod, fn, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) setFn(mod, fn, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) setFn(mod, fn, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) setFn(mod, fn, 8, size - 15 + i, bit(bits, i));
        setFn(mod, fn, 8, size - 8, true); // dark module
    }

    private static void drawCodewords(boolean[][] mod, boolean[][] fn, byte[] data) {
        int size = mod.length, i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean up = ((right + 1) & 2) == 0;
                    int y = up ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < data.length * 8) {
                        mod[y][x] = bit(data[i >>> 3], 7 - (i & 7));
                        i++;
                    }
                }
            }
        }
    }

    private static void applyMask(boolean[][] mod, boolean[][] fn, int m) {
        int size = mod.length;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean inv;
                switch (m) {
                    case 0: inv = (x + y) % 2 == 0; break;
                    case 1: inv = y % 2 == 0; break;
                    case 2: inv = x % 3 == 0; break;
                    case 3: inv = (x + y) % 3 == 0; break;
                    case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                    case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                    case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                }
                if (inv && !fn[y][x]) mod[y][x] = !mod[y][x];
            }
        }
    }

    // ---------------------------------------------------------------- penalty

    private static long penalty(boolean[][] m) {
        int size = m.length;
        long p = 0;
        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) if (m[y][x]) dark++;
        }
        for (int a = 0; a < size; a++) {
            // N1 runs in row a and column a
            for (int dir = 0; dir < 2; dir++) {
                int run = 1;
                for (int b = 1; b < size; b++) {
                    boolean cur = dir == 0 ? m[a][b] : m[b][a];
                    boolean prev = dir == 0 ? m[a][b - 1] : m[b - 1][a];
                    if (cur == prev) {
                        run++;
                        if (run == 5) p += 3; else if (run > 5) p++;
                    } else {
                        run = 1;
                    }
                }
                // N3: 1:1:3:1:1 finder-like pattern with 4 light modules on a side
                for (int b = 0; b + 11 <= size; b++) {
                    boolean p1 = true, p2 = true;
                    int[] pa = {1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0};
                    for (int k = 0; k < 11; k++) {
                        boolean v = dir == 0 ? m[a][b + k] : m[b + k][a];
                        if (v != (pa[k] == 1)) p1 = false;
                        if (v != (pa[10 - k] == 1)) p2 = false;
                    }
                    if (p1) p += 40;
                    if (p2) p += 40;
                }
            }
        }
        // N2 2x2 blocks
        for (int y = 0; y + 1 < size; y++)
            for (int x = 0; x + 1 < size; x++)
                if (m[y][x] == m[y][x + 1] && m[y][x] == m[y + 1][x] && m[y][x] == m[y + 1][x + 1]) p += 3;
        // N4 balance
        int total = size * size;
        int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        p += k * 10L;
        return p;
    }
}
