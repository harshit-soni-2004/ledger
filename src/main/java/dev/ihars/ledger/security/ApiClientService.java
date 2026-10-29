package dev.ihars.ledger.security;

import dev.ihars.ledger.audit.Actor;
import dev.ihars.ledger.audit.AuditEvent;
import dev.ihars.ledger.audit.AuditTrail;
import dev.ihars.ledger.security.ApiClientRejectedException.Code;
import dev.ihars.ledger.security.infrastructure.JdbcApiClientRepository;
import dev.ihars.ledger.shared.id.PublicIdGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authenticates API keys and manages clients and keys. Plaintext tokens exist only in the
 * {@link IssuedKey} returned at creation; they are never stored, logged or audited.
 */
@Service
public class ApiClientService {

    public static final int MAX_ACTIVE_KEYS = 2;
    public static final int MAX_NAME_LENGTH = 128;

    private final JdbcApiClientRepository clients;
    private final AuditTrail audit;
    private final PublicIdGenerator ids;
    private final Clock clock;

    public ApiClientService(JdbcApiClientRepository clients, AuditTrail audit, PublicIdGenerator ids, Clock clock) {
        this.clients = clients;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    /** The caller for a presented token, or empty for anything not an active key of an active client. */
    @Transactional(readOnly = true)
    public Optional<ApiClientPrincipal> authenticate(String token) {
        if (!ApiKeys.isWellFormed(token)) {
            return Optional.empty();
        }
        return clients.findPrincipal(ApiKeys.hash(token), clock.instant());
    }

    /** Creates a client together with its first key. */
    @Transactional
    public CreatedClient createClient(String rawName, ApiRole role, Actor actor, String requestId) {
        String name = validName(rawName);
        if (role == null) {
            throw new ApiClientRejectedException(Code.INVALID_ROLE, "Role is required");
        }
        ApiClient client = clients.insertClient(ids.next(PublicIdGenerator.Prefix.CLIENT), name, role, now());
        audit.append(new AuditEvent(actor, "API_CLIENT_CREATED", "API_CLIENT", client.publicId(), requestId, null,
                Map.of("role", role.name())));
        return new CreatedClient(client, issue(client, ApiKeys.generate(), actor, requestId));
    }

    @Transactional
    public IssuedKey issueKey(String clientPublicId, Actor actor, String requestId) {
        ApiClient client = lockActive(clientPublicId);
        long active = clients.keys(client.id()).stream().filter(k -> k.status() == ApiKey.Status.ACTIVE).count();
        if (active >= MAX_ACTIVE_KEYS) {
            throw new ApiClientRejectedException(Code.TOO_MANY_ACTIVE_KEYS,
                    "A client may hold at most " + MAX_ACTIVE_KEYS + " active keys; revoke one first");
        }
        return issue(client, ApiKeys.generate(), actor, requestId);
    }

    @Transactional
    public void revokeKey(String clientPublicId, String keyPublicId, Actor actor, String requestId) {
        ApiClient client = clients.lockClient(clientPublicId)
                .orElseThrow(() -> new ApiClientRejectedException(Code.CLIENT_NOT_FOUND, "Client not found"));
        if (clients.revokeKey(client.id(), keyPublicId, now()) != 1) {
            throw new ApiClientRejectedException(Code.KEY_NOT_FOUND, "No active key with that id for this client");
        }
        audit.append(new AuditEvent(actor, "API_KEY_REVOKED", "API_KEY", keyPublicId, requestId, null,
                Map.of("client", client.publicId())));
    }

    @Transactional
    public ApiClient disableClient(String clientPublicId, Actor actor, String requestId) {
        ApiClient client = lockActive(clientPublicId);
        clients.disableClient(client.id(), now());
        audit.append(new AuditEvent(actor, "API_CLIENT_DISABLED", "API_CLIENT", client.publicId(), requestId, null,
                Map.of()));
        return new ApiClient(client.id(), client.publicId(), client.name(), client.role(), ApiClient.Status.DISABLED,
                client.createdAt());
    }

    @Transactional(readOnly = true)
    public Optional<ApiClient> findClient(String clientPublicId) {
        return clients.findClient(clientPublicId);
    }

    @Transactional(readOnly = true)
    public List<ApiKey> keys(ApiClient client) {
        return clients.keys(client.id());
    }

    /**
     * Seeds the first ADMIN from configuration (local/demo only). Does nothing if the key is
     * already registered, and refuses to add a second bootstrap admin once any admin exists.
     *
     * @return true if an admin was created
     */
    @Transactional
    public boolean bootstrapAdmin(String token) {
        if (!ApiKeys.isWellFormed(token)) {
            throw new IllegalStateException("The bootstrap admin key is not a well-formed API key");
        }
        if (clients.keyHashExists(ApiKeys.hash(token)) || clients.activeAdminExists()) {
            return false;
        }
        Actor system = Actor.system("bootstrap");
        ApiClient admin = clients.insertClient(ids.next(PublicIdGenerator.Prefix.CLIENT), "Bootstrap admin",
                ApiRole.ADMIN, now());
        audit.append(new AuditEvent(system, "API_CLIENT_CREATED", "API_CLIENT", admin.publicId(), null, null,
                Map.of("role", ApiRole.ADMIN.name(), "bootstrap", true)));
        issue(admin, token, system, null);
        return true;
    }

    private IssuedKey issue(ApiClient client, String token, Actor actor, String requestId) {
        ApiKey key = clients.insertKey(ids.next(PublicIdGenerator.Prefix.API_KEY), client,
                ApiKeys.displayPrefix(token), ApiKeys.hash(token), now());
        audit.append(new AuditEvent(actor, "API_KEY_ISSUED", "API_KEY", key.publicId(), requestId, null,
                Map.of("client", client.publicId(), "prefix", key.prefix())));
        return new IssuedKey(key, token);
    }

    private ApiClient lockActive(String clientPublicId) {
        ApiClient client = clients.lockClient(clientPublicId)
                .orElseThrow(() -> new ApiClientRejectedException(Code.CLIENT_NOT_FOUND, "Client not found"));
        if (client.status() != ApiClient.Status.ACTIVE) {
            throw new ApiClientRejectedException(Code.CLIENT_DISABLED, "Client is disabled");
        }
        return client;
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    private static String validName(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || name.chars().anyMatch(Character::isISOControl)) {
            throw new ApiClientRejectedException(Code.INVALID_NAME,
                    "Name must be 1-" + MAX_NAME_LENGTH + " characters without control characters");
        }
        return name;
    }

    public record CreatedClient(ApiClient client, IssuedKey key) {}
}
