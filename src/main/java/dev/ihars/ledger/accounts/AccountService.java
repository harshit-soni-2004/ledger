package dev.ihars.ledger.accounts;

import dev.ihars.ledger.accounts.AccountRejectedException.Code;
import dev.ihars.ledger.accounts.infrastructure.JdbcAccountRepository;
import dev.ihars.ledger.audit.Actor;
import dev.ihars.ledger.audit.AuditEvent;
import dev.ihars.ledger.audit.AuditTrail;
import dev.ihars.ledger.ledger.domain.OverdraftPolicy;
import dev.ihars.ledger.ledger.posting.LedgerAccounts;
import dev.ihars.ledger.shared.currency.CurrencyCatalog;
import dev.ihars.ledger.shared.id.PublicIdGenerator;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import dev.ihars.ledger.shared.money.InvalidAmountException;
import dev.ihars.ledger.shared.money.Money;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opens and looks up accounts. System accounts are created only by migrations. */
@Service
public class AccountService {

    public static final String ACCOUNT_OPENED = "ACCOUNT_OPENED";
    public static final int MAX_NAME_LENGTH = 128;

    private final JdbcAccountRepository accounts;
    private final LedgerAccounts ledger;
    private final CurrencyCatalog currencies;
    private final AuditTrail audit;
    private final PublicIdGenerator ids;
    private final Clock clock;

    public AccountService(JdbcAccountRepository accounts, LedgerAccounts ledger, CurrencyCatalog currencies,
            AuditTrail audit, PublicIdGenerator ids, Clock clock) {
        this.accounts = accounts;
        this.ledger = ledger;
        this.currencies = currencies;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Opens an account with a zero balance. The normal balance follows from the type, and the
     * balance floor from the overdraft policy.
     *
     * @throws AccountRejectedException if the command is invalid; nothing is written
     */
    @Transactional
    public Account open(OpenAccountCommand command, Actor actor, String requestId) {
        AuditEvent.requireTraceId("requestId", requestId);
        String name = validName(command.name());
        if (command.type() == null) {
            throw new AccountRejectedException(Code.INVALID_TYPE, "Account type is required");
        }
        CurrencyUnit currency = currencies.findActive(command.currency())
                .orElseThrow(() -> new AccountRejectedException(Code.UNSUPPORTED_CURRENCY,
                        "Currency is not supported: " + command.currency()));
        OverdraftPolicy policy = overdraftPolicy(command, currency);

        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Account account = accounts.insert(ids.next(PublicIdGenerator.Prefix.ACCOUNT), name, command.type(),
                currency, policy, now);
        ledger.register(account.id(), currency, policy.minBalance(currency).orElse(null), now);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", account.type().name());
        payload.put("currency", currency.code());
        payload.put("overdraftPolicy", policy.kind().name());
        if (policy.limit() != null) {
            payload.put("overdraftLimit", policy.limit().toPlainString());
        }
        audit.append(new AuditEvent(actor, ACCOUNT_OPENED, "ACCOUNT", account.publicId(), requestId, null, payload));
        return account;
    }

    @Transactional(readOnly = true)
    public Optional<Account> findByPublicId(String publicId) {
        return accounts.findByPublicId(publicId);
    }

    @Transactional(readOnly = true)
    public Optional<Account> findById(long id) {
        return accounts.findById(id);
    }

    /** A system account seeded by migration, e.g. {@code ("LIABILITY.SUSPENSE", "INR")}. */
    @Transactional(readOnly = true)
    public Optional<Account> findSystemAccount(String systemCode, String currency) {
        return accounts.findSystemAccount(systemCode, currency);
    }

    private static String validName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || name.chars().anyMatch(Character::isISOControl)) {
            throw new AccountRejectedException(Code.INVALID_NAME,
                    "Name must be 1-" + MAX_NAME_LENGTH + " characters without control characters");
        }
        return name;
    }

    private static OverdraftPolicy overdraftPolicy(OpenAccountCommand command, CurrencyUnit currency) {
        if (command.overdraft() == null) {
            throw new AccountRejectedException(Code.INVALID_OVERDRAFT, "Overdraft policy is required");
        }
        if (command.overdraft() != OverdraftPolicy.Kind.LIMITED) {
            if (command.overdraftLimit() != null) {
                throw new AccountRejectedException(Code.INVALID_OVERDRAFT,
                        "An overdraft limit is allowed only with the LIMITED policy");
            }
            return new OverdraftPolicy(command.overdraft(), null);
        }
        try {
            return OverdraftPolicy.limited(Money.parsePositive(command.overdraftLimit(), currency));
        } catch (InvalidAmountException e) {
            throw new AccountRejectedException(Code.INVALID_OVERDRAFT, "Invalid overdraft limit: " + e.getMessage(), e);
        }
    }
}
