package dev.ihars.ledger.security;

/** A newly created key with its plaintext token, returned exactly once. */
public record IssuedKey(ApiKey key, String token) {

    @Override
    public String toString() {
        return "IssuedKey[key=" + key + ", token=<redacted>]";
    }
}
