package dev.ihars.ledger.accounts.api;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.accounts.AccountLifecycleService;
import dev.ihars.ledger.accounts.AccountService;
import dev.ihars.ledger.accounts.OpenAccountCommand;
import dev.ihars.ledger.idempotency.IdempotencyService;
import dev.ihars.ledger.idempotency.IdempotentRequest;
import dev.ihars.ledger.idempotency.Outcome;
import dev.ihars.ledger.ledger.domain.AccountStatus;
import dev.ihars.ledger.ledger.domain.AccountType;
import dev.ihars.ledger.ledger.domain.Direction;
import dev.ihars.ledger.ledger.domain.JournalType;
import dev.ihars.ledger.ledger.domain.OverdraftPolicy;
import dev.ihars.ledger.ledger.query.AccountBalance;
import dev.ihars.ledger.ledger.query.LedgerEntryView;
import dev.ihars.ledger.ledger.query.LedgerQueries;
import dev.ihars.ledger.security.ApiClient;
import dev.ihars.ledger.security.ApiClientPrincipal;
import dev.ihars.ledger.security.ApiClientService;
import dev.ihars.ledger.security.ApiRole;
import dev.ihars.ledger.shared.error.ApiProblemException;
import dev.ihars.ledger.shared.error.ErrorCode;
import dev.ihars.ledger.shared.money.Money;
import dev.ihars.ledger.shared.web.MoneyJson;
import dev.ihars.ledger.shared.web.Page;
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
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Accounts, balances and entries. A CLIENT sees only the accounts it owns; any other account,
 * including system accounts, is reported as not found so ids cannot be probed.
 */
@RestController
@RequestMapping("/api/v1/accounts")
@Tag(name = "Accounts")
public class AccountController {

    static final String BASE = "/api/v1/accounts/";
    private static final Function<String, URI> LOCATION = id -> URI.create(BASE + id);

    private final AccountService accounts;
    private final AccountLifecycleService lifecycle;
    private final LedgerQueries ledger;
    private final IdempotencyService idempotency;
    private final AccountOpeningPolicy policy;
    private final ApiClientService clients;

    public AccountController(AccountService accounts, AccountLifecycleService lifecycle, LedgerQueries ledger,
            IdempotencyService idempotency, AccountOpeningPolicy policy, ApiClientService clients) {
        this.accounts = accounts;
        this.lifecycle = lifecycle;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.policy = policy;
        this.clients = clients;
    }

    public record CreateAccountRequest(
            @NotBlank @Size(max = AccountService.MAX_NAME_LENGTH) String name,
            @NotNull AccountType type,
            @NotNull @Pattern(regexp = "[A-Z]{3}") String currency,
            OverdraftPolicy.Kind overdraftPolicy,
            @Valid MoneyJson overdraftLimit,
            String ownerClientId) {

        OverdraftPolicy.Kind overdraft() {
            return overdraftPolicy == null ? OverdraftPolicy.Kind.FORBIDDEN : overdraftPolicy;
        }
    }

    public record LifecycleRequest(@NotBlank @Size(max = AccountLifecycleService.MAX_REASON_LENGTH) String reason) {}

    public record AccountResponse(String id, String name, AccountType type, Direction normalBalance, String currency,
            OverdraftPolicy.Kind overdraftPolicy, MoneyJson overdraftLimit, AccountStatus status, String systemCode,
            String ownerClientId, Instant createdAt) {}

    public record BalanceResponse(String accountId, String currency, LocalDate asOf, MoneyJson ledgerBalance,
            MoneyJson availableBalance, Long lastEntrySequence) {}

    public record EntryResponse(long sequence, String journalId, JournalType journalType, Direction direction,
            MoneyJson amount, MoneyJson balanceAfter, LocalDate effectiveDate, Instant postedAt) {}

    @PostMapping
    @Operation(summary = "Open an account", description = """
            CLIENT: own LIABILITY wallets (overdraft FORBIDDEN). OPERATOR: the same wallets for ownerClientId. \
            ADMIN: any type, overdraft FORBIDDEN or LIMITED. UNLIMITED is never allowed. Requires Idempotency-Key.""")
    public ResponseEntity<String> create(@Valid @RequestBody CreateAccountRequest request,
            @RequestHeader(name = IdempotencyService.KEY_HEADER, required = false) String key,
            @AuthenticationPrincipal ApiClientPrincipal caller) {
        Long owner = policy.authorize(caller, request.type(), request.overdraft(), request.ownerClientId());
        String limit = overdraftLimit(request);
        OpenAccountCommand command = new OpenAccountCommand(request.name(), request.type(), request.currency(),
                request.overdraft(), limit);
        return idempotency.execute(new IdempotentRequest(caller.clientId(), "OPEN_ACCOUNT", key, request, LOCATION),
                () -> {
                    Account account = accounts.open(command, owner, caller.actor(), RequestContext.requestId());
                    return Outcome.created(response(account), account.publicId());
                });
    }

    @GetMapping
    @Operation(summary = "List accounts in creation order",
            description = "CLIENT: own accounts only. OPERATOR/ADMIN: all, or one owner's with ownerClientId.")
    public Page<AccountResponse> list(@RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) String ownerClientId,
            @AuthenticationPrincipal ApiClientPrincipal caller) {
        int size = Page.limit(limit);
        Long owner = listOwner(caller, ownerClientId);
        Account after = null;
        String position = Page.decodeCursor(cursor);
        if (position != null) {
            after = accounts.findByPublicId(position)
                    .filter(a -> owner == null || owner.equals(a.ownerClientId()))
                    .orElseThrow(Page::invalidCursor);
        }
        List<Account> found = accounts.page(owner, after, size + 1);
        List<Account> page = found.subList(0, Math.min(size, found.size()));
        String next = found.size() > size ? Page.encodeCursor(page.getLast().publicId()) : null;
        return new Page<>(page.stream().map(this::response).toList(), next);
    }

    @GetMapping("/{accountId}")
    @Operation(summary = "Account details")
    public AccountResponse get(@PathVariable String accountId, @AuthenticationPrincipal ApiClientPrincipal caller) {
        return response(visible(accountId, caller));
    }

    @GetMapping("/{accountId}/balance")
    @Operation(summary = "Balance", description = """
            Without asOf: current ledger and available balance from the projection. With asOf (a business \
            date in the configured reporting zone): the sum of entries with effectiveDate <= asOf.""")
    public BalanceResponse balance(@PathVariable String accountId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf,
            @AuthenticationPrincipal ApiClientPrincipal caller) {
        Account account = visible(accountId, caller);
        if (asOf != null) {
            Money balance = ledger.balanceAsOf(account.id(), asOf).orElseThrow();
            return new BalanceResponse(account.publicId(), account.currency().code(), asOf, MoneyJson.of(balance),
                    null, null);
        }
        AccountBalance balance = ledger.balance(account.id()).orElseThrow();
        return new BalanceResponse(account.publicId(), account.currency().code(), null,
                MoneyJson.of(balance.ledgerBalance()), balance.available().map(MoneyJson::of).orElse(null),
                balance.lastEntrySeq());
    }

    @GetMapping("/{accountId}/entries")
    @Operation(summary = "Entries, newest first", description = "Keyset pagination: limit (max 200) and cursor.")
    public Page<EntryResponse> entries(@PathVariable String accountId, @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor, @AuthenticationPrincipal ApiClientPrincipal caller) {
        Account account = visible(accountId, caller);
        int size = Page.limit(limit);
        Long before = sequenceCursor(Page.decodeCursor(cursor));
        List<LedgerEntryView> found = ledger.entriesNewestFirst(account.id(), before, size + 1);
        List<LedgerEntryView> page = found.subList(0, Math.min(size, found.size()));
        String next = found.size() > size ? Page.encodeCursor("seq:" + page.getLast().accountSeq()) : null;
        return new Page<>(page.stream().map(AccountController::entry).toList(), next);
    }

    @PostMapping("/{accountId}/freeze")
    @Operation(summary = "Freeze an ACTIVE account (OPERATOR, ADMIN)", description = "Requires Idempotency-Key.")
    public ResponseEntity<String> freeze(@PathVariable String accountId, @Valid @RequestBody LifecycleRequest request,
            @RequestHeader(name = IdempotencyService.KEY_HEADER, required = false) String key,
            @AuthenticationPrincipal ApiClientPrincipal caller) {
        return changeStatus("FREEZE_ACCOUNT", accountId, request, key, caller,
                () -> lifecycle.freeze(accountId, request.reason(), caller.actor(), RequestContext.requestId()));
    }

    @PostMapping("/{accountId}/unfreeze")
    @Operation(summary = "Unfreeze a FROZEN account (ADMIN)", description = "Requires Idempotency-Key.")
    public ResponseEntity<String> unfreeze(@PathVariable String accountId,
            @Valid @RequestBody LifecycleRequest request,
            @RequestHeader(name = IdempotencyService.KEY_HEADER, required = false) String key,
            @AuthenticationPrincipal ApiClientPrincipal caller) {
        return changeStatus("UNFREEZE_ACCOUNT", accountId, request, key, caller,
                () -> lifecycle.unfreeze(accountId, request.reason(), caller.actor(), RequestContext.requestId()));
    }

    @PostMapping("/{accountId}/close")
    @Operation(summary = "Close a zero-balance account (ADMIN); terminal", description = "Requires Idempotency-Key.")
    public ResponseEntity<String> close(@PathVariable String accountId, @Valid @RequestBody LifecycleRequest request,
            @RequestHeader(name = IdempotencyService.KEY_HEADER, required = false) String key,
            @AuthenticationPrincipal ApiClientPrincipal caller) {
        return changeStatus("CLOSE_ACCOUNT", accountId, request, key, caller,
                () -> lifecycle.close(accountId, request.reason(), caller.actor(), RequestContext.requestId()));
    }

    private record LifecycleFingerprint(String accountId, String reason) {}

    private ResponseEntity<String> changeStatus(String operation, String accountId, LifecycleRequest request,
            String key, ApiClientPrincipal caller, Supplier<Account> change) {
        return idempotency.execute(new IdempotentRequest(caller.clientId(), operation, key,
                new LifecycleFingerprint(accountId, request.reason()), null), () -> {
                    Account account = change.get();
                    return Outcome.ok(response(account), account.publicId());
                });
    }

    private Account visible(String publicId, ApiClientPrincipal caller) {
        return accounts.findByPublicId(publicId)
                .filter(a -> !caller.is(ApiRole.CLIENT) || Long.valueOf(caller.clientId()).equals(a.ownerClientId()))
                .orElseThrow(() -> new ApiProblemException(HttpStatus.NOT_FOUND, ErrorCode.ACCOUNT_NOT_FOUND,
                        "Account not found."));
    }

    private Long listOwner(ApiClientPrincipal caller, String ownerClientId) {
        if (caller.is(ApiRole.CLIENT)) {
            if (ownerClientId != null && !ownerClientId.equals(caller.clientPublicId())) {
                throw new ApiProblemException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                        "Clients may list only their own accounts.");
            }
            return caller.clientId();
        }
        if (ownerClientId == null) {
            return null;
        }
        return clients.findClient(ownerClientId).map(ApiClient::id)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                        "Unknown ownerClientId."));
    }

    private static Long sequenceCursor(String position) {
        if (position == null) {
            return null;
        }
        if (!position.startsWith("seq:")) {
            throw Page.invalidCursor();
        }
        try {
            long seq = Long.parseLong(position.substring(4));
            if (seq < 1) {
                throw Page.invalidCursor();
            }
            return seq;
        } catch (NumberFormatException e) {
            throw Page.invalidCursor();
        }
    }

    private static String overdraftLimit(CreateAccountRequest request) {
        MoneyJson limit = request.overdraftLimit();
        if (limit == null) {
            return null;
        }
        if (limit.value() == null || !request.currency().equals(limit.currency())) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "overdraftLimit must have a string value in the account currency.");
        }
        return limit.value();
    }

    private AccountResponse response(Account account) {
        String owner = account.ownerClientId() == null ? null
                : clients.findClientById(account.ownerClientId()).map(ApiClient::publicId).orElse(null);
        return new AccountResponse(account.publicId(), account.name(), account.type(), account.normalBalance(),
                account.currency().code(), account.overdraftPolicy().kind(),
                MoneyJson.of(account.overdraftPolicy().limit()), account.status(), account.systemCode(), owner,
                account.createdAt());
    }

    private static EntryResponse entry(LedgerEntryView e) {
        return new EntryResponse(e.accountSeq(), e.journalPublicId(), e.journalType(), e.direction(),
                MoneyJson.of(e.amount()), MoneyJson.of(e.balanceAfter()), e.effectiveDate(), e.postedAt());
    }
}
