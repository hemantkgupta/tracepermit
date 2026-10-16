package io.tracepermit.contracts.redaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable profile defining active redaction rules and bounds.
 */
public final class RedactionProfile {
  public static final String DEFAULT_PROFILE_ID = "tracepermit-redaction-enterprise-v1";
  public static final int DEFAULT_MAX_DEPTH = 32;

  public static final Set<String> DEFAULT_SECRET_FIELD_NAMES = Set.of(
      "password",
      "passwd",
      "secret",
      "client_secret",
      "clientsecret",
      "private_key",
      "privatekey",
      "access_token",
      "accesstoken",
      "refresh_token",
      "refreshtoken",
      "authorization",
      "api_key",
      "apikey",
      "api_token",
      "apitoken",
      "auth_token",
      "authtoken",
      "access_key",
      "accesskey",
      "secret_key",
      "secretkey",
      "token",
      "bearer_token",
      "bearertoken",
      "session_token",
      "sessiontoken",
      "cvv",
      "security_code");

  public static final Pattern BEARER_TOKEN_PATTERN =
      Pattern.compile("(?i)\\bbearer\\s+[a-zA-Z0-9_\\-\\.~+/]+=*");
  public static final Pattern JWT_TOKEN_PATTERN =
      Pattern.compile("\\bey[a-zA-Z0-9_\\-]+\\.ey[a-zA-Z0-9_\\-]+\\.[a-zA-Z0-9_\\-]+");
  public static final Pattern PEM_PRIVATE_KEY_PATTERN =
      Pattern.compile("-----BEGIN[ A-Z0-9_-]+PRIVATE KEY-----[\\s\\S]*?(?:-----END[ A-Z0-9_-]+PRIVATE KEY-----|$)");
  public static final Pattern CREDIT_CARD_PATTERN =
      Pattern.compile("\\b(?:\\d{4}[ -]?){3}\\d{4}\\b|\\b\\d{15,16}\\b");
  public static final Pattern US_SSN_PATTERN =
      Pattern.compile("\\b\\d{3}-\\d{2}-\\d{4}\\b");

  private final String profileId;
  private final List<RedactionRule> rules;
  private final int maxDepth;

  public RedactionProfile(String profileId, List<RedactionRule> rules, int maxDepth) {
    this.profileId = Objects.requireNonNull(profileId, "profileId");
    this.rules = Collections.unmodifiableList(new ArrayList<>(Objects.requireNonNull(rules, "rules")));
    if (maxDepth < 1 || maxDepth > 128) {
      throw new IllegalArgumentException("maxDepth must be between 1 and 128");
    }
    this.maxDepth = maxDepth;
  }

  public static RedactionProfile enterpriseDefault() {
    List<RedactionRule> rules = List.of(
        RedactionRule.forFieldNames("credentials", RedactionStrategy.MASK, DEFAULT_SECRET_FIELD_NAMES),
        RedactionRule.forValuePattern("bearer_token", RedactionStrategy.MASK, BEARER_TOKEN_PATTERN),
        RedactionRule.forValuePattern("jwt_token", RedactionStrategy.MASK, JWT_TOKEN_PATTERN),
        RedactionRule.forValuePattern("private_key", RedactionStrategy.MASK, PEM_PRIVATE_KEY_PATTERN),
        RedactionRule.forValuePattern("credit_card", RedactionStrategy.MASK, CREDIT_CARD_PATTERN),
        RedactionRule.forValuePattern("ssn", RedactionStrategy.MASK, US_SSN_PATTERN));
    return new RedactionProfile(DEFAULT_PROFILE_ID, rules, DEFAULT_MAX_DEPTH);
  }

  public static RedactionProfile irreversibleHash() {
    List<RedactionRule> rules = List.of(
        RedactionRule.forFieldNames("credentials", RedactionStrategy.IRREVERSIBLE_HASH, DEFAULT_SECRET_FIELD_NAMES),
        RedactionRule.forValuePattern("bearer_token", RedactionStrategy.IRREVERSIBLE_HASH, BEARER_TOKEN_PATTERN),
        RedactionRule.forValuePattern("jwt_token", RedactionStrategy.IRREVERSIBLE_HASH, JWT_TOKEN_PATTERN),
        RedactionRule.forValuePattern("private_key", RedactionStrategy.IRREVERSIBLE_HASH, PEM_PRIVATE_KEY_PATTERN),
        RedactionRule.forValuePattern("credit_card", RedactionStrategy.IRREVERSIBLE_HASH, CREDIT_CARD_PATTERN),
        RedactionRule.forValuePattern("ssn", RedactionStrategy.IRREVERSIBLE_HASH, US_SSN_PATTERN));
    return new RedactionProfile("tracepermit-redaction-hash-v1", rules, DEFAULT_MAX_DEPTH);
  }

  public static RedactionProfile strictDrop() {
    List<RedactionRule> rules = List.of(
        RedactionRule.forFieldNames("credentials", RedactionStrategy.DROP, DEFAULT_SECRET_FIELD_NAMES),
        RedactionRule.forValuePattern("bearer_token", RedactionStrategy.MASK, BEARER_TOKEN_PATTERN),
        RedactionRule.forValuePattern("jwt_token", RedactionStrategy.MASK, JWT_TOKEN_PATTERN),
        RedactionRule.forValuePattern("private_key", RedactionStrategy.MASK, PEM_PRIVATE_KEY_PATTERN),
        RedactionRule.forValuePattern("credit_card", RedactionStrategy.MASK, CREDIT_CARD_PATTERN),
        RedactionRule.forValuePattern("ssn", RedactionStrategy.MASK, US_SSN_PATTERN));
    return new RedactionProfile("tracepermit-redaction-drop-v1", rules, DEFAULT_MAX_DEPTH);
  }

  public String profileId() {
    return profileId;
  }

  public List<RedactionRule> rules() {
    return rules;
  }

  public int maxDepth() {
    return maxDepth;
  }
}
