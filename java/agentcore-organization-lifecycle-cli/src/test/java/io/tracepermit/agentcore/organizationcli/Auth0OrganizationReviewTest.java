package io.tracepermit.agentcore.organizationcli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class Auth0OrganizationReviewTest {
  @Test void publicFacadeHasNoIdentityRepositorySignerOrTargetOverride() throws Exception {
    assertThat(Auth0OrganizationReview.class.getConstructors()).isEmpty();
    assertThat(Auth0OrganizationReview.Plan.class.getConstructors()).isEmpty();
    assertThat(Arrays.stream(Auth0OrganizationReview.class.getDeclaredMethods())
        .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName))
        .containsExactly("prepare");
    assertThat(Arrays.stream(Auth0OrganizationReview.Plan.class.getDeclaredMethods())
        .filter(m -> java.lang.reflect.Modifier.isPublic(m.getModifiers())).map(java.lang.reflect.Method::getName))
        .containsExactlyInAnyOrder("requireReviewerBinding","preview","review","toString");
    var prepare = Auth0OrganizationReview.class.getMethod("prepare",byte[].class,byte[].class);
    assertThat(prepare.getReturnType()).isEqualTo(Auth0OrganizationReview.Plan.class);
    assertThat(java.lang.reflect.Modifier.isStatic(prepare.getModifiers())).isTrue();
    assertThat(Auth0OrganizationReview.Plan.class.getMethod("review",byte[].class).getReturnType())
        .isEqualTo(Auth0OrganizationReview.Result.class);
    assertThat(Auth0OrganizationReview.Result.class.getConstructors()).isEmpty();
    assertThat(Auth0OrganizationReview.Preview.class.getConstructors()).isEmpty();
  }

  @Test void nullOversizeAndUnsupportedProviderNeverPrepare() {
    var f = OrganizationOperatorFixture.auth0();f.useReviewHelperClaims();
    var request = f.reviewCommand(UUID.randomUUID().toString(),"sha256:"+"a".repeat(64),"APPROVE");
    for (byte[] config : new byte[][]{null,new byte[0],new byte[65537],"null".getBytes(StandardCharsets.UTF_8)}) {
      assertThatThrownBy(() -> Auth0OrganizationReview.prepare(config,bytes(request)))
          .hasMessage("AUTH0_REVIEW_REJECTED");
    }
    for (byte[] command : new byte[][]{null,new byte[0],new byte[1025]}) {
      assertThatThrownBy(() -> Auth0OrganizationReview.prepare(f.configBytes(),command))
          .hasMessage("AUTH0_REVIEW_REJECTED");
    }
    var okta = new OrganizationOperatorFixture();
    assertThatThrownBy(() -> Auth0OrganizationReview.prepare(okta.configBytes(),bytes(request)))
        .hasMessage("AUTH0_REVIEW_REJECTED");
  }

  @ParameterizedTest @ValueSource(strings = {"null","empty","bad","non-ascii","too-large"})
  void malformedBearerCannotOpenDatabaseOrAuthorizeAnotherAttempt(String value) throws Exception {
    var f = OrganizationOperatorFixture.auth0();f.useReviewHelperClaims();
    try (var sentinel = new ServerSocket(0,8,InetAddress.getByName("127.0.0.1"))) {
      sentinel.setSoTimeout(200);
      for (String n : Set.of("replay","operations")) {
        ((com.fasterxml.jackson.databind.node.ObjectNode)f.configuration.path(n)).put("port",sentinel.getLocalPort());
      }
      var plan = Auth0OrganizationReview.prepare(f.configBytes(),bytes(f.reviewCommand(
          UUID.randomUUID().toString(),"sha256:"+"a".repeat(64),"APPROVE")));
      byte[] token = switch(value) {
        case "null" -> null;
        case "empty" -> new byte[0];
        case "bad" -> "Bearer a.b.c".getBytes(StandardCharsets.US_ASCII);
        case "non-ascii" -> new byte[]{(byte)255};
        case "too-large" -> new byte[16385];
        default -> throw new AssertionError();
      };
      assertThatThrownBy(() -> plan.preview(token)).hasMessage("AUTH0_REVIEW_REJECTED");
      var first = plan.review(token);
      assertThat(first.exitCode()).isEqualTo(1);
      assertThat(new String(first.bytes(),StandardCharsets.UTF_8))
          .contains("REJECTED","\"automaticRetryAllowed\":false");
      var spent = plan.review(f.reviewerToken(f.reviewerSubject()));
      assertThat(spent.exitCode()).isEqualTo(1);
      assertThat(spent.toString()).doesNotContain("password","Bearer",f.reviewerSubject());
      assertThat(plan.toString()).isEqualTo("Auth0OrganizationReview.Plan[redacted]");
      try (var unexpected = sentinel.accept()) { assertThat(unexpected.isConnected()).isTrue();throw new AssertionError("UNEXPECTED_REVIEW_DATABASE_CONNECTION"); }
      catch (java.net.SocketTimeoutException expected) { /* Expected zero connections. */ }
    }
  }

  private static byte[] bytes(com.fasterxml.jackson.databind.JsonNode n) {
    return n.toString().getBytes(StandardCharsets.UTF_8);
  }
}
