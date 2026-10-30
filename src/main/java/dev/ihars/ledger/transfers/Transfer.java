package dev.ihars.ledger.transfers;

import dev.ihars.ledger.shared.money.Money;
import java.time.Instant;

/**
 * A wallet-to-wallet transfer request and its outcome.
 *
 * @param id internal id; never exposed
 * @param clientId internal id of the API client that requested it
 * @param sourceAccountId public id of the debited wallet
 * @param destinationAccountId public id of the credited wallet
 * @param rejectionCode the business rule that refused a REJECTED transfer, else null
 * @param journalId public id of the posted journal, null for REJECTED
 */
public record Transfer(long id, String publicId, long clientId, String sourceAccountId, String destinationAccountId,
        Money amount, TransferStatus status, String rejectionCode, String journalId, String description,
        String clientReference, Instant createdAt) {

    public enum TransferStatus {
        POSTED,
        REJECTED,
        REVERSED
    }
}
