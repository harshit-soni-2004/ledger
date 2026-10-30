package dev.ihars.ledger.security.web;

import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import dev.ihars.ledger.security.ApiClientService;
import dev.ihars.ledger.shared.error.ErrorCode;
import dev.ihars.ledger.shared.error.ProblemResponseWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderNotFoundException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;

/**
 * Stateless API-key security. The role matrix below is the single place where endpoint
 * permissions are declared (docs/04-api-contract.md); ownership is checked by the services.
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfiguration {

    private static final String CLIENT = "CLIENT";
    private static final String OPERATOR = "OPERATOR";
    private static final String ADMIN = "ADMIN";

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ApiClientService clients, ProblemResponseWriter problems)
            throws Exception {
        AuthenticationEntryPoint unauthenticated = (request, response, e) -> {
            response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
            problems.write(response, HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHENTICATED,
                    "A valid API key is required.");
        };
        AccessDeniedHandler forbidden = (request, response, e) -> problems.write(response, HttpStatus.FORBIDDEN,
                ErrorCode.FORBIDDEN, "Your role does not permit this operation.");

        http.csrf(csrf -> csrf.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(new ApiKeyAuthenticationFilter(clients, unauthenticated),
                        AnonymousAuthenticationFilter.class)
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/error").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**")
                                .permitAll()
                        .requestMatchers(POST, "/api/v1/accounts").hasAnyRole(CLIENT, OPERATOR, ADMIN)
                        .requestMatchers(GET, "/api/v1/accounts", "/api/v1/accounts/**")
                                .hasAnyRole(CLIENT, OPERATOR, ADMIN)
                        .requestMatchers(POST, "/api/v1/accounts/*/freeze").hasAnyRole(OPERATOR, ADMIN)
                        .requestMatchers(POST, "/api/v1/accounts/*/unfreeze", "/api/v1/accounts/*/close")
                                .hasRole(ADMIN)
                        .requestMatchers(POST, "/api/v1/transfers").hasRole(CLIENT)
                        .requestMatchers(GET, "/api/v1/transfers", "/api/v1/transfers/*")
                                .hasAnyRole(CLIENT, OPERATOR, ADMIN)
                        .requestMatchers(GET, "/api/v1/journal-transactions/*").hasAnyRole(OPERATOR, ADMIN)
                        .requestMatchers(POST, "/api/v1/journal-transactions/openings").hasRole(ADMIN)
                        .requestMatchers("/api/v1/ops/**").hasRole(ADMIN)
                        .requestMatchers("/api/v1/api-clients", "/api/v1/api-clients/**").hasRole(ADMIN)
                        .requestMatchers("/api/**").denyAll()
                        .anyRequest().authenticated());
        return http.build();
    }

    /** API keys are the only credential; this stops Boot from creating a default password user. */
    @Bean
    AuthenticationManager noPasswordAuthentication() {
        return authentication -> {
            throw new ProviderNotFoundException("Only API keys are supported");
        };
    }
}
