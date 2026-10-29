package dev.ihars.ledger.security.web;

import dev.ihars.ledger.security.ApiClientPrincipal;
import dev.ihars.ledger.security.ApiClientService;
import dev.ihars.ledger.security.ApiKeys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates {@code Authorization: Bearer <api key>}. A request without the header continues
 * anonymously (public endpoints, or a 401 from the entry point); a request with a header that
 * does not identify an active key of an active client is rejected immediately. Every failure
 * produces the same response, and the token is never logged.
 */
public class ApiKeyAuthenticationFilter extends OncePerRequestFilter {

    public static final String CLIENT_ID_KEY = "clientId";
    public static final String KEY_ID_KEY = "keyId";
    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthenticationFilter.class);
    private static final String BEARER = "Bearer ";

    private final ApiClientService clients;
    private final AuthenticationEntryPoint entryPoint;
    private final SecurityContextHolderStrategy contexts = SecurityContextHolder.getContextHolderStrategy();

    public ApiKeyAuthenticationFilter(ApiClientService clients, AuthenticationEntryPoint entryPoint) {
        this.clients = clients;
        this.entryPoint = entryPoint;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null) {
            chain.doFilter(request, response);
            return;
        }
        Optional<ApiClientPrincipal> principal = principal(header);
        if (principal.isEmpty()) {
            log.warn("Rejected API credentials (prefix {})", safePrefix(header));
            entryPoint.commence(request, response, new BadCredentialsException("Invalid API key"));
            return;
        }
        ApiClientPrincipal caller = principal.get();
        SecurityContext context = contexts.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(caller, null,
                List.of(new SimpleGrantedAuthority(caller.role().authority()))));
        contexts.setContext(context);
        MDC.put(CLIENT_ID_KEY, caller.clientPublicId());
        MDC.put(KEY_ID_KEY, caller.keyPublicId());
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(CLIENT_ID_KEY);
            MDC.remove(KEY_ID_KEY);
            contexts.clearContext();
        }
    }

    private Optional<ApiClientPrincipal> principal(String header) {
        if (!header.startsWith(BEARER)) {
            return Optional.empty();
        }
        return clients.authenticate(header.substring(BEARER.length()));
    }

    /** Only the non-secret leading characters, and only for well-formed tokens. */
    private static String safePrefix(String header) {
        String token = header.startsWith(BEARER) ? header.substring(BEARER.length()) : "";
        return ApiKeys.isWellFormed(token) ? ApiKeys.displayPrefix(token) : "malformed";
    }
}
