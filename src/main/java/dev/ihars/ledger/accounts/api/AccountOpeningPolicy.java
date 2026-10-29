package dev.ihars.ledger.accounts.api;

import dev.ihars.ledger.ledger.domain.AccountType;
import dev.ihars.ledger.ledger.domain.OverdraftPolicy;
import dev.ihars.ledger.security.ApiClient;
import dev.ihars.ledger.security.ApiClientPrincipal;
import dev.ihars.ledger.security.ApiClientService;
import dev.ihars.ledger.security.ApiRole;
import dev.ihars.ledger.shared.error.ApiProblemException;
import dev.ihars.ledger.shared.error.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Who may open which accounts through the API (application-enforced; the service and the
 * database allow more, because migrations and tests need it):
 *
 * <ul>
 *   <li>CLIENT: LIABILITY wallets with overdraft FORBIDDEN, owned by itself.
 *   <li>OPERATOR: the same wallets, for a given owner (an active CLIENT).
 *   <li>ADMIN: any type with overdraft FORBIDDEN or LIMITED, optionally owned.
 *   <li>Nobody: UNLIMITED overdraft. Only migration-seeded system accounts have it.
 * </ul>
 */
@Component
class AccountOpeningPolicy {

    private final ApiClientService clients;

    AccountOpeningPolicy(ApiClientService clients) {
        this.clients = clients;
    }

    /** Checks the request and returns the internal id of the owner, or null for unowned. */
    Long authorize(ApiClientPrincipal caller, AccountType type, OverdraftPolicy.Kind overdraft, String ownerClientId) {
        if (overdraft == OverdraftPolicy.Kind.UNLIMITED) {
            throw forbidden("Accounts with an unlimited overdraft cannot be opened through the API.");
        }
        boolean wallet = type == AccountType.LIABILITY && overdraft == OverdraftPolicy.Kind.FORBIDDEN;
        return switch (caller.role()) {
            case CLIENT -> {
                if (!wallet) {
                    throw forbidden("Clients may open only LIABILITY wallets with overdraft FORBIDDEN.");
                }
                if (ownerClientId != null && !ownerClientId.equals(caller.clientPublicId())) {
                    throw forbidden("Clients may open accounts only for themselves.");
                }
                yield caller.clientId();
            }
            case OPERATOR -> {
                if (!wallet) {
                    throw forbidden("Operators may open only LIABILITY wallets with overdraft FORBIDDEN.");
                }
                if (ownerClientId == null) {
                    throw new ApiProblemException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                            "ownerClientId is required.");
                }
                yield owner(ownerClientId);
            }
            case ADMIN -> ownerClientId == null ? null : owner(ownerClientId);
        };
    }

    private long owner(String publicId) {
        return clients.findClient(publicId)
                .filter(c -> c.role() == ApiRole.CLIENT && c.status() == ApiClient.Status.ACTIVE)
                .map(ApiClient::id)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                        "ownerClientId must be an active client with role CLIENT."));
    }

    private static ApiProblemException forbidden(String detail) {
        return new ApiProblemException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN, detail);
    }
}
