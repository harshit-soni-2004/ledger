package dev.ihars.ledger.transfers;

import static dev.ihars.ledger.support.LedgerTestData.BANK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.security.ApiRole;
import dev.ihars.ledger.support.LedgerIntegrationTest;
import java.math.BigDecimal;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TransfersApiIT extends LedgerIntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String TRANSFERS = "/api/v1/transfers";

    private TestClient asha;
    private TestClient ravi;

    @BeforeEach
    void clients() {
        asha = newClient(ApiRole.CLIENT);
        ravi = newClient(ApiRole.CLIENT);
    }

    @Test
    void aClientTransfersFromItsWalletExactlyOnce() {
        Account from = wallet(asha, "INR");
        Account to = wallet(ravi, "INR");
        data.deposit(from, "1000.00");
        String body = transfer(from, to, "250.50", "rent-2026-10");
        String key = key();

        HttpResponse<String> created = postAs(asha.token(), TRANSFERS, body, "Idempotency-Key", key);

        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        JsonNode transfer = json(created);
        String id = transfer.get("id").asString();
        assertThat(id).startsWith("trf_");
        assertThat(created.headers().firstValue("Location")).contains("/api/v1/transfers/" + id);
        assertThat(transfer.get("status").asString()).isEqualTo("POSTED");
        assertThat(transfer.get("amount").get("value").asString()).isEqualTo("250.50");
        assertThat(transfer.get("clientReference").asString()).isEqualTo("rent-2026-10");
        assertThat(balance(from)).isEqualTo("749.50");
        assertThat(balance(to)).isEqualTo("250.50");

        HttpResponse<String> replay = postAs(asha.token(), TRANSFERS, body, "Idempotency-Key", key);
        assertThat(replay.statusCode()).isEqualTo(201);
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json(replay).get("id").asString()).isEqualTo(id);
        assertThat(balance(from)).isEqualTo("749.50");
        assertProblem(postAs(asha.token(), TRANSFERS, transfer(from, to, "1.00", null), "Idempotency-Key", key),
                422, "IDEMPOTENCY_KEY_REUSED");

        String journal = transfer.get("journalTransactionId").asString();
        JsonNode posted = json(getAs(ADMIN_KEY, "/api/v1/journal-transactions/" + journal));
        assertThat(posted.get("type").asString()).isEqualTo("TRANSFER");
        assertThat(posted.get("externalReference").asString()).isEqualTo("rent-2026-10");
        JsonNode entries = json(getAs(ravi.token(), "/api/v1/accounts/" + to.publicId() + "/entries"));
        assertThat(entries.get("data").get(0).get("journalId").asString()).isEqualTo(journal);
        assertThat(count("SELECT COUNT(*) FROM transfers t JOIN api_clients c ON c.id = t.client_id "
                + "WHERE c.public_id = ?", asha.id())).isEqualTo(1);
        assertThat(integrity.check().violations()).isEmpty();
    }

    @Test
    void insufficientFundsIsRecordedAndReplayedForTheSameKey() {
        Account from = wallet(asha, "INR");
        Account to = wallet(ravi, "INR");
        data.deposit(from, "10.00");
        String body = transfer(from, to, "10.01", null);
        String key = key();

        HttpResponse<String> rejected = postAs(asha.token(), TRANSFERS, body, "Idempotency-Key", key);

        assertProblem(rejected, 422, "INSUFFICIENT_FUNDS");
        assertThat(rejected.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        String id = json(rejected).get("transferId").asString();
        assertThat(balance(from)).isEqualTo("10.00");
        assertThat(balance(to)).isEqualTo("0.00");

        data.deposit(from, "100.00");
        HttpResponse<String> replay = postAs(asha.token(), TRANSFERS, body, "Idempotency-Key", key);
        assertProblem(replay, 422, "INSUFFICIENT_FUNDS");
        assertThat(replay.headers().firstValue("Idempotent-Replayed")).contains("true");
        assertThat(json(replay).get("transferId").asString()).isEqualTo(id);
        assertThat(balance(from)).isEqualTo("110.00");

        JsonNode stored = json(getAs(asha.token(), TRANSFERS + "/" + id));
        assertThat(stored.get("status").asString()).isEqualTo("REJECTED");
        assertThat(stored.get("rejectionCode").asString()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(stored.get("journalTransactionId").isNull()).isTrue();
        assertThat(count("SELECT COUNT(*) FROM audit_events WHERE action = 'TRANSFER_REJECTED' AND entity_id = ?",
                id)).isEqualTo(1);

        assertThat(postAs(asha.token(), TRANSFERS, body, "Idempotency-Key", key()).statusCode()).isEqualTo(201);
        assertThat(balance(from)).isEqualTo("99.99");
    }

    @Test
    void aFrozenAccountRejectsTheTransferUnderTheLock() {
        Account from = wallet(asha, "INR");
        Account to = wallet(ravi, "INR");
        data.deposit(from, "50.00");
        assertThat(postAs(keyFor(ApiRole.OPERATOR), "/api/v1/accounts/" + to.publicId() + "/freeze",
                "{\"reason\": \"KYC review\"}", "Idempotency-Key", key()).statusCode()).isEqualTo(200);

        HttpResponse<String> rejected = postAs(asha.token(), TRANSFERS, transfer(from, to, "5.00", null),
                "Idempotency-Key", key());

        assertProblem(rejected, 422, "ACCOUNT_NOT_ACTIVE");
        assertThat(balance(from)).isEqualTo("50.00");
        assertThat(json(getAs(asha.token(), TRANSFERS + "/" + json(rejected).get("transferId").asString()))
                .get("rejectionCode").asString()).isEqualTo("ACCOUNT_NOT_ACTIVE");
    }

    @Test
    void invalidTransfersAreRefusedWithoutARecord() {
        Account from = wallet(asha, "INR");
        Account to = wallet(ravi, "INR");
        Account ravisOwn = wallet(ravi, "INR");
        Account usd = wallet(ravi, "USD");
        Account bank = data.system(BANK, "INR");
        data.deposit(from, "100.00");
        data.deposit(ravisOwn, "100.00");

        assertProblem(post(asha, transfer(ravisOwn, to, "1.00", null)), 422, "ACCOUNT_NOT_FOUND");
        assertProblem(post(asha, transfer(from, bank, "1.00", null)), 422, "ACCOUNT_NOT_FOUND");
        assertProblem(post(asha, transfer(from, from, "1.00", null)), 400, "VALIDATION_FAILED");
        assertProblem(post(asha, transfer(from, usd, "1.00", null)), 422, "CURRENCY_MISMATCH");
        assertProblem(post(asha, transfer(from, to, "1.00", null).replace("\"currency\": \"INR\"",
                "\"currency\": \"USD\"")), 422, "CURRENCY_MISMATCH");
        assertProblem(post(asha, transfer(from, to, "1.001", null)), 400, "VALIDATION_FAILED");
        assertProblem(post(asha, transfer(from, to, "0", null)), 400, "VALIDATION_FAILED");
        assertProblem(post(asha, transfer(from, to, "-1.00", null)), 400, "VALIDATION_FAILED");
        assertProblem(post(asha, transfer(from, to, "1.00", null).replace("\"1.00\"", "1.00")), 400, "BAD_REQUEST");
        assertProblem(post(asha, transfer(from, to, "1.00", "has space")), 400, "VALIDATION_FAILED");
        assertProblem(postAs(asha.token(), TRANSFERS, transfer(from, to, "1.00", null)), 400,
                "IDEMPOTENCY_KEY_MISSING");
        assertProblem(postAs(keyFor(ApiRole.OPERATOR), TRANSFERS, transfer(from, to, "1.00", null),
                "Idempotency-Key", key()), 403, "FORBIDDEN");

        assertThat(count("SELECT COUNT(*) FROM transfers t JOIN api_clients c ON c.id = t.client_id "
                + "WHERE c.public_id = ?", asha.id())).isZero();
        assertThat(balance(from)).isEqualTo("100.00");
    }

    @Test
    void concurrentTransfersNeverOverspend() throws Exception {
        Account from = wallet(asha, "INR");
        Account to = wallet(ravi, "INR");
        data.deposit(from, "80.00");
        int threads = 16;
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<HttpResponse<String>>> calls;
        try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
            calls = IntStream.range(0, threads).mapToObj(i -> CompletableFuture.supplyAsync(() -> {
                await(start);
                return postAs(asha.token(), TRANSFERS, transfer(from, to, "10.00", null), "Idempotency-Key", key());
            }, pool)).toList();
            start.countDown();
            CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).join();
        }

        List<Integer> statuses = calls.stream().map(CompletableFuture::join).map(HttpResponse::statusCode).toList();
        assertThat(statuses).filteredOn(s -> s == 201).hasSize(8);
        assertThat(statuses).filteredOn(s -> s == 422).hasSize(8);
        assertThat(balance(from)).isEqualTo("0.00");
        assertThat(balance(to)).isEqualTo("80.00");
        assertThat(count("SELECT COUNT(*) FROM ledger_entries WHERE account_id = ? AND balance_after < 0",
                from.id())).isZero();
        assertThat(integrity.check().violations()).isEmpty();
    }

    @Test
    void concurrentDuplicatesOfOneKeyTransferOnce() {
        Account from = wallet(asha, "INR");
        Account to = wallet(ravi, "INR");
        data.deposit(from, "100.00");
        String body = transfer(from, to, "30.00", null);
        String key = key();

        List<HttpResponse<String>> responses = IntStream.range(0, 4)
                .mapToObj(i -> CompletableFuture.supplyAsync(
                        () -> postAs(asha.token(), TRANSFERS, body, "Idempotency-Key", key)))
                .toList().stream().map(CompletableFuture::join).toList();

        assertThat(responses).extracting(HttpResponse::statusCode).containsOnly(201);
        assertThat(responses).extracting(r -> json(r).get("id").asString()).containsOnly(
                json(responses.getFirst()).get("id").asString());
        assertThat(balance(from)).isEqualTo("70.00");
    }

    @Test
    void transfersAreVisibleToTheirClientAndToStaff() {
        Account from = wallet(asha, "INR");
        Account to = wallet(ravi, "INR");
        data.deposit(from, "100.00");
        List<String> ids = IntStream.range(0, 3)
                .mapToObj(i -> json(post(asha, transfer(from, to, "1.00", "ref-" + i))).get("id").asString())
                .toList();

        assertProblem(getAs(ravi.token(), TRANSFERS + "/" + ids.getFirst()), 404, "NOT_FOUND");
        assertThat(getAs(keyFor(ApiRole.OPERATOR), TRANSFERS + "/" + ids.getFirst()).statusCode()).isEqualTo(200);
        assertThat(json(getAs(ravi.token(), TRANSFERS)).get("data")).isEmpty();
        assertProblem(getAs(ravi.token(), TRANSFERS + "?clientId=" + asha.id()), 403, "FORBIDDEN");

        JsonNode first = json(getAs(asha.token(), TRANSFERS + "?limit=2"));
        assertThat(first.get("data")).extracting(t -> t.get("id").asString())
                .containsExactly(ids.get(2), ids.get(1));
        JsonNode second = json(getAs(asha.token(), TRANSFERS + "?limit=2&cursor=" + first.get("nextCursor").asString()));
        assertThat(second.get("data")).extracting(t -> t.get("id").asString()).containsExactly(ids.get(0));
        assertThat(second.get("nextCursor").isNull()).isTrue();

        JsonNode byReference = json(getAs(asha.token(), TRANSFERS + "?clientReference=ref-1"));
        assertThat(byReference.get("data")).extracting(t -> t.get("id").asString()).containsExactly(ids.get(1));
        JsonNode staff = json(getAs(ADMIN_KEY, TRANSFERS + "?clientId=" + asha.id() + "&status=POSTED"));
        assertThat(staff.get("data")).hasSize(3);
        assertProblem(getAs(asha.token(), TRANSFERS + "?status=NOPE"), 400, "BAD_REQUEST");
    }

    @Test
    void theSchemaRejectsInconsistentTransfers() {
        Account from = wallet(asha, "INR");
        Account to = wallet(ravi, "INR");
        Account usd = wallet(ravi, "USD");
        String insert = """
                INSERT INTO transfers (public_id, client_id, idempotency_key, source_account_id,
                    destination_account_id, currency, amount, status, rejection_code, journal_id, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'INR', ?, ?, ?, NULL, NOW(6), NOW(6))
                """;

        assertRejected(insert, CHECK_CONSTRAINT_VIOLATED, from.id(), to.id(), "1.00", "POSTED", null);
        assertRejected(insert, CHECK_CONSTRAINT_VIOLATED, from.id(), to.id(), "1.00", "REJECTED", null);
        assertRejected(insert, CHECK_CONSTRAINT_VIOLATED, from.id(), from.id(), "1.00", "REJECTED", "X");
        assertRejected(insert, CHECK_CONSTRAINT_VIOLATED, from.id(), to.id(), "0.00", "REJECTED", "X");
        assertRejected(insert, FOREIGN_KEY_VIOLATED, from.id(), usd.id(), "1.00", "REJECTED", "X");
    }

    private void assertRejected(String insert, int error, long source, long destination, String amount,
            String status, String rejectionCode) {
        long client = count("SELECT id FROM api_clients WHERE public_id = ?", asha.id());
        assertThatThrownBy(() -> privilegedJdbc().sql(insert)
                        .params("trf_01M60000000000000000000000", client, key(), source, destination,
                                new BigDecimal(amount), status, rejectionCode)
                        .update())
                .as("%s %s->%s %s", status, source, destination, amount)
                .satisfies(e -> assertThat(mysqlErrorCode(e)).contains(error));
    }

    private Account wallet(TestClient owner, String currency) {
        HttpResponse<String> opened = postAs(owner.token(), "/api/v1/accounts",
                "{\"name\": \"Wallet\", \"type\": \"LIABILITY\", \"currency\": \"" + currency + "\"}",
                "Idempotency-Key", key());
        assertThat(opened.statusCode()).as(opened.body()).isEqualTo(201);
        return accounts.findByPublicId(json(opened).get("id").asString()).orElseThrow();
    }

    private String balance(Account account) {
        return queries.balance(account.id()).orElseThrow().ledgerBalance().toPlainString();
    }

    private HttpResponse<String> post(TestClient caller, String body) {
        return postAs(caller.token(), TRANSFERS, body, "Idempotency-Key", key());
    }

    private static String transfer(Account from, Account to, String amount, String reference) {
        return """
                {"sourceAccountId": "%s", "destinationAccountId": "%s",
                 "amount": {"value": "%s", "currency": "INR"}%s}
                """.formatted(from.publicId(), to.publicId(), amount,
                reference == null ? "" : ", \"clientReference\": \"" + reference + "\"");
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
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
