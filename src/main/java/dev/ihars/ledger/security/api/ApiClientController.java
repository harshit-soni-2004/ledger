package dev.ihars.ledger.security.api;

import dev.ihars.ledger.security.ApiClient;
import dev.ihars.ledger.security.ApiClientPrincipal;
import dev.ihars.ledger.security.ApiClientService;
import dev.ihars.ledger.security.ApiClientService.CreatedClient;
import dev.ihars.ledger.security.ApiKey;
import dev.ihars.ledger.security.ApiRole;
import dev.ihars.ledger.security.IssuedKey;
import dev.ihars.ledger.shared.error.ApiProblemException;
import dev.ihars.ledger.shared.error.ErrorCode;
import dev.ihars.ledger.shared.web.RequestContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADMIN-only client and key management. Responses that carry a plaintext token are not
 * cacheable and are deliberately not idempotent-replayable: replay would require storing the
 * token.
 */
@RestController
@RequestMapping("/api/v1/api-clients")
@Tag(name = "API clients", description = "ADMIN only. Create clients, issue and revoke API keys.")
public class ApiClientController {

    private final ApiClientService clients;

    public ApiClientController(ApiClientService clients) {
        this.clients = clients;
    }

    public record CreateClientRequest(@NotBlank @Size(max = 128) String name, @NotNull ApiRole role) {}

    public record KeyResponse(String id, String prefix, ApiKey.Status status, Instant createdAt, Instant revokedAt) {
        static KeyResponse of(ApiKey key) {
            return new KeyResponse(key.publicId(), key.prefix(), key.status(), key.createdAt(), key.revokedAt());
        }
    }

    public record IssuedKeyResponse(String id, String clientId, String prefix, Instant createdAt, String token) {
        static IssuedKeyResponse of(IssuedKey issued) {
            ApiKey key = issued.key();
            return new IssuedKeyResponse(key.publicId(), key.clientPublicId(), key.prefix(), key.createdAt(),
                    issued.token());
        }
    }

    public record ClientResponse(String id, String name, ApiRole role, ApiClient.Status status, Instant createdAt,
            List<KeyResponse> keys) {}

    public record CreatedClientResponse(String id, String name, ApiRole role, ApiClient.Status status,
            Instant createdAt, IssuedKeyResponse key) {}

    @PostMapping
    @Operation(summary = "Create a client and its first key", description = "The token is returned only once.")
    public ResponseEntity<CreatedClientResponse> create(@Valid @RequestBody CreateClientRequest request,
            @AuthenticationPrincipal ApiClientPrincipal caller) {
        CreatedClient created = clients.createClient(request.name(), request.role(), caller.actor(),
                RequestContext.requestId());
        ApiClient client = created.client();
        return ResponseEntity.created(URI.create("/api/v1/api-clients/" + client.publicId()))
                .cacheControl(CacheControl.noStore())
                .body(new CreatedClientResponse(client.publicId(), client.name(), client.role(), client.status(),
                        client.createdAt(), IssuedKeyResponse.of(created.key())));
    }

    @GetMapping("/{clientId}")
    @Operation(summary = "Client details and key metadata (never tokens)")
    public ClientResponse get(@PathVariable String clientId) {
        ApiClient client = clients.findClient(clientId)
                .orElseThrow(() -> ApiProblemException.notFound("Client not found."));
        return response(client);
    }

    @PostMapping("/{clientId}/keys")
    @Operation(summary = "Issue an additional key (rotation)", description = "At most two active keys per client.")
    public ResponseEntity<IssuedKeyResponse> issueKey(@PathVariable String clientId,
            @AuthenticationPrincipal ApiClientPrincipal caller) {
        IssuedKey issued = clients.issueKey(clientId, caller.actor(), RequestContext.requestId());
        return ResponseEntity.status(201).cacheControl(CacheControl.noStore()).body(IssuedKeyResponse.of(issued));
    }

    @PostMapping("/{clientId}/keys/{keyId}/revoke")
    @Operation(summary = "Revoke a key; effective on the next request")
    public ResponseEntity<Void> revokeKey(@PathVariable String clientId, @PathVariable String keyId,
            @AuthenticationPrincipal ApiClientPrincipal caller) {
        clients.revokeKey(clientId, keyId, caller.actor(), RequestContext.requestId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{clientId}/disable")
    @Operation(summary = "Disable a client; all of its keys stop working")
    public ClientResponse disable(@PathVariable String clientId, @AuthenticationPrincipal ApiClientPrincipal caller) {
        if (caller.clientPublicId().equals(clientId)) {
            throw new ApiProblemException(HttpStatus.CONFLICT, ErrorCode.CLIENT_DISABLED, "An admin cannot disable itself.");
        }
        return response(clients.disableClient(clientId, caller.actor(), RequestContext.requestId()));
    }

    private ClientResponse response(ApiClient client) {
        return new ClientResponse(client.publicId(), client.name(), client.role(), client.status(), client.createdAt(),
                clients.keys(client).stream().map(KeyResponse::of).toList());
    }
}
