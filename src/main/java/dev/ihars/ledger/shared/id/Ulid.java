package dev.ihars.ledger.shared.id;

/**
 * ULID encoding (https://github.com/ulid/spec): 48-bit millisecond timestamp and 80 random
 * bits as 26 Crockford base32 characters.
 */
final class Ulid {

    static final int RANDOM_BYTES = 10;
    static final int LENGTH = 26;
    private static final long MAX_TIMESTAMP = (1L << 48) - 1;
    private static final char[] ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();

    private Ulid() {}

    static String encode(long epochMillis, byte[] randomness) {
        if (epochMillis < 0 || epochMillis > MAX_TIMESTAMP) {
            throw new IllegalArgumentException("Timestamp outside the 48-bit ULID range: " + epochMillis);
        }
        if (randomness.length != RANDOM_BYTES) {
            throw new IllegalArgumentException("ULID randomness must be " + RANDOM_BYTES + " bytes");
        }
        char[] out = new char[LENGTH];
        long time = epochMillis;
        for (int i = 9; i >= 0; i--) {
            out[i] = ALPHABET[(int) (time & 31)];
            time >>>= 5;
        }
        // 80 random bits -> 16 characters, 5 bits at a time, most significant first.
        int bitBuffer = 0;
        int bitCount = 0;
        int position = 10;
        for (byte b : randomness) {
            bitBuffer = (bitBuffer << 8) | (b & 0xFF);
            bitCount += 8;
            while (bitCount >= 5) {
                bitCount -= 5;
                out[position++] = ALPHABET[(bitBuffer >>> bitCount) & 31];
            }
        }
        return new String(out);
    }
}
