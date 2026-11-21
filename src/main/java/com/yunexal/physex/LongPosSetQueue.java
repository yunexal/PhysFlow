package com.yunexal.physex;

import java.util.Arrays;

/**
 * Zero-allocation primitive FIFO queue + open-addressing hash set for packed 64-bit block positions.
 * Prevents duplicate fluid coordinates in the active simulation queue without boxing Long objects.
 */
public final class LongPosSetQueue {

    private static final long EMPTY = Long.MIN_VALUE;

    private long[] queue;
    private int head;
    private int tail;
    private int count;

    private long[] table;
    private int mask;
    private int resizeThreshold;

    public LongPosSetQueue(int initialCapacity) {
        int cap = nextPowerOfTwo(Math.max(16, initialCapacity));
        this.queue = new long[cap];
        this.table = new long[cap * 2];
        Arrays.fill(this.table, EMPTY);
        this.mask = this.table.length - 1;
        this.resizeThreshold = (int) (this.table.length * 0.65);
    }

    public static long pack(int x, int y, int z) {
        return (((long) (x & 0x3FFFFFF)) << 38)
                | (((long) (z & 0x3FFFFFF)) << 12)
                | ((long) (y & 0xFFF));
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    public static int unpackY(long packed) {
        return (int) ((packed << 52) >> 52);
    }

    public static int unpackZ(long packed) {
        return (int) ((packed << 26) >> 38);
    }

    public int size() {
        return count;
    }

    public boolean isEmpty() {
        return count == 0;
    }

    public boolean enqueue(int x, int y, int z, int maxAllowed) {
        if (count >= maxAllowed) {
            return false;
        }
        return enqueuePacked(pack(x, y, z));
    }

    public boolean enqueuePacked(long packed) {
        if (packed == EMPTY) {
            return false;
        }
        int idx = mix64(packed) & mask;
        while (true) {
            long existing = table[idx];
            if (existing == packed) {
                return false;
            }
            if (existing == EMPTY) {
                table[idx] = packed;
                pushQueue(packed);
                if (count >= resizeThreshold) {
                    rehashTable(table.length * 2);
                }
                return true;
            }
            idx = (idx + 1) & mask;
        }
    }

    public long pollPacked() {
        if (count == 0) {
            return EMPTY;
        }
        long val = queue[head];
        head = (head + 1) % queue.length;
        count--;
        removeFromTable(val);
        return val;
    }

    public long[] snapshotArray() {
        long[] copy = new long[count];
        for (int i = 0; i < count; i++) {
            copy[i] = queue[(head + i) % queue.length];
        }
        return copy;
    }

    public void clear() {
        head = 0;
        tail = 0;
        count = 0;
        Arrays.fill(table, EMPTY);
    }

    private void pushQueue(long val) {
        if (count == queue.length) {
            long[] next = new long[queue.length * 2];
            for (int i = 0; i < count; i++) {
                next[i] = queue[(head + i) % queue.length];
            }
            queue = next;
            head = 0;
            tail = count;
        }
        queue[tail] = val;
        tail = (tail + 1) % queue.length;
        count++;
    }

    private void removeFromTable(long val) {
        int idx = mix64(val) & mask;
        while (true) {
            long cur = table[idx];
            if (cur == EMPTY) {
                return;
            }
            if (cur == val) {
                int hole = idx;
                int probe = (hole + 1) & mask;
                while (table[probe] != EMPTY) {
                    int ideal = mix64(table[probe]) & mask;
                    if (((probe - ideal) & mask) >= ((probe - hole) & mask)) {
                        table[hole] = table[probe];
                        hole = probe;
                    }
                    probe = (probe + 1) & mask;
                }
                table[hole] = EMPTY;
                return;
            }
            idx = (idx + 1) & mask;
        }
    }

    private void rehashTable(int newSize) {
        long[] next = new long[newSize];
        Arrays.fill(next, EMPTY);
        int newMask = newSize - 1;
        for (long key : table) {
            if (key != EMPTY) {
                int idx = mix64(key) & newMask;
                while (next[idx] != EMPTY) {
                    idx = (idx + 1) & newMask;
                }
                next[idx] = key;
            }
        }
        this.table = next;
        this.mask = newMask;
        this.resizeThreshold = (int) (newSize * 0.65);
    }

    private static int mix64(long z) {
        z = (z ^ (z >>> 33)) * 0xff51afd7ed558ccdL;
        z = (z ^ (z >>> 33)) * 0xc4ceb9fe1a85ec53L;
        return (int) (z ^ (z >>> 33));
    }

    private static int nextPowerOfTwo(int v) {
        int n = 16;
        while (n < v) {
            n <<= 1;
        }
        return n;
    }
}
