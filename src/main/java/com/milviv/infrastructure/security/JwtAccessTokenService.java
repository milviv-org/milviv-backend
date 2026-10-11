package com.milviv.infrastructure.security;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.milviv.application.auth.AccessTokenIssuer;

/**
 * Access-токен — JWT, подпись HS256 (RFC 7519, RFC 7518).
 *
 * Проверка следует JWT BCP (RFC 8725): алгоритм фиксирован и не берётся из
 * заголовка («alg: none» и подмена алгоритма отклоняются), подпись сравнивается
 * за постоянное время, проверяются iss и exp.
 */
public class JwtAccessTokenService implements AccessTokenIssuer {

    private static final String ALGORITHM = "HS256";
    private static final String HMAC = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper json = new ObjectMapper();
    private final SecretKeySpec key;
    private final String issuer;
    private final Duration ttl;
    private final String encodedHeader;

    public JwtAccessTokenService(byte[] secret, String issuer, Duration ttl) {
        this.key = new SecretKeySpec(secret, HMAC);
        this.issuer = issuer;
        this.ttl = ttl;
        this.encodedHeader = encode(Map.of("alg", ALGORITHM, "typ", "JWT"));
    }

    @Override
    public AccessToken issue(UUID userId, Instant now) {
        Instant issuedAt = Instant.ofEpochSecond(now.getEpochSecond());
        Instant expiresAt = issuedAt.plus(ttl);

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", issuer);
        claims.put("sub", userId.toString());
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expiresAt.getEpochSecond());
        claims.put("jti", UUID.randomUUID().toString());

        String signingInput = encodedHeader + "." + encode(claims);
        return new AccessToken(signingInput + "." + ENCODER.encodeToString(sign(signingInput)), expiresAt);
    }

    /** userId, если токен подписан нами, выпущен нами и ещё не истёк; иначе пусто. */
    public Optional<UUID> verify(String token, Instant now) {
        if (token == null) {
            return Optional.empty();
        }
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) {
                return Optional.empty();
            }

            JsonNode header = json.readTree(DECODER.decode(parts[0]));
            if (!ALGORITHM.equals(header.path("alg").asText()) || !"JWT".equals(header.path("typ").asText())) {
                return Optional.empty();
            }

            byte[] expected = sign(parts[0] + "." + parts[1]);
            byte[] actual = DECODER.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                return Optional.empty();
            }

            JsonNode claims = json.readTree(DECODER.decode(parts[1]));
            if (!issuer.equals(claims.path("iss").asText())) {
                return Optional.empty();
            }
            if (!claims.path("exp").canConvertToLong() || now.getEpochSecond() >= claims.path("exp").asLong()) {
                return Optional.empty();
            }
            return Optional.of(UUID.fromString(claims.path("sub").asText()));
        } catch (Exception ex) {
            // неверный base64, JSON или UUID — токен просто недействителен
            return Optional.empty();
        }
    }

    private String encode(Map<String, ?> value) {
        try {
            return ENCODER.encodeToString(json.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to serialize JWT part", ex);
        }
    }

    private byte[] sign(String signingInput) {
        try {
            Mac mac = Mac.getInstance(HMAC); // Mac не потокобезопасен — новый на каждый вызов
            mac.init(key);
            return mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is not available", ex);
        }
    }
}
