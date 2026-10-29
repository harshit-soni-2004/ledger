package dev.ihars.ledger.accounts;

import dev.ihars.ledger.accounts.AccountRejectedException.Code;
import dev.ihars.ledger.accounts.infrastructure.JdbcAccountRepository;
import dev.ihars.ledger.audit.Actor;
import dev.ihars.ledger.audit.AuditEvent;
import dev.ihars.ledger.audit.AuditTrail;
import dev.ihars.ledger.ledger.domain.AccountSnapshot;
import dev.ihars.ledger.ledger.domain.AccountStatus;
import dev.ihars.ledger.ledger.posting.LedgerAccounts;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freezes, unfreezes and closes accounts. CLOSED is terminal.
 *
 * <p>Each change locks the account's balance row first, the same lock postings take, then
 * checks the status and balance under that lock and updates the status with a guard on the
 * expected value. A posting and a lifecycle change on the same account therefore serialize: a
 * posting that waited sees the new status, and a close never races a posting that would leave
 * a non-zero balance.
 */
@Service
public class AccountLifecycleService {

    public static final String ACCOUNT_FROZEN = "ACCOUNT_FROZEN";
    public static final String ACCOUNT_UNFROZEN = "ACCOUNT_UNFROZEN";
    public static final String ACCOUNT_CLOSED = "ACCOUNT_CLOSED";
    public static final int MAX_REASON_LENGTH = 255;

    private final JdbcAccountRepository accounts;
    private final LedgerAccounts ledger;
    private final AuditTrail audit;
    private final Clock clock;

    public AccountLifecycleService(JdbcAccountRepository accounts, LedgerAccounts ledger, AuditTrail audit,
            Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional
    public Account freeze(String publicId, String reason, Actor actor, String requestId) {
        return transition(publicId, Set.of(AccountStatus.ACTIVE), AccountStatus.FROZEN, ACCOUNT_FROZEN, reason, actor,
                requestId);
    }

    @Transactional
    public Account unfreeze(String publicId, String reason, Actor actor, String requestId) {
        return transition(publicId, Set.of(AccountStatus.FROZEN), AccountStatus.ACTIVE, ACCOUNT_UNFROZEN, reason, actor,
                requestId);
    }

    /** Closes an ACTIVE or FROZEN account whose balance is exactly zero. */
    @Transactional
    public Account close(String publicId, String reason, Actor actor, String requestId) {
        return transition(publicId, Set.of(AccountStatus.ACTIVE, AccountStatus.FROZEN), AccountStatus.CLOSED,
                ACCOUNT_CLOSED, reason, actor, requestId);
    }

    private Account transition(String publicId, Set<AccountStatus> from, AccountStatus target, String action,
            String rawReason, Actor actor, String requestId) {
        AuditEvent.requireTraceId("requestId", requestId);
        String reason = validReason(rawReason);
        Account account = accounts.findByPublicId(publicId)
                .orElseThrow(() -> new AccountRejectedException(Code.ACCOUNT_NOT_FOUND, "Account not found"));
        if (account.isSystem()) {
            throw new AccountRejectedException(Code.SYSTEM_ACCOUNT, "System accounts cannot change status");
        }
        AccountSnapshot locked = ledger.lockForLifecycle(account.id())
                .orElseThrow(() -> new IllegalStateException("Account " + account.id() + " has no balance row"));
        if (!from.contains(locked.status())) {
            throw new AccountRejectedException(Code.STATUS_CONFLICT,
                    "Account is " + locked.status() + "; it cannot become " + target);
        }
        if (target == AccountStatus.CLOSED && !locked.balance().isZero()) {
            throw new AccountRejectedException(Code.BALANCE_NOT_ZERO,
                    "Only an account with a zero balance can be closed; balance is " + locked.balance());
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (!ledger.changeStatus(account.id(), locked.status(), target, now)) {
            throw new IllegalStateException("Status of account " + account.id() + " changed while locked");
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", locked.status().name());
        payload.put("to", target.name());
        payload.put("reason", reason);
        audit.append(new AuditEvent(actor, action, "ACCOUNT", account.publicId(), requestId, null, payload));
        return new Account(account.id(), account.publicId(), account.systemCode(), account.ownerClientId(),
                account.name(), account.type(), account.normalBalance(), account.currency(),
                account.overdraftPolicy(), target, account.createdAt());
    }

    private static String validReason(String raw) {
        String reason = raw == null ? "" : raw.strip();
        if (reason.isEmpty() || reason.length() > MAX_REASON_LENGTH
                || reason.chars().anyMatch(Character::isISOControl)) {
            throw new AccountRejectedException(Code.REASON_REQUIRED,
                    "A reason of 1-" + MAX_REASON_LENGTH + " characters without control characters is required");
        }
        return reason;
    }
}
