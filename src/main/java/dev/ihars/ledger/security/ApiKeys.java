package dev.ihars.ledger.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * API key tokens: {@code lk_} followed by 43 base62 characters (about 256 bits from
 * {@link SecureRandom}). Only the SHA-256 of the token is stored. A fast hash is appropriate
 * because the token is random, not a human-chosen password; a slow KDF would only add latency
 * to every request.
 */
public final class ApiKeys {

    public static final String TOKEN_PREFIX = "lk_";
    public static final int SECRET_LENGTH = 43;
    public static final int DISPLAY_PREFIX_LENGTH = 11;
    private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final Pattern TOKEN = Pattern.compile("lk_[0-9A-Za-z]{" + SECRET_LENGTH + "}");
    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiKeys() {}

    public static String generate() {
        StringBuilder token = new StringBuilder(TOKEN_PREFIX.length() + SECRET_LENGTH).append(TOKEN_PREFIX);
        for (int i = 0; i < SECRET_LENGTH; i++) {
            token.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return token.toString();
    }

    /** Shape check only; lets malformed credentials fail without a database lookup. */
    public static boolean isWellFormed(String token) {
        return token != null && TOKEN.matcher(token).matches();
    }

    public static byte[] hash(String token) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", e);
        }
    }

    /** Non-secret leading characters for logs and support, e.g. {@code lk_AbC12xYz}. */
    public static String displayPrefix(String token) {
        return token.substring(0, DISPLAY_PREFIX_LENGTH);
    }
}
