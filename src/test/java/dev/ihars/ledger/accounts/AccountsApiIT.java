package dev.ihars.ledger.accounts;

import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.security.ApiRole;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AccountsApiIT extends LedgerIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String WALLET = "{\"name\": \"Asha's wallet\", \"type\": \"LIABILITY\", \"currency\": \"INR\"}";

    private TestClient client;

    @BeforeEach
    void newCaller() {
        client = newClient(ApiRole.CLIENT);
    }

    @Test
    void aClientOpensItsOwnWalletExactlyOnce() {
        String key = key();
        HttpResponse<String> created = postAs(client.token(), "/api/v1/accounts", WALLET, "Idempotency-Key", key);

        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode body = json(created);
        String id = body.get("id").asString();
        assertThat(id).startsWith("acct_");
        assertThat(created.headers().firstValue("Location")).contains("/api/v1/accounts/" + id);
        assertThat(body.get("type").asString()).isEqualTo("LIABILITY");
        assertThat(body.get("normalBalance").asString()).isEqualTo("CREDIT");
        assertThat(body.get("overdraftPolicy").asString()).isEqualTo("FORBIDDEN");
        assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.get("ownerClientId").asString()).isEqualTo(client.id());

        HttpResponse<String> replay = postAs(client.token(), "/api/v1/accounts", WALLET, "Idempotency-Key", key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json(replay).get("id").asString()).isEqualTo(id);
        assertThat(replay.headers().firstValue("Location")).contains("/api/v1/accounts/" + id);
        assertThat(count("SELECT COUNT(*) FROM accounts a JOIN api_clients c ON c.id = a.owner_client_id "
                + "WHERE c.public_id = ?", client.id())).isEqualTo(1);

        HttpResponse<String> reused = postAs(client.token(), "/api/v1/accounts",
                WALLET.replace("Asha's wallet", "Other"), "Idempotency-Key", key);
        assertProblem(reused, 422, "IDEMPOTENCY_KEY_REUSED");
        assertProblem(postAs(client.token(), "/api/v1/accounts", WALLET), 400, "IDEMPOTENCY_KEY_MISSING");
        assertProblem(postAs(client.token(), "/api/v1/accounts", WALLET, "Idempotency-Key", "not valid"), 400,
                "IDEMPOTENCY_KEY_INVALID");
    }

    @Test
    void theOpeningPolicyLimitsWhatEachRoleMayOpen() {
        String asset = "{\"name\": \"Cash\", \"type\": \"ASSET\", \"currency\": \"INR\"}";
        String unlimited = "{\"name\": \"X\", \"type\": \"LIABILITY\", \"currency\": \"INR\", "
                + "\"overdraftPolicy\": \"UNLIMITED\"}";
        TestClient other = newClient(ApiRole.CLIENT);
        String forOther = "{\"name\": \"W\", \"type\": \"LIABILITY\", \"currency\": \"INR\", \"ownerClientId\": \""
                + other.id() + "\"}";

        assertProblem(open(client.token(), asset), 403, "FORBIDDEN");
        assertProblem(open(client.token(), unlimited), 403, "FORBIDDEN");
        assertProblem(open(client.token(), forOther), 403, "FORBIDDEN");
        assertProblem(open(ADMIN_KEY, unlimited), 403, "FORBIDDEN");

        String operator = keyFor(ApiRole.OPERATOR);
        assertProblem(open(operator, WALLET), 400, "VALIDATION_FAILED");
        assertProblem(open(operator, asset), 403, "FORBIDDEN");
        HttpResponse<String> opened = open(operator, forOther);
        assertThat(opened.statusCode()).isEqualTo(201);
        assertThat(json(opened).get("ownerClientId").asString()).isEqualTo(other.id());
        TestClient anotherOperator = newClient(ApiRole.OPERATOR);
        assertProblem(open(operator, forOther.replace(other.id(), anotherOperator.id())), 400, "VALIDATION_FAILED");

        HttpResponse<String> limited = open(ADMIN_KEY, "{\"name\": \"Credit line\", \"type\": \"ASSET\", "
                + "\"currency\": \"INR\", \"overdraftPolicy\": \"LIMITED\", "
                + "\"overdraftLimit\": {\"value\": \"100.00\", \"currency\": \"INR\"}}");
        assertThat(limited.statusCode()).isEqualTo(201);
        assertThat(json(limited).get("overdraftLimit").get("value").asString()).isEqualTo("100.00");
        assertThat(json(limited).get("ownerClientId").isNull()).isTrue();
    }

    @Test
    void malformedRequestsAreRejected() {
        String numericMoney = "{\"name\": \"Credit line\", \"type\": \"ASSET\", \"currency\": \"INR\", "
                + "\"overdraftPolicy\": \"LIMITED\", \"overdraftLimit\": {\"value\": 100.10, \"currency\": \"INR\"}}";
        assertProblem(open(ADMIN_KEY, numericMoney), 400, "BAD_REQUEST");
        assertProblem(open(ADMIN_KEY, numericMoney.replace("100.10", "100")), 400, "BAD_REQUEST");
        assertProblem(open(ADMIN_KEY, numericMoney.replace("100.10", "\"1.001\"")), 400, "VALIDATION_FAILED");
        assertProblem(open(ADMIN_KEY, numericMoney.replace("100.10", "\"100.10\"")
                .replace("\"currency\": \"INR\"}}", "\"currency\": \"USD\"}}")), 400, "VALIDATION_FAILED");
        assertThat(open(ADMIN_KEY, numericMoney.replace("100.10", "\"100.10\"")).statusCode()).isEqualTo(201);
        assertProblem(open(client.token(), WALLET.replace("INR", "XYZ")), 422, "UNSUPPORTED_CURRENCY");
        assertProblem(open(client.token(), "{\"name\": \"\", \"type\": \"LIABILITY\", \"currency\": \"INR\"}"), 400,
                "VALIDATION_FAILED");
        assertProblem(open(client.token(), WALLET.replace("}", ", \"colour\": \"red\"}")), 400, "BAD_REQUEST");
    }

    @Test
    void clientsSeeOnlyTheirOwnAccounts() {
        String mine = json(open(client.token(), WALLET)).get("id").asString();
        TestClient other = newClient(ApiRole.CLIENT);
        String theirs = json(open(other.token(), WALLET)).get("id").asString();
        String suspense = data.system("LIABILITY.SUSPENSE", "INR").publicId();

        assertThat(getAs(client.token(), "/api/v1/accounts/" + mine).statusCode()).isEqualTo(200);
        for (String hidden : List.of(theirs, suspense, "acct_00000000000000000000000000")) {
            assertProblem(getAs(client.token(), "/api/v1/accounts/" + hidden), 404, "ACCOUNT_NOT_FOUND");
            assertProblem(getAs(client.token(), "/api/v1/accounts/" + hidden + "/balance"), 404, "ACCOUNT_NOT_FOUND");
            assertProblem(getAs(client.token(), "/api/v1/accounts/" + hidden + "/entries"), 404, "ACCOUNT_NOT_FOUND");
        }
        assertThat(getAs(keyFor(ApiRole.OPERATOR), "/api/v1/accounts/" + theirs).statusCode()).isEqualTo(200);
        assertThat(json(getAs(ADMIN_KEY, "/api/v1/accounts/" + suspense)).get("systemCode").asString())
                .isEqualTo("LIABILITY.SUSPENSE");
    }

    @Test
    void listingIsScopedAndPaginated() {
        List<String> mine = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mine.add(json(open(client.token(), WALLET)).get("id").asString());
        }
        open(newClient(ApiRole.CLIENT).token(), WALLET);

        List<String> listed = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = json(getAs(client.token(), "/api/v1/accounts?limit=2" + (cursor == null ? "" : "&cursor=" + cursor)));
            page.get("data").forEach(a -> listed.add(a.get("id").asString()));
            cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
        } while (cursor != null);
        assertThat(listed).containsExactlyElementsOf(mine);

        JsonNode forClient = json(getAs(ADMIN_KEY, "/api/v1/accounts?limit=200&ownerClientId=" + client.id()));
        assertThat(forClient.get("data")).hasSize(3);
        assertProblem(getAs(client.token(), "/api/v1/accounts?ownerClientId=cli_00000000000000000000000000"), 403,
                "FORBIDDEN");
        assertProblem(getAs(client.token(), "/api/v1/accounts?limit=201"), 400, "VALIDATION_FAILED");
        assertProblem(getAs(client.token(), "/api/v1/accounts?cursor=%21%21"), 400, "BAD_REQUEST");
    }

    @Test
    void balancesAndEntriesNewestFirst() {
        String id = json(open(client.token(), WALLET)).get("id").asString();
        Account wallet = accounts.findByPublicId(id).orElseThrow();
        String journal = data.deposit(wallet, "10.00").publicId();
        data.deposit(wallet, "2.50");
        data.deposit(wallet, "0.25");

        JsonNode balance = json(getAs(client.token(), "/api/v1/accounts/" + id + "/balance"));
        assertThat(balance.get("ledgerBalance").get("value").asString()).isEqualTo("12.75");
        assertThat(balance.get("ledgerBalance").get("currency").asString()).isEqualTo("INR");
        assertThat(balance.get("availableBalance").get("value").asString()).isEqualTo("12.75");
        assertThat(balance.get("lastEntrySequence").asLong()).isEqualTo(3);
        JsonNode past = json(getAs(client.token(), "/api/v1/accounts/" + id + "/balance?asOf="
                + calendar.today().minusDays(1)));
        assertThat(past.get("ledgerBalance").get("value").asString()).isEqualTo("0.00");
        assertProblem(getAs(client.token(), "/api/v1/accounts/" + id + "/balance?asOf=yesterday"), 400, "BAD_REQUEST");

        JsonNode first = json(getAs(client.token(), "/api/v1/accounts/" + id + "/entries?limit=2"));
        assertThat(first.get("data")).extracting(e -> e.get("sequence").asLong()).containsExactly(3L, 2L);
        assertThat(first.get("data").get(0).get("amount").get("value").asString()).isEqualTo("0.25");
        assertThat(first.get("data").get(0).get("journalType").asString()).isEqualTo("DEPOSIT");
        assertThat(first.get("data").get(0).has("entryId")).isFalse();
        JsonNode second = json(getAs(client.token(), "/api/v1/accounts/" + id + "/entries?limit=2&cursor="
                + first.get("nextCursor").asString()));
        assertThat(second.get("data")).extracting(e -> e.get("sequence").asLong()).containsExactly(1L);
        assertThat(second.get("data").get(0).get("journalId").asString()).isEqualTo(journal);
        assertThat(second.get("nextCursor").isNull()).isTrue();
        assertProblem(getAs(client.token(), "/api/v1/accounts/" + id + "/entries?cursor=bm9wZQ"), 400, "BAD_REQUEST");
    }

    @Test
    void lifecycleChangesFollowTheRoleMatrixAndAreIdempotent() {
        String id = json(open(client.token(), WALLET)).get("id").asString();
        String operator = keyFor(ApiRole.OPERATOR);
        String reason = "{\"reason\": \"Suspicious activity\"}";
        String freeze = "/api/v1/accounts/" + id + "/freeze";

        assertProblem(postAs(client.token(), freeze, reason, "Idempotency-Key", key()), 403, "FORBIDDEN");
        String key = key();
        HttpResponse<String> frozen = postAs(operator, freeze, reason, "Idempotency-Key", key);
        assertThat(frozen.statusCode()).isEqualTo(200);
        assertThat(json(frozen).get("status").asString()).isEqualTo("FROZEN");
        HttpResponse<String> replay = postAs(operator, freeze, reason, "Idempotency-Key", key);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertProblem(postAs(operator, freeze, reason, "Idempotency-Key", key()), 409, "ACCOUNT_STATUS_CONFLICT");
        assertProblem(postAs(operator, freeze, "{\"reason\": \"\"}", "Idempotency-Key", key()), 400,
                "VALIDATION_FAILED");

        assertProblem(postAs(operator, "/api/v1/accounts/" + id + "/unfreeze", reason, "Idempotency-Key", key()), 403,
                "FORBIDDEN");
        HttpResponse<String> unfrozen = postAs(ADMIN_KEY, "/api/v1/accounts/" + id + "/unfreeze", reason,
                "Idempotency-Key", key());
        assertThat(json(unfrozen).get("status").asString()).isEqualTo("ACTIVE");

        data.deposit(accounts.findByPublicId(id).orElseThrow(), "1.00");
        assertProblem(postAs(ADMIN_KEY, "/api/v1/accounts/" + id + "/close", reason, "Idempotency-Key", key()), 409,
                "ACCOUNT_BALANCE_NOT_ZERO");
        assertProblem(postAs(operator, "/api/v1/accounts/acct_00000000000000000000000000/freeze", reason,
                "Idempotency-Key", key()), 404, "ACCOUNT_NOT_FOUND");
        String suspense = data.system("LIABILITY.SUSPENSE", "INR").publicId();
        assertProblem(postAs(ADMIN_KEY, "/api/v1/accounts/" + suspense + "/freeze", reason, "Idempotency-Key", key()),
                409, "ACCOUNT_STATUS_CONFLICT");

        String empty = json(open(client.token(), WALLET)).get("id").asString();
        HttpResponse<String> closed = postAs(ADMIN_KEY, "/api/v1/accounts/" + empty + "/close", reason,
                "Idempotency-Key", key());
        assertThat(json(closed).get("status").asString()).isEqualTo("CLOSED");
    }

    private HttpResponse<String> open(String token, String body) {
        return postAs(token, "/api/v1/accounts", body, "Idempotency-Key", key());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    private static void assertProblem(HttpResponse<String> response, int status, String code) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json(response).get("code").asString()).as(response.body()).isEqualTo(code);
    }
}
