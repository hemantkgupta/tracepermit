package io.tracepermit.identity;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Operator-selected token grammar; groups never leave verification as raw claims. */
public final class OidcTokenProfile {
  public static final String STANDARD = "oauth-access-token-jwt-rs256-v1";
  public static final String OKTA = "okta-custom-as-jwt-rs256-v1";
  private static final Set<String> ROLES = Set.of("REQUESTER", "REVIEWER", "VIEWER");
  private static final Set<String> RESERVED = Set.of(
      "iss", "aud", "sub", "iat", "exp", "nbf", "jti", "nonce", "cid", "client_id",
      "scope", "scp", "cnf", "azp", "at_hash", "roles");
  private static final OidcTokenProfile DEFAULT = new OidcTokenProfile(null, Map.of());
  private final String groupClaim;
  private final Map<String, String> mappings;

  private OidcTokenProfile(String groupClaim, Map<String, String> mappings) {
    this.groupClaim = groupClaim;
    this.mappings = Map.copyOf(mappings);
  }

  public static OidcTokenProfile standard() { return DEFAULT; }

  public static OidcTokenProfile okta(String groupClaim, Map<String, String> mappings) {
    require(customClaim(groupClaim) && mappings != null && !mappings.isEmpty() && mappings.size() <= 32);
    mappings.forEach((group, role) -> require(group != null && !group.isBlank()
        && group.length() <= 128 && group.chars().noneMatch(Character::isISOControl)
        && role != null && ROLES.contains(role)));
    return new OidcTokenProfile(groupClaim, mappings);
  }

  public boolean isOkta() { return groupClaim != null; }
  public String authenticationProfile() { return isOkta() ? OKTA : STANDARD; }

  public void validateTenantClaim(String tenantClaim) {
    if (isOkta()) require(customClaim(tenantClaim) && !groupClaim.equals(tenantClaim));
  }

  /** Call only after signature, issuer, audience, client, tenant and time validation. */
  public Optional<String> mappedRole(JsonNode verifiedClaims) {
    if (!isOkta()) return Optional.empty();
    JsonNode groups = verifiedClaims.get(groupClaim);
    require(groups != null && groups.isArray() && !groups.isEmpty() && groups.size() <= 100);
    Set<String> unique = new HashSet<>();
    String selected = null;
    for (JsonNode group : groups) {
      require(group.isTextual() && !group.textValue().isBlank() && group.textValue().length() <= 128
          && group.textValue().chars().noneMatch(Character::isISOControl)
          && unique.add(group.textValue()));
      String role = mappings.get(group.textValue());
      if (role != null) {
        // Even two aliases for one role are ambiguous in this deliberately narrow profile.
        require(selected == null);
        selected = role;
      }
    }
    require(selected != null);
    return Optional.of(Objects.requireNonNull(selected));
  }

  private static boolean customClaim(String name) {
    return name != null && name.matches("[A-Za-z][A-Za-z0-9_]{0,63}") && !RESERVED.contains(name);
  }

  private static void require(boolean condition) {
    if (!condition) throw new IllegalArgumentException("INVALID_OIDC_GROUP_BINDING");
  }

  @Override public String toString() { return authenticationProfile(); }
}
