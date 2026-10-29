package dev.ihars.ledger.security;

import static dev.ihars.ledger.security.ApiRole.ADMIN;
import static dev.ihars.ledger.security.ApiRole.CLIENT;
import static dev.ihars.ledger.security.ApiRole.OPERATOR;
import static org.assertj.core.api.Assertions.assertThat;

import dev.ihars.ledger.support.IntegrationTest;
import java.net.http.HttpResponse;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;

/**
 * The authorization matrix of docs/04-api-contract.md, route by route. "Allowed" means the
 * request got past authorization (any status but 401/403); requests use ids and bodies that
 * fail later, so nothing is changed.
 */
class RoleMatrixIT extends IntegrationTest {

    private static final String ACCOUNT = "/api/v1/accounts/acct_00000000000000000000000000";
    private static final Set<ApiRole> ANY = EnumSet.allOf(ApiRole.class);
    private static final Set<ApiRole> STAFF = EnumSet.of(OPERATOR, ADMIN);
    private static final Set<ApiRole> ADMIN_ONLY = EnumSet.of(ADMIN);
    private static final Set<ApiRole> NOBODY = EnumSet.noneOf(ApiRole.class);

    private record Route(String method, String path, String body, Set<ApiRole> allowed) {}

    private static final List<Route> ROUTES = List.of(
            new Route("POST", "/api/v1/accounts", "{}", ANY),
            new Route("GET", "/api/v1/accounts", null, ANY),
            new Route("GET", ACCOUNT, null, ANY),
            new Route("GET", ACCOUNT + "/balance", null, ANY),
            new Route("GET", ACCOUNT + "/entries", null, ANY),
            new Route("POST", ACCOUNT + "/freeze", "{}", STAFF),
            new Route("POST", ACCOUNT + "/unfreeze", "{}", ADMIN_ONLY),
            new Route("POST", ACCOUNT + "/close", "{}", ADMIN_ONLY),
            new Route("GET", "/api/v1/journal-transactions/jtx_00000000000000000000000000", null, STAFF),
            new Route("POST", "/api/v1/journal-transactions/openings", "{}", ADMIN_ONLY),
            new Route("GET", "/api/v1/ops/integrity-checks/latest", null, ADMIN_ONLY),
            new Route("POST", "/api/v1/api-clients", "{}", ADMIN_ONLY),
            new Route("GET", "/api/v1/api-clients/cli_00000000000000000000000000", null, ADMIN_ONLY),
            new Route("POST", "/api/v1/transfers", "{}", NOBODY),
            new Route("GET", "/api/v1/deposits", null, NOBODY));

    @Test
    void everyRouteAdmitsExactlyItsRoles() {
        SoftAssertions softly = new SoftAssertions();
        for (Route route : ROUTES) {
            for (ApiRole role : ApiRole.values()) {
                HttpResponse<String> response = call(route.method(), route.path(), keyFor(role), route.body());
                String label = role + " " + route.method() + " " + route.path() + " -> " + response.body();
                if (route.allowed().contains(role)) {
                    softly.assertThat(response.statusCode()).as(label).isNotIn(401, 403);
                } else {
                    softly.assertThat(response.statusCode()).as(label).isEqualTo(403);
                }
            }
        }
        softly.assertAll();
    }

    @Test
    void everyRouteRequiresAuthentication() {
        for (Route route : ROUTES) {
            assertThat(call(route.method(), route.path(), null, route.body()).statusCode())
                    .as(route.method() + " " + route.path()).isEqualTo(401);
        }
    }
}
