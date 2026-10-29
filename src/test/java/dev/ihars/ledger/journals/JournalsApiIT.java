package dev.ihars.ledger.journals;

import static dev.ihars.ledger.support.LedgerTestData.BANK;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.security.ApiRole;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class JournalsApiIT extends LedgerIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String OPENINGS = "/api/v1/journal-transactions/openings";

    @Test
    void anAdminPostsABackdatedOpeningBalanceOnce() {
        Account wallet = data.wallet("INR");
        Account bank = data.system(BANK, "INR");
        LocalDate monthAgo = calendar.today().minusDays(30);
        String body = opening(monthAgo, "Migrated from legacy core (ticket MIG-7)", bank, wallet, "500.00");
        String key = key();

        HttpResponse<String> created = postAs(ADMIN_KEY, OPENINGS, body, "Idempotency-Key", key);

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode journal = json(created);
        String id = journal.get("id").asString();
        assertThat(created.headers().firstValue("Location")).contains("/api/v1/journal-transactions/" + id);
        assertThat(journal.get("type").asString()).isEqualTo("OPENING");
        assertThat(journal.get("effectiveDate").asString()).isEqualTo(monthAgo.toString());
        assertThat(journal.get("reason").asString()).isEqualTo("Migrated from legacy core (ticket MIG-7)");
        assertThat(journal.get("total").get("value").asString()).isEqualTo("500.00");
        assertThat(journal.get("entries")).extracting(e -> e.get("accountId").asString())
                .containsExactly(bank.publicId(), wallet.publicId());
        assertThat(queries.balance(wallet.id()).orElseThrow().ledgerBalance().toPlainString()).isEqualTo("500.00");

        HttpResponse<String> replay = postAs(ADMIN_KEY, OPENINGS, body, "Idempotency-Key", key);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json(replay).get("id").asString()).isEqualTo(id);
        assertThat(queries.balance(wallet.id()).orElseThrow().lastEntrySeq()).isEqualTo(1);

        assertProblem(postAs(ADMIN_KEY, OPENINGS, body, "Idempotency-Key", key()), 422, "OPENING_NOT_ALLOWED");

        JsonNode fetched = json(getAs(keyFor(ApiRole.OPERATOR), "/api/v1/journal-transactions/" + id));
        assertThat(fetched.get("entries").get(1).get("balanceAfter").get("value").asString()).isEqualTo("500.00");
        assertThat(integrity.check().violations()).isEmpty();
    }

    @Test
    void openingsAreOnlyForAccountsWithoutHistory() {
        Account used = data.wallet("INR");
        data.deposit(used, "1.00");
        Account bank = data.system(BANK, "INR");

        HttpResponse<String> response = postAs(ADMIN_KEY, OPENINGS,
                opening(calendar.today(), "Opening", bank, used, "5.00"), "Idempotency-Key", key());

        assertProblem(response, 422, "OPENING_NOT_ALLOWED");
        assertThat(json(response).get("rule").asString()).isEqualTo("OPENING_NOT_ALLOWED");
        assertThat(json(response).get("detail").asString()).doesNotContain(String.valueOf(used.id()));
        assertThat(queries.balance(used.id()).orElseThrow().lastEntrySeq()).isEqualTo(1);
    }

    @Test
    void concurrentOpeningsOfOneAccountPostAtMostOnce() {
        Account wallet = data.wallet("INR");
        Account bank = data.system(BANK, "INR");
        String body = opening(calendar.today(), "Opening", bank, wallet, "7.00");

        List<CompletableFuture<HttpResponse<String>>> calls = List.of(
                CompletableFuture.supplyAsync(() -> postAs(ADMIN_KEY, OPENINGS, body, "Idempotency-Key", key())),
                CompletableFuture.supplyAsync(() -> postAs(ADMIN_KEY, OPENINGS, body, "Idempotency-Key", key())));

        List<Integer> statuses = calls.stream().map(CompletableFuture::join).map(HttpResponse::statusCode).toList();
        assertThat(statuses).containsExactlyInAnyOrder(201, 422);
        assertThat(queries.balance(wallet.id()).orElseThrow().ledgerBalance().toPlainString()).isEqualTo("7.00");
    }

    @Test
    void invalidOpeningsAreRejected() {
        Account wallet = data.wallet("INR");
        Account bank = data.system(BANK, "INR");
        LocalDate today = calendar.today();

        assertProblem(post(opening(today.plusDays(1), "Opening", bank, wallet, "1.00")), 422,
                "EFFECTIVE_DATE_IN_FUTURE");
        assertProblem(post(opening(today, "  ", bank, wallet, "1.00")), 400, "VALIDATION_FAILED");
        assertProblem(post(opening(today, "Opening", bank, wallet, "1.00").replace("\"1.00\"", "1.00")), 400,
                "BAD_REQUEST");
        assertProblem(post(opening(today, "Opening", bank, wallet, "1.001")), 400, "VALIDATION_FAILED");
        assertProblem(post(opening(today, "Opening", bank, wallet, "1.00")
                .replaceFirst("\"1.00\"", "\"2.00\"")), 422, "JOURNAL_REJECTED");
        assertProblem(post(opening(today, "Opening", bank, wallet, "1.00")
                .replace(wallet.publicId(), "acct_00000000000000000000000000")), 422, "ACCOUNT_NOT_FOUND");
        Account usd = data.wallet("USD");
        assertProblem(post(opening(today, "Opening", bank, usd, "1.00")), 422, "CURRENCY_MISMATCH");
        assertProblem(postAs(ADMIN_KEY, OPENINGS, opening(today, "Opening", bank, wallet, "1.00")), 400,
                "IDEMPOTENCY_KEY_MISSING");
        assertThat(queries.balance(wallet.id()).orElseThrow().lastEntrySeq()).isZero();
    }

    @Test
    void journalEndpointsFollowTheRoleMatrix() {
        Account wallet = data.wallet("INR");
        String journal = data.deposit(wallet, "3.00").publicId();
        Account bank = data.system(BANK, "INR");
        String body = opening(calendar.today(), "Opening", bank, data.wallet("INR"), "1.00");

        assertProblem(postAs(keyFor(ApiRole.OPERATOR), OPENINGS, body, "Idempotency-Key", key()), 403, "FORBIDDEN");
        assertProblem(postAs(keyFor(ApiRole.CLIENT), OPENINGS, body, "Idempotency-Key", key()), 403, "FORBIDDEN");
        assertProblem(getAs(keyFor(ApiRole.CLIENT), "/api/v1/journal-transactions/" + journal), 403, "FORBIDDEN");
        assertThat(getAs(ADMIN_KEY, "/api/v1/journal-transactions/" + journal).statusCode()).isEqualTo(200);
        assertProblem(getAs(ADMIN_KEY, "/api/v1/journal-transactions/jtx_00000000000000000000000000"), 404,
                "NOT_FOUND");
    }

    private HttpResponse<String> post(String body) {
        return postAs(ADMIN_KEY, OPENINGS, body, "Idempotency-Key", key());
    }

    private static String opening(LocalDate date, String reason, Account debit, Account credit, String amount) {
        return """
                {"currency": "INR", "effectiveDate": "%s", "reason": "%s", "lines": [
                  {"accountId": "%s", "direction": "DEBIT", "amount": {"value": "%s", "currency": "INR"}},
                  {"accountId": "%s", "direction": "CREDIT", "amount": {"value": "%s", "currency": "INR"}}]}
                """.formatted(date, reason, debit.publicId(), amount, credit.publicId(), amount);
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
