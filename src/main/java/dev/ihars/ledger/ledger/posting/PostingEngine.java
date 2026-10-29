package dev.ihars.ledger.ledger.posting;

import dev.ihars.ledger.audit.AuditEvent;
import dev.ihars.ledger.audit.AuditTrail;
import dev.ihars.ledger.ledger.domain.AccountSnapshot;
import dev.ihars.ledger.ledger.domain.JournalDraft;
import dev.ihars.ledger.ledger.domain.JournalLine;
import dev.ihars.ledger.ledger.domain.JournalRejectedException;
import dev.ihars.ledger.ledger.domain.JournalType;
import dev.ihars.ledger.ledger.domain.PostingCalculator;
import dev.ihars.ledger.ledger.domain.PostingPlan;
import dev.ihars.ledger.ledger.domain.PostingPlan.BalanceChange;
import dev.ihars.ledger.ledger.domain.PostingPlan.PlannedEntry;
import dev.ihars.ledger.ledger.infrastructure.JdbcLedgerWriter;
import dev.ihars.ledger.shared.id.PublicIdGenerator;
import dev.ihars.ledger.shared.time.BusinessCalendar;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The only component that writes journals, entries and balances (ADR-0005, ADR-0008).
 *
 * <p>One call is one database transaction (or part of the caller's): lock the balance rows in
 * ascending account id order, validate against the locked state, then insert the header, the
 * entries and the balance updates and append the audit event. Any failure rolls all of it back.
 */
@Service
public class PostingEngine {

    public static final String JOURNAL_POSTED = "JOURNAL_POSTED";

    private final JdbcLedgerWriter writer;
    private final AuditTrail audit;
    private final PublicIdGenerator ids;
    private final Clock clock;
    private final BusinessCalendar calendar;

    public PostingEngine(JdbcLedgerWriter writer, AuditTrail audit, PublicIdGenerator ids, Clock clock,
            BusinessCalendar calendar) {
        this.writer = writer;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
        this.calendar = calendar;
    }

    /**
     * Posts a journal atomically.
     *
     * @throws JournalRejectedException if an accounting rule rejects the journal; nothing is written
     */
    @Transactional
    public PostedJournal post(JournalDraft draft, PostingContext context) {
        if (draft.type() == JournalType.REVERSAL) {
            throw new JournalRejectedException(JournalRejectedException.Code.REVERSAL_NOT_SUPPORTED,
                    "Reversals are not available yet");
        }
        if (draft.type() == JournalType.OPENING) {
            throw new JournalRejectedException(JournalRejectedException.Code.OPENING_NOT_ALLOWED,
                    "Opening journals are posted with postOpening");
        }
        SortedSet<Long> accountIds = accountIds(draft);
        return post(draft, writer.lockAccounts(accountIds), context);
    }

    /**
     * Posts an opening balance. Every non-system account in the journal must have no entries
     * yet, checked under the balance locks, so an opening can only ever be the first entry of a
     * customer account. System accounts (bank, equity, suspense) may already have history.
     *
     * @throws JournalRejectedException with {@code OPENING_NOT_ALLOWED} if an account has entries
     */
    @Transactional
    public PostedJournal postOpening(JournalDraft draft, PostingContext context) {
        if (draft.type() != JournalType.OPENING) {
            throw new IllegalArgumentException("Not an opening journal: " + draft.type());
        }
        SortedSet<Long> accountIds = accountIds(draft);
        Map<Long, AccountSnapshot> locked = writer.lockAccounts(accountIds);
        Set<Long> system = writer.systemAccountIds(accountIds);
        for (AccountSnapshot account : locked.values()) {
            if (!system.contains(account.accountId()) && account.lastEntrySeq() != 0) {
                throw new JournalRejectedException(JournalRejectedException.Code.OPENING_NOT_ALLOWED,
                        "Account %d already has entries".formatted(account.accountId()));
            }
        }
        return post(draft, locked, context);
    }

    private static SortedSet<Long> accountIds(JournalDraft draft) {
        SortedSet<Long> accountIds = new TreeSet<>();
        for (JournalLine line : draft.lines()) {
            accountIds.add(line.accountId());
        }
        return accountIds;
    }

    private PostedJournal post(JournalDraft draft, Map<Long, AccountSnapshot> locked, PostingContext context) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        PostingPlan plan = PostingCalculator.plan(draft, locked, calendar.dateOf(now));

        String publicId = ids.next(PublicIdGenerator.Prefix.JOURNAL);
        long journalId = writer.insertJournal(publicId, draft, now, context.actor().id(), context.requestId(),
                context.correlationId());
        writer.insertEntries(journalId, draft, plan.entries(), now);
        for (BalanceChange change : plan.balanceChanges()) {
            writer.updateBalance(change, now);
        }
        audit.append(new AuditEvent(context.actor(), JOURNAL_POSTED, "JOURNAL", publicId, context.requestId(),
                context.correlationId(), auditPayload(draft)));

        List<PostedJournal.Entry> entries = plan.entries().stream()
                .map(PostingEngine::toPostedEntry)
                .toList();
        return new PostedJournal(journalId, publicId, draft.type(), draft.currency(), draft.total(),
                draft.effectiveDate(), now, entries);
    }

    private static PostedJournal.Entry toPostedEntry(PlannedEntry e) {
        return new PostedJournal.Entry(e.lineNo(), e.accountId(), e.direction(), e.amount(), e.accountSeq(),
                e.balanceAfter());
    }

    private static Map<String, Object> auditPayload(JournalDraft draft) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", draft.type().name());
        payload.put("currency", draft.currency().code());
        payload.put("total", draft.total().toPlainString());
        payload.put("effectiveDate", draft.effectiveDate().toString());
        payload.put("lines", draft.lines().size());
        if (draft.externalReference() != null) {
            payload.put("externalReference", draft.externalReference());
        }
        return payload;
    }
}
