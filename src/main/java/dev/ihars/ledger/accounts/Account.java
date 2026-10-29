package dev.ihars.ledger.accounts;

import dev.ihars.ledger.ledger.domain.AccountStatus;
import dev.ihars.ledger.ledger.domain.AccountType;
import dev.ihars.ledger.ledger.domain.Direction;
import dev.ihars.ledger.ledger.domain.OverdraftPolicy;
import dev.ihars.ledger.shared.money.CurrencyUnit;
import java.time.Instant;

/**
 * An account in the chart of accounts.
 *
 * @param id internal id; never exposed outside the service
 * @param systemCode set only for system accounts, e.g. {@code LIABILITY.SUSPENSE}
 */
public record Account(long id, String publicId, String systemCode, String name, AccountType type,
        Direction normalBalance, CurrencyUnit currency, OverdraftPolicy overdraftPolicy, AccountStatus status,
        Instant createdAt) {}
