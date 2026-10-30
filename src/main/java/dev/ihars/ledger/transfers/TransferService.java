package dev.ihars.ledger.transfers;

import dev.ihars.ledger.accounts.Account;
import dev.ihars.ledger.accounts.AccountService;
import dev.ihars.ledger.audit.AuditEvent;
import dev.ihars.ledger.audit.AuditTrail;
import dev.ihars.ledger.ledger.domain.AccountType;
import dev.ihars.ledger.ledger.domain.JournalDraft;
import dev.ihars.ledger.ledger.domain.JournalRejectedException;
import dev.ihars.ledger.ledger.domain.JournalType;
import dev.ihars.ledger.ledger.posting.PostedJournal;
import dev.ihars.ledger.ledger.posting.PostingContext;
import dev.ihars.ledger.ledger.posting.PostingEngine;
import dev.ihars.ledger.shared.error.ApiProblemException;
import dev.ihars.ledger.shared.error.ErrorCode;
import dev.ihars.ledger.shared.id.PublicIdGenerator;
import dev.ihars.ledger.shared.money.InvalidAmountException;
import dev.ihars.ledger.shared.money.Money;
import dev.ihars.ledger.shared.time.BusinessCalendar;
import dev.ihars.ledger.transfers.Transfer.TransferStatus;
import dev.ihars.ledger.transfers.infrastructure.JdbcTransferRepository;
import dev.ihars.ledger.transfers.infrastructure.JdbcTransferRepository.NewTransfer;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Same-currency transfers between customer wallets (LIABILITY accounts).
 *
 * <p>{@link #prepare} checks everything that does not depend on locked state: the accounts
 * exist, the caller owns the source, currencies match, the amount is valid. {@link #execute}
 * runs inside the caller's (idempotency) transaction and posts through the {@link PostingEngine},
 * which checks status and overdraft under the balance locks. A rejection from those checks is
 * committed as a REJECTED transfer, so retrying the same key returns the same answer instead of
 * succeeding later by accident (docs/14 Q3).
 */
@Service
public class TransferService {

    public static final String TRANSFER_REJECTED = "TRANSFER_REJECTED";

    /** Rejections that depend on locked account state and are recorded, not rolled back. */
    static final Set<JournalRejectedException.Code> RECORDED_REJECTIONS =
            EnumSet.of(JournalRejectedException.Code.INSUFFICIENT_FUNDS, JournalRejectedException.Code.ACCOUNT_NOT_ACTIVE);

    private final AccountService accounts;
    private final PostingEngine engine;
    private final JdbcTransferRepository transfers;
    private final AuditTrail audit;
    private final PublicIdGenerator ids;
    private final BusinessCalendar calendar;
    private final Clock clock;

    public TransferService(AccountService accounts, PostingEngine engine, JdbcTransferRepository transfers,
            AuditTrail audit, PublicIdGenerator ids, BusinessCalendar calendar, Clock clock) {
        this.accounts = accounts;
        this.engine = engine;
        this.transfers = transfers;
        this.audit = audit;
        this.ids = ids;
        this.calendar = calendar;
        this.clock = clock;
    }

    public record TransferCommand(String sourceAccountId, String destinationAccountId, String amount, String currency,
            String description, String clientReference) {}

    /** A command whose accounts and amount are resolved; produced only by {@link #prepare}. */
    public record PreparedTransfer(Account source, Account destination, Money amount, String description,
            String clientReference) {}

    public PreparedTransfer prepare(TransferCommand command, long clientId) {
        Account source = accounts.findByPublicId(command.sourceAccountId())
                .filter(a -> Long.valueOf(clientId).equals(a.ownerClientId()))
                .orElseThrow(() -> unprocessable(ErrorCode.ACCOUNT_NOT_FOUND, "Source account not found."));
        Account destination = accounts.findByPublicId(command.destinationAccountId())
                .filter(a -> !a.isSystem())
                .orElseThrow(() -> unprocessable(ErrorCode.ACCOUNT_NOT_FOUND, "Destination account not found."));
        if (source.id() == destination.id()) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "Source and destination accounts must differ.");
        }
        if (source.type() != AccountType.LIABILITY || destination.type() != AccountType.LIABILITY) {
            throw unprocessable(ErrorCode.TRANSFER_NOT_ALLOWED, "Transfers move money between customer wallets only.");
        }
        if (!source.currency().code().equals(command.currency())
                || !destination.currency().code().equals(command.currency())) {
            throw unprocessable(ErrorCode.CURRENCY_MISMATCH,
                    "The amount currency must equal the currency of both accounts.");
        }
        Money amount;
        try {
            amount = Money.parsePositive(command.amount(), source.currency());
        } catch (InvalidAmountException e) {
            throw new ApiProblemException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                    "amount must be a positive decimal string with at most " + source.currency().minorUnits()
                            + " decimal places.");
        }
        return new PreparedTransfer(source, destination, amount, command.description(), command.clientReference());
    }

    /**
     * Posts the transfer, or records why it was refused. Joins the caller's transaction; every
     * exception other than a recorded rejection rolls all of it back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Transfer execute(PreparedTransfer transfer, long clientId, String idempotencyKey, PostingContext context) {
        String publicId = ids.next(PublicIdGenerator.Prefix.TRANSFER);
        NewTransfer row = new NewTransfer(publicId, clientId, idempotencyKey, transfer.source().id(),
                transfer.destination().id(), transfer.amount(), transfer.description(), transfer.clientReference());
        JournalDraft draft = JournalDraft.builder(JournalType.TRANSFER, transfer.amount().currency(), calendar.today())
                .debit(transfer.source().id(), transfer.amount())
                .credit(transfer.destination().id(), transfer.amount())
                .description(transfer.description())
                .externalReference(transfer.clientReference())
                .build();
        try {
            PostedJournal posted = engine.post(draft, context);
            long id = transfers.insert(row, TransferStatus.POSTED, posted.id(), null, posted.postedAt());
            return transfer(id, row, transfer, TransferStatus.POSTED, null, posted.publicId(), posted.postedAt());
        } catch (JournalRejectedException e) {
            if (!RECORDED_REJECTIONS.contains(e.code())) {
                throw e;
            }
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            long id = transfers.insert(row, TransferStatus.REJECTED, null, e.code().name(), now);
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("rule", e.code().name());
            payload.put("sourceAccountId", transfer.source().publicId());
            payload.put("destinationAccountId", transfer.destination().publicId());
            payload.put("amount", transfer.amount().toPlainString());
            payload.put("currency", transfer.amount().currency().code());
            audit.append(new AuditEvent(context.actor(), TRANSFER_REJECTED, "TRANSFER", publicId,
                    context.requestId(), context.correlationId(), payload));
            return transfer(id, row, transfer, TransferStatus.REJECTED, e.code().name(), null, now);
        }
    }

    public Optional<Transfer> find(String publicId) {
        return transfers.findByPublicId(publicId);
    }

    public List<Transfer> page(Long clientId, TransferStatus status, String clientReference, Transfer after,
            int limit) {
        return transfers.page(clientId, status, clientReference, after == null ? null : after.id(), limit);
    }

    private static Transfer transfer(long id, NewTransfer row, PreparedTransfer transfer, TransferStatus status,
            String rejectionCode, String journalId, Instant createdAt) {
        return new Transfer(id, row.publicId(), row.clientId(), transfer.source().publicId(),
                transfer.destination().publicId(), transfer.amount(), status, rejectionCode, journalId,
                transfer.description(), transfer.clientReference(), createdAt);
    }

    private static ApiProblemException unprocessable(ErrorCode code, String detail) {
        return new ApiProblemException(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
    }
}
