package dev.ihars.ledger.transfers.api;

import dev.ihars.ledger.idempotency.IdempotencyService;
import dev.ihars.ledger.idempotency.IdempotentRequest;
import dev.ihars.ledger.idempotency.Outcome;
import dev.ihars.ledger.ledger.posting.PostingContext;
import dev.ihars.ledger.security.ApiClient;
import dev.ihars.ledger.security.ApiClientPrincipal;
import dev.ihars.ledger.security.ApiClientService;
import dev.ihars.ledger.security.ApiRole;
import dev.ihars.ledger.shared.error.ApiProblemException;
import dev.ihars.ledger.shared.error.ErrorCode;
import dev.ihars.ledger.shared.error.Problems;
import dev.ihars.ledger.shared.tx.LockContentionRetry;
import dev.ihars.ledger.shared.web.MoneyJson;
import dev.ihars.ledger.shared.web.Page;
import dev.ihars.ledger.shared.web.RequestContext;
import dev.ihars.ledger.transfers.Transfer;
import dev.ihars.ledger.transfers.Transfer.TransferStatus;
import dev.ihars.ledger.transfers.TransferService;
import dev.ihars.ledger.transfers.TransferService.PreparedTransfer;
import dev.ihars.ledger.transfers.TransferService.TransferCommand;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Wallet-to-wallet transfers. A CLIENT transfers from wallets it owns and sees only its own
 * transfers; OPERATOR and ADMIN may read all of them.
 */
@RestController
@RequestMapping("/api/v1/transfers")
@Tag(name = "Transfers")
public class TransferController {

    static final String BASE = "/api/v1/transfers/";
    static final String OPERATION = "CREATE_TRANSFER";

    private final TransferService transfers;
    private final IdempotencyService idempotency;
    private final LockContentionRetry retry;
    private final ApiClientService clients;

    public TransferController(TransferService transfers, IdempotencyService idempotency, LockContentionRetry retry,
            ApiClientService clients) {
        this.transfers = transfers;
        this.idempotency = idempotency;
        this.retry = retry;
        this.clients = clients;
    }

    public record CreateTransferRequest(
            @NotBlank String sourceAccountId,
            @NotBlank String destinationAccountId,
            @NotNull @Valid MoneyJson amount,
            @Size(max = 255) String description,
            @Pattern(regexp = "[A-Za-z0-9._:-]{1,64}") String clientReference) {}

    public record TransferResponse(String id, TransferStatus status, String rejectionCode, String journalTransactionId,
            String sourceAccountId, String destinationAccountId, MoneyJson amount, String description,
            String clientReference, Instant createdAt) {}

    @PostMapping
    @Operation(summary = "Transfer between wallets (CLIENT, from an owned wallet)", description = """
            Same currency only. Requires Idempotency-Key. Insufficient funds or an inactive account is answered \
            with 422 and recorded as a REJECTED transfer; retrying the same key replays that answer.""")
    public ResponseEntity<String> create(@Valid @RequestBody CreateTransferRequest request,
            @RequestHeader(name = IdempotencyService.KEY_HEADER, required = false) String key,
            @AuthenticationPrincipal ApiClientPrincipal caller) {
        PreparedTransfer prepared = transfers.prepare(new TransferCommand(request.sourceAccountId(),
                request.destinationAccountId(), request.amount().value(), request.amount().currency(),
                request.description(), request.clientReference()), caller.clientId());
        PostingContext context = new PostingContext(caller.actor(), RequestContext.requestId(),
                RequestContext.correlationId());
        IdempotentRequest idempotent = new IdempotentRequest(caller.clientId(), OPERATION, key, request,
                id -> URI.create(BASE + id));
        return retry.run(OPERATION, () -> idempotency.execute(idempotent, () -> {
            Transfer transfer = transfers.execute(prepared, caller.clientId(), key, context);
            if (transfer.status() == TransferStatus.REJECTED) {
                return Outcome.rejected(rejection(transfer), transfer.publicId());
            }
            return Outcome.created(response(transfer), transfer.publicId());
        }));
    }

    @GetMapping("/{transferId}")
    @Operation(summary = "Transfer details", description = "CLIENT: own transfers only.")
    public TransferResponse get(@PathVariable String transferId, @AuthenticationPrincipal ApiClientPrincipal caller) {
        return response(transfers.find(transferId)
                .filter(t -> !caller.is(ApiRole.CLIENT) || t.clientId() == caller.clientId())
                .orElseThrow(() -> ApiProblemException.notFound("Transfer not found.")));
    }

    @GetMapping
    @Operation(summary = "List transfers, newest first", description = """
            CLIENT: own transfers. OPERATOR/ADMIN: all, or one client's with clientId. Optional filters status and \
            clientReference (look up the outcome of a request whose response was lost). Keyset pagination.""")
    public Page<TransferResponse> list(@RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor, @RequestParam(required = false) TransferStatus status,
            @RequestParam(required = false) String clientReference, @RequestParam(required = false) String clientId,
            @AuthenticationPrincipal ApiClientPrincipal caller) {
        int size = Page.limit(limit);
        Long owner = listClient(caller, clientId);
        Transfer after = null;
        String position = Page.decodeCursor(cursor);
        if (position != null) {
            after = transfers.find(position)
                    .filter(t -> owner == null || owner == t.clientId())
                    .orElseThrow(Page::invalidCursor);
        }
        List<Transfer> found = transfers.page(owner, status, clientReference, after, size + 1);
        List<Transfer> page = found.subList(0, Math.min(size, found.size()));
        String next = found.size() > size ? Page.encodeCursor(page.getLast().publicId()) : null;
        return new Page<>(page.stream().map(TransferController::response).toList(), next);
    }

    private Long listClient(ApiClientPrincipal caller, String clientId) {
        if (caller.is(ApiRole.CLIENT)) {
            if (clientId != null && !clientId.equals(caller.clientPublicId())) {
                throw new ApiProblemException(HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN,
                        "Clients may list only their own transfers.");
            }
            return caller.clientId();
        }
        if (clientId == null) {
            return null;
        }
        return clients.findClient(clientId).map(ApiClient::id)
                .orElseThrow(() -> new ApiProblemException(HttpStatus.BAD_REQUEST, ErrorCode.VALIDATION_FAILED,
                        "Unknown clientId."));
    }

    private static Map<String, Object> rejection(Transfer transfer) {
        ErrorCode code = ErrorCode.valueOf(transfer.rejectionCode());
        String detail = code == ErrorCode.INSUFFICIENT_FUNDS
                ? "Source account balance is insufficient for this transfer."
                : "The source or destination account is not ACTIVE.";
        Map<String, Object> problem = Problems.asMap(HttpStatus.UNPROCESSABLE_CONTENT, code, detail);
        problem.put("transferId", transfer.publicId());
        return problem;
    }

    private static TransferResponse response(Transfer t) {
        return new TransferResponse(t.publicId(), t.status(), t.rejectionCode(), t.journalId(), t.sourceAccountId(),
                t.destinationAccountId(), MoneyJson.of(t.amount()), t.description(), t.clientReference(),
                t.createdAt());
    }
}
