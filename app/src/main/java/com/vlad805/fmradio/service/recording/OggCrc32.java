package com.vlad805.fmradio.service.recording;

/**
 * Standard Ogg CRC-32 (polynomial 0x04C11DB7, MSB-first, init 0, no final XOR).
 */
public final class OggCrc32 {
    private static final int[] TABLE = new int[256];

    static {
        for (int i = 0; i < 256; i++) {
            int r = i << 24;

            for (int j = 0; j < 8; j++) {
                if ((r & 0x80000000) != 0) {
                    r = (r << 1) ^ 0x04C11DB7;
                } else {
                    r <<= 1;
                }
            }

            TABLE[i] = r;
        }
    }

    private OggCrc32() {}

    public static int update(final int crc, final byte[] data, final int offset, final int length) {
        int c = crc;

        for (int i = 0; i < length; i++) {
            c = (c << 8) ^ TABLE[((c >>> 24) ^ (data[offset + i] & 0xFF)) & 0xFF];
        }

        return c;
    }

    public static int compute(final byte[] data, final int offset, final int length) {
        return update(0, data, offset, length);
    }
}
