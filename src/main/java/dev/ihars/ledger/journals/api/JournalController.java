package dev.ihars.ledger.journals.api;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.accounts.AccountService;
import dev.ihars.ledger.idempotency.IdempotencyService;
import dev.ihars.ledger.idempotency.IdempotentRequest;
import dev.ihars.ledger.idempotency.Outcome;
import dev.ihars.ledger.ledger.domain.Direction;
import dev.ihars.ledger.ledger.domain.JournalDraft;
import dev.ihars.ledger.ledger.domain.JournalType;
import dev.ihars.ledger.ledger.posting.PostedJournal;
import dev.ihars.ledger.ledger.posting.PostingContext;
import dev.ihars.ledger.ledger.posting.PostingEngine;
import dev.ihars.ledger.ledger.query.JournalView;
import dev.ihars.ledger.ledger.query.LedgerEntryView;
import dev.ihars.ledger.ledger.query.LedgerQueries;
import dev.ihars.ledger.security.ApiClientPrincipal;
import dev.ihars.ledger.shared.currency.CurrencyCatalog;
import dev.ihars.ledger.shared.error.ApiProblemException;
import dev.ihars.ledger.shared.error.ErrorCode;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.InvalidAmountException;
import dev.ihars.ledger.shared.money.Money;
import dev.ihars.ledger.shared.web.MoneyJson;
import dev.ihars.ledger.shared.web.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Journal lookup (OPERATOR, ADMIN) and opening balances (ADMIN). There is no endpoint that
 * writes arbitrary journals or raw entries.
 */
@RestController
@RequestMapping("/api/v1/journal-transactions")
@Tag(name = "Journals")
public class JournalController {

    private static final String BASE = "/api/v1/journal-transactions/";

    private final LedgerQueries ledger;
    private final PostingEngine engine;
    private final AccountService accounts;
    private final CurrencyCatalog currencies;
    private final IdempotencyService idempotency;

    public JournalController(LedgerQueries ledger, PostingEngine engine, AccountService accounts,
            CurrencyCatalog currencies, IdempotencyService idempotency) {
        this.ledger = ledger;
        this.engine = engine;
        this.accounts = accounts;
        this.currencies = currencies;
        this.idempotency = idempotency;
    }

    public record OpeningLine(@NotBlank String accountId, @NotNull Direction direction,
            @NotNull @Valid MoneyJson amount) {}

    public record OpeningRequest(
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull LocalDate effectiveDate,
            @NotBlank @Size(max = JournalDraft.MAX_REASON_LENGTH) String reason,
            @Size(max = JournalDraft.MAX_DESCRIPTION_LENGTH) String description,
            String externalReference,
            @NotNull @Size(min = 2, max = JournalDraft.MAX_LINES) List<@Valid @NotNull OpeningLine> lines) {}

    public record EntryResponse(int lineNo, String accountId, Direction direction, MoneyJson amount,
            long accountSequence, MoneyJson balanceAfter) {}

    public record JournalResponse(String id, JournalType type, String currency, MoneyJson total,
            LocalDate effectiveDate, Instant postedAt, String postedBy, String description, String reason,
            String externalReference, String requestId, String correlationId, List<EntryResponse> entries) {}

    @GetMapping("/{journalId}")
    @Operation(summary = "Journal header and entries (OPERATOR, ADMIN)")
    public JournalResponse get(@PathVariable String journalId) {
        return response(ledger.journal(journalId)
                .orElseThrow(() -> ApiProblemException.notFound("Journal not found.")));
    }

    @PostMapping("/openings")
    @Operation(summary = "Post opening balances (ADMIN)", description = """
            Reason required. Every non-system account in the journal must have no entries yet. Backdating is \
            allowed; future business dates are rejected. Requires Idempotency-Key.""")
    public ResponseEntity<String> postOpening(@Valid @RequestBody OpeningRequest request,
            @RequestHeader(name = IdempotencyService.KEY_HEADER, required = false) String key,
            @AuthenticationPrincipal ApiClientPrincipal caller) {
        JournalDraft draft = draft(request);
        PostingContext context = new PostingContext(caller.actor(), RequestContext.requestId(),
                RequestContext.correlationId());
        return idempotency.execute(new IdempotentRequest(caller.clientId(), "POST_OPENING", key, request,
                id -> URI.create(BASE + id)), () -> {
                    PostedJournal posted = engine.postOpening(draft, context);
                    return Outcome.created(response(ledger.journal(posted.publicId()).orElseThrow()),
                            posted.publicId());
                });
    }

    private JournalDraft draft(OpeningRequest request) {
        CurrencyUnit currency = currencies.findActive(request.currency())
                .orElseThrow(() -> new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT,
                        ErrorCode.UNSUPPORTED_CURRENCY, "Currency is not supported."));
        JournalDraft.Builder builder = JournalDraft.builder(JournalType.OPENING, currency, request.effectiveDate())
                .reason(request.reason().strip())
                .description(request.description())
                .externalReference(request.externalReference());
        for (OpeningLine line : request.lines()) {
            if (!currency.code().equals(line.amount().currency())) {
                throw new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT, ErrorCode.CURRENCY_MISMATCH,
                        "Every line amount must be in the journal currency.");
            }
            Account account = accounts.findByPublicId(line.accountId())
                    .orElseThrow(() -> new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT,
                            ErrorCode.ACCOUNT_NOT_FOUND, "Unknown account " + line.accountId() + "."));
            builder = line.direction() == Direction.DEBIT
                    ? builder.debit(account.id(), amount(line.amount(), currency))
                    : builder.credit(account.id(), amount(line.amount(), currency));
        }
        return builder.build();
    }

    private static Money amount(MoneyJson json, CurrencyUnit currency) {
        try {
            return Money.parsePositive(json.value(), currency);
        } catch (InvalidAmountException e) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "Amounts must be positive decimal strings with at most " + currency.minorUnits()
                            + " decimal places.");
        }
    }

    private static JournalResponse response(JournalView journal) {
        List<EntryResponse> entries = journal.entries().stream().map(JournalController::entry).toList();
        return new JournalResponse(journal.publicId(), journal.type(), journal.currency().code(),
                MoneyJson.of(journal.total()), journal.effectiveDate(), journal.postedAt(), journal.postedBy(),
                journal.description(), journal.reason(), journal.externalReference(), journal.requestId(),
                journal.correlationId(), entries);
    }

    private static EntryResponse entry(LedgerEntryView e) {
        return new EntryResponse(e.lineNo(), e.accountPublicId(), e.direction(), MoneyJson.of(e.amount()),
                e.accountSeq(), MoneyJson.of(e.balanceAfter()));
    }
}
