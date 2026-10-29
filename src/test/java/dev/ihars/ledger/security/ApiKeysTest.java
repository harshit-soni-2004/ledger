package dev.ihars.ledger.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ApiKeysTest {

    @Test
    void generatedTokensAreWellFormedAndUnique() {
        Set<String> tokens = new HashSet<>();
        for (int i = 0; i < 1_000; i++) {
            String token = ApiKeys.generate();
            assertThat(token).matches("lk_[0-9A-Za-z]{43}");
            assertThat(ApiKeys.isWellFormed(token)).isTrue();
            tokens.add(token);
        }
        assertThat(tokens).hasSize(1_000);
    }

    @Test
    void malformedTokensAreRecognisedWithoutALookup() {
        assertThat(ApiKeys.isWellFormed(null)).isFalse();
        assertThat(ApiKeys.isWellFormed("")).isFalse();
        assertThat(ApiKeys.isWellFormed("lk_short")).isFalse();
        assertThat(ApiKeys.isWellFormed("sk_" + "a".repeat(43))).isFalse();
        assertThat(ApiKeys.isWellFormed("lk_" + "a".repeat(42) + "!")).isFalse();
        assertThat(ApiKeys.isWellFormed("lk_" + "a".repeat(44))).isFalse();
    }

    @Test
    void theStoredHashIsSha256OfTheToken() {
        // SHA-256("abc"), FIPS 180-2 test vector.
        assertThat(HexFormat.of().formatHex(ApiKeys.hash("abc")))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void theDisplayPrefixRevealsOnlyEightSecretCharacters() {
        String token = ApiKeys.generate();

        assertThat(ApiKeys.displayPrefix(token)).hasSize(11).startsWith("lk_");
        assertThat(token).startsWith(ApiKeys.displayPrefix(token));
    }

    @Test
    void anIssuedKeyNeverPrintsItsToken() {
        String token = ApiKeys.generate();
        IssuedKey issued = new IssuedKey(new ApiKey("key_x", "cli_x", ApiKeys.displayPrefix(token),
                ApiKey.Status.ACTIVE, Instant.EPOCH, null), token);

        assertThat(issued.toString()).doesNotContain(token).contains("<redacted>");
    }
}
