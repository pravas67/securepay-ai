package com.securepay.security;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import java.nio.charset.StandardCharsets;
import java.util.List;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Signs and verifies our own HS256 access tokens. To move to an external OIDC provider (Cognito, Auth0, Keycloak),
 * replace the decoder with spring.security.oauth2.resourceserver.jwt.issuer-uri and map its claims in
 * {@link #jwtAuthenticationConverter()}.
 */
@Configuration
@EnableConfigurationProperties(AuthProperties.class)
public class JwtConfig {

    private final SecretKey key;
    private final AuthProperties properties;

    public JwtConfig(AuthProperties properties) {
        this.properties = properties;
        this.key = new SecretKeySpec(properties.jwtSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder() {
        return new NimbusJwtEncoder(new ImmutableSecret<>(key));
    }

    @Bean
    JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        // Checks expiry/not-before (with small clock skew) and that we issued the token.
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(properties.issuer())));
        return decoder;
    }

    /** Turns a verified token into the same {@link SecurityUser} principal the controllers already use. */
    @Bean
    Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter() {
        return jwt -> {
            SecurityUser user = new SecurityUser(
                    ((Number) jwt.getClaim(TokenService.USER_ID_CLAIM)).longValue(),
                    jwt.getSubject(),
                    null,
                    jwt.getClaimAsString(TokenService.ROLE_CLAIM));
            return UsernamePasswordAuthenticationToken.authenticated(user, jwt, user.getAuthorities());
        };
    }

    static List<String> publicPaths() {
        return List.of("/api/auth/login", "/actuator/health", "/api/webhooks/stripe",
                "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html");
    }
}
