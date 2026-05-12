/*
 * Complexity Analyzer
 * Copyright (C) 2025-2026 dertex909
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.complexityanalyzer.export.cabin;

public final class LeBuf {

    private byte[] buf;
    private int pos;

    public LeBuf(int initialCapacity) {
        this.buf = new byte[Math.max(16, initialCapacity)];
        this.pos = 0;
    }

    public int position() {
        return pos;
    }

    public int size() {
        return pos;
    }

    public byte[] array() {
        return buf;
    }

    public byte[] toByteArray() {
        byte[] out = new byte[pos];
        System.arraycopy(buf, 0, out, 0, pos);
        return out;
    }

    private void ensure(int more) {
        int need = pos + more;
        if (need > buf.length) {
            int newLen = buf.length;
            while (newLen < need) newLen += (newLen >>> 1) + 16;
            byte[] grown = new byte[newLen];
            System.arraycopy(buf, 0, grown, 0, pos);
            this.buf = grown;
        }
    }

    public void u8(int v) {
        ensure(1);
        buf[pos++] = (byte) v;
    }

    public void u16(int v) {
        ensure(2);
        buf[pos] = (byte) v;
        buf[pos + 1] = (byte) (v >>> 8);
        pos += 2;
    }

    public void i32(int v) {
        ensure(4);
        buf[pos] = (byte) v;
        buf[pos + 1] = (byte) (v >>> 8);
        buf[pos + 2] = (byte) (v >>> 16);
        buf[pos + 3] = (byte) (v >>> 24);
        pos += 4;
    }

    public void i64(long v) {
        ensure(8);
        buf[pos] = (byte) v;
        buf[pos + 1] = (byte) (v >>> 8);
        buf[pos + 2] = (byte) (v >>> 16);
        buf[pos + 3] = (byte) (v >>> 24);
        buf[pos + 4] = (byte) (v >>> 32);
        buf[pos + 5] = (byte) (v >>> 40);
        buf[pos + 6] = (byte) (v >>> 48);
        buf[pos + 7] = (byte) (v >>> 56);
        pos += 8;
    }

    public void f64(double v) {
        i64(Double.doubleToRawLongBits(v));
    }

    public void bytes(byte[] data) {
        ensure(data.length);
        System.arraycopy(data, 0, buf, pos, data.length);
        pos += data.length;
    }

    public void bytes(byte[] data, int off, int len) {
        ensure(len);
        System.arraycopy(data, off, buf, pos, len);
        pos += len;
    }

    public void putI32At(int offset, int v) {
        if (offset < 0 || offset + 4 > buf.length) throw new IndexOutOfBoundsException();
        buf[offset] = (byte) v;
        buf[offset + 1] = (byte) (v >>> 8);
        buf[offset + 2] = (byte) (v >>> 16);
        buf[offset + 3] = (byte) (v >>> 24);
    }

    public void putI64At(int offset, long v) {
        if (offset < 0 || offset + 8 > buf.length) throw new IndexOutOfBoundsException();
        buf[offset] = (byte) v;
        buf[offset + 1] = (byte) (v >>> 8);
        buf[offset + 2] = (byte) (v >>> 16);
        buf[offset + 3] = (byte) (v >>> 24);
        buf[offset + 4] = (byte) (v >>> 32);
        buf[offset + 5] = (byte) (v >>> 40);
        buf[offset + 6] = (byte) (v >>> 48);
        buf[offset + 7] = (byte) (v >>> 56);
    }
}