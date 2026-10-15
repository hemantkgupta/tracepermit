package io.tracepermit.adapters.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tracepermit.adapters.remote.EnforcingMcpDispatcher;
import io.tracepermit.application.HumanApprovalService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.TrustedReviewerContext;
import io.tracepermit.application.TrustedTenantContext;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.ApprovalRequestState;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.AuthenticationException;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.persistence.postgres.PostgresDurableEnforcementState;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.simulator.StatefulTargetSimulator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Authenticated, loopback-only API foundation. Not started by either existing demo launcher.
 * Optional browser adapter is isolated from the bearer API. No public edge or real target exists.
 */
public final class SimulatorApprovalServer implements AutoCloseable {
  private static final String ROOT = "/api/v1/approvals";
  private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
      .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
      .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final HttpServer server;
  private final ExecutorService workers;
  private final OidcAccessTokenVerifier verifier;
  private final ApprovalMemberships memberships;
  private final PostgresDurableEnforcementState state;
  private final HumanApprovalService approvals;
  private final Clock clock;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final ApprovalBrowserAdapter browser;

  public static SimulatorApprovalServer start(
      InetSocketAddress address, OidcAccessTokenVerifier verifier, ApprovalMemberships memberships,
      PostgresDurableEnforcementState state, JsonNode policy, ExecutionPermitAuthority permits,
      StatefulTargetSimulator simulator, Clock clock) throws IOException {
    if (address.getAddress() == null
        || !"127.0.0.1".equals(address.getAddress().getHostAddress())) {
      throw new IllegalArgumentException("The approval foundation must bind to IPv4 loopback.");
    }
    return start(address, verifier, memberships, state, policy, permits, simulator, clock, null);
  }

  static SimulatorApprovalServer start(
      InetSocketAddress address, OidcAccessTokenVerifier verifier, ApprovalMemberships memberships,
      PostgresDurableEnforcementState state, JsonNode policy, ExecutionPermitAuthority permits,
      StatefulTargetSimulator simulator, Clock clock, ApprovalBrowserAdapter browser) throws IOException {
    if (address.getAddress() == null || !"127.0.0.1".equals(address.getAddress().getHostAddress())) {
      throw new IllegalArgumentException("The approval foundation must bind to IPv4 loopback.");
    }
    return new SimulatorApprovalServer(address, verifier, memberships, state, policy, permits,
        simulator, clock, browser);
  }

  private SimulatorApprovalServer(
      InetSocketAddress address, OidcAccessTokenVerifier verifier, ApprovalMemberships memberships,
      PostgresDurableEnforcementState state, JsonNode policy, ExecutionPermitAuthority permits,
      StatefulTargetSimulator simulator, Clock clock, ApprovalBrowserAdapter browser) throws IOException {
    this.browser = browser;
    this.verifier = java.util.Objects.requireNonNull(verifier);
    this.memberships = java.util.Objects.requireNonNull(memberships);
    this.state = java.util.Objects.requireNonNull(state);
    this.clock = java.util.Objects.requireNonNull(clock);
    this.approvals = new HumanApprovalService(new PolicyEvaluator(), policy, permits, state, state,
        simulator, () -> SecurityReadiness.allReady(clock.instant()), clock,
        () -> UUID.randomUUID().toString(), "urn:tracepermit:approval-api:simulator", "simulator", 300);
    this.server = HttpServer.create(address, 32);
    // Bounded parallelism; this local foundation is not a production abuse-control layer.
    this.workers = Executors.newFixedThreadPool(8);
    server.setExecutor(workers);
    server.createContext("/", this::handle);
    server.start();
  }

  public int port() { return server.getAddress().getPort(); }

  @Override public void close() {
    if (closed.compareAndSet(false, true)) {
      server.stop(0); workers.close();
      if (browser != null) browser.clear();
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      var headers = exchange.getRequestHeaders();
      if (headers.getOrDefault("Host", java.util.List.of()).size() != 1
          || !("127.0.0.1:" + port()).equals(headers.getFirst("Host"))) {
        throw failure(400, "INVALID_HOST");
      }
      var uri = exchange.getRequestURI();
      if (uri.isAbsolute() || uri.getRawPath().contains("%")) throw failure(400, "INVALID_REQUEST");
      if (browser != null && browser.handle(exchange, port())) return;
      String path = uri.getRawPath();
      boolean browserRequest = browser != null && (ApprovalBrowserAdapter.API.equals(path)
          || path.startsWith(ApprovalBrowserAdapter.API + "/"));
      AuthenticatedConnector connector;
      if (browserRequest) {
        connector = browser.authenticate(exchange, port());
        path = ROOT + path.substring(ApprovalBrowserAdapter.API.length());
      } else {
        if (headers.containsKey("Origin") || headers.containsKey("Cookie")) {
          throw failure(403, "BROWSER_SESSION_NOT_SUPPORTED");
        }
        if (headers.getOrDefault("Authorization", java.util.List.of()).size() != 1) {
          throw failure(401, "INVALID_ACCESS_TOKEN");
        }
        connector = verifier.authenticate(headers.getFirst("Authorization"));
      }
      // A cnf claim is not proof of possession; this bearer-only local adapter cannot verify it.
      if (connector.senderConstraint().isPresent()) throw failure(401, "SENDER_PROOF_NOT_SUPPORTED");
      ApprovalMemberships.Entry member = memberships.resolve(connector);
      if (uri.getRawQuery() != null) {
        throw failure(400, "INVALID_REQUEST");
      }
      String method = exchange.getRequestMethod();
      if (ROOT.equals(path)) {
        if ("POST".equals(method)) {
          require(connector, member, ApprovalMemberships.Role.REQUESTER, "approvals.request");
          JsonNode arguments = body(exchange, Set.of("groupId", "port", "cidr"));
          validateArguments(arguments);
          JsonNode event = normalize(connector, member, arguments);
          var pending = approvals.requestApprovalWithSnapshot(context(member), event);
          send(exchange, 201, projection(pending.approval(), member));
        } else if ("GET".equals(method)) {
          requireRead(connector, member);
          ObjectNode page = JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
          var items = page.putArray("items");
          String owner = member.roles().equals(Set.of(ApprovalMemberships.Role.REQUESTER))
              ? member.principalId() : null;
          for (var approval : state.listApprovals(member.tenant(), owner, 50)) {
            items.add(projection(approval, member));
          }
          send(exchange, 200, page);
        } else throw failure(405, "METHOD_NOT_ALLOWED");
      } else if (path.startsWith(ROOT + "/")) {
        String[] parts = path.substring(ROOT.length() + 1).split("/", -1);
        if (parts.length > 2 || !parts[0].matches("[a-f0-9-]{36}")) {
          throw failure(404, "APPROVAL_NOT_FOUND");
        }
        if ("GET".equals(method) && parts.length == 1) {
          requireRead(connector, member);
          send(exchange, 200, projection(visible(member, parts[0]), member));
        } else if ("POST".equals(method) && parts.length == 2
            && "decision".equals(parts[1])) {
          require(connector, member, ApprovalMemberships.Role.REVIEWER, "approvals.decide");
          ApprovalRequestState approval = visible(member, parts[0]);
          JsonNode decision = body(exchange, Set.of("decision", "requestHash"));
          checkHash(decision, approval);
          if (!decision.path("decision").isTextual()
              || !Set.of("approve", "deny").contains(decision.path("decision").asText())) {
            throw failure(400, "INVALID_REQUEST");
          }
          var reviewer = new TrustedReviewerContext(member.tenant(), member.principalId(), "human",
              true, Set.of(HumanApprovalService.REVIEWER_ROLE),
              Set.of(HumanApprovalService.REVIEWER_SCOPE), connector.authenticationProfile(),
              connector.credentialFingerprint().value());
          var reviewed = approvals.review(reviewer, parts[0], decision.path("decision").asText(),
              "CHANGE_REVIEWED");
          send(exchange, 200, projection(reviewed, member));
        } else if ("POST".equals(method) && parts.length == 2
            && "execute".equals(parts[1])) {
          require(connector, member, ApprovalMemberships.Role.REQUESTER, "approvals.execute");
          ApprovalRequestState approval = visible(member, parts[0]);
          if (!member.principalId().equals(approval.binding().subjectId())) {
            throw failure(403, "REQUESTER_REQUIRED");
          }
          if ("approved".equals(approval.status())
              && !memberships.isActiveReviewer(member.tenant(), approval.reviewerSubjectId())) {
            throw failure(403, "REVIEWER_AUTHORITY_REVOKED");
          }
          checkHash(body(exchange, Set.of("requestHash")), approval);
          var executed = approvals.executeApproved(context(member), parts[0], exactAction(approval));
          ObjectNode result = projection(executed.approval(), member);
          result.put("executionStatus", executed.outcome());
          send(exchange, 200, result);
        } else throw failure(405, "METHOD_NOT_ALLOWED");
      } else throw failure(404, "NOT_FOUND");
    } catch (ApprovalBrowserAdapter.BrowserFailure failure) {
      error(exchange, failure.status, failure.code);
    } catch (HttpFailure failure) {
      error(exchange, failure.status, failure.code);
    } catch (AuthenticationException failure) {
      error(exchange, 401, "INVALID_ACCESS_TOKEN");
    } catch (SecurityException failure) {
      error(exchange, 403, "NOT_AUTHORIZED");
    } catch (IllegalArgumentException failure) {
      if ("SELF_APPROVAL_DENIED".equals(failure.getMessage())) {
        error(exchange, 403, "SELF_APPROVAL_DENIED");
      } else if ("APPROVAL_BINDING_MISMATCH".equals(failure.getMessage())) {
        error(exchange, 409, "APPROVAL_BINDING_MISMATCH");
      } else error(exchange, 400, "INVALID_REQUEST");
    } catch (IllegalStateException failure) {
      String code = failure.getMessage();
      if (Set.of("APPROVAL_ALREADY_DECIDED", "APPROVAL_NOT_EXECUTABLE", "APPROVAL_REPLAYED",
          "APPROVAL_EXPIRED", "APPROVAL_BINDING_MISMATCH", "APPROVAL_POLICY_MISMATCH",
          "APPROVAL_NOT_REQUIRED")
          .contains(code == null ? "" : code)) error(exchange, 409, code);
      else error(exchange, 503, "SECURITY_STATE_UNAVAILABLE");
    } catch (RuntimeException failure) {
      error(exchange, 503, "SECURITY_STATE_UNAVAILABLE");
    } finally { exchange.close(); }
  }

  private ApprovalRequestState visible(ApprovalMemberships.Entry member, String id) {
    var approval = state.find(member.tenant(), id);
    if (approval == null || (member.roles().equals(Set.of(ApprovalMemberships.Role.REQUESTER))
        && !member.principalId().equals(approval.binding().subjectId()))) {
      throw failure(404, "APPROVAL_NOT_FOUND");
    }
    return approval;
  }

  private JsonNode exactAction(ApprovalRequestState approval) {
    JsonNode action = state.findAction(approval.binding().tenant(), approval.binding().approvalRequestId());
    if (action == null || !approval.binding().actionHash().equals(CanonicalJson.sha256(action))) {
      throw new IllegalStateException("APPROVAL_SNAPSHOT_UNAVAILABLE");
    }
    return action;
  }

  private ObjectNode projection(ApprovalRequestState approval, ApprovalMemberships.Entry member) {
    JsonNode event = exactAction(approval);
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    result.put("mode", "simulated");
    result.put("approvalRequestId", approval.binding().approvalRequestId());
    result.put("requestHash", approval.binding().requestHash());
    result.put("requesterId", approval.binding().subjectId());
    result.put("status", approval.status());
    result.put("expiresAt", approval.binding().expiresAt().toString());
    result.put("expired", !approval.binding().expiresAt().isAfter(clock.instant()));
    result.put("policyId", approval.binding().policyId());
    result.put("policyVersion", approval.binding().policyVersion());
    result.put("originalPolicyOutcome", approval.binding().originalDecision());
    result.set("action", event.path("action").deepCopy());
    if (approval.reviewerSubjectId() != null) result.put("reviewerId", approval.reviewerSubjectId());
    if (approval.consumedAttemptId() != null) {
      var attempt = state.findByIdempotencyKey(member.tenant(), "simulator",
          InMemoryExecutionLedger.idempotencyKey(event, approval.binding().actionHash()));
      if (attempt == null) throw new IllegalStateException("APPROVAL_EXECUTION_UNAVAILABLE");
      result.put("executionStatus", attempt.status());
    } else result.put("executionStatus", "not_executed");
    return result;
  }

  private JsonNode normalize(AuthenticatedConnector connector, ApprovalMemberships.Entry member,
      JsonNode arguments) {
    AuthenticatedConnector pseudonymous = new AuthenticatedConnector(member.principalId(),
        "Enrolled API principal", member.tenant().value(), connector.origin(), connector.clientId(),
        connector.clientName(), connector.issuer(), connector.verifiedAt(),
        connector.authenticationProfile(), connector.verificationKeyId(),
        connector.credentialFingerprint(), connector.scopes(), connector.expiresAt());
    ObjectNode event = (ObjectNode) EnforcingMcpDispatcher.buildStandardEvent(
        "aws.ec2.update_security_group", "aws", "prod", "security-group", arguments,
        UUID.randomUUID().toString(), UUID.randomUUID().toString().replace("-", ""),
        clock.instant(), pseudonymous, Optional.empty());
    ((ObjectNode) event.path("identity").path("subject")).put("type", member.human() ? "human" : "workload");
    ((ObjectNode) event.path("trigger")).put("kind", "service").put("origin", "api");
    ((ObjectNode) event.path("action")).put("protocol", "http");
    ((ObjectNode) event.path("clientClaims")).put("transport", "loopback-http-approval-api");
    return ActionValidator.validate(event);
  }

  private static void validateArguments(JsonNode args) {
    if (!"sg-demo".equals(args.path("groupId").asText()) || !args.path("port").isInt()
        || args.path("port").asInt() < 1 || args.path("port").asInt() > 65535
        || !Set.of("192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24")
            .contains(args.path("cidr").asText())) throw failure(400, "INVALID_REQUEST");
  }

  private static void requireRead(AuthenticatedConnector connector, ApprovalMemberships.Entry member) {
    if (!connector.hasScope(ConnectorScope.of("approvals.read"))) throw failure(403, "NOT_AUTHORIZED");
  }

  private static void require(AuthenticatedConnector connector, ApprovalMemberships.Entry member,
      ApprovalMemberships.Role role, String scope) {
    if (!member.roles().contains(role) || !connector.hasScope(ConnectorScope.of(scope))) {
      throw failure(403, "NOT_AUTHORIZED");
    }
  }

  private static TrustedTenantContext context(ApprovalMemberships.Entry member) {
    return new TrustedTenantContext(member.tenant());
  }

  private static void checkHash(JsonNode body, ApprovalRequestState approval) {
    if (!body.path("requestHash").isTextual()
        || !approval.binding().requestHash().equals(body.path("requestHash").asText())) {
      throw failure(409, "APPROVAL_BINDING_MISMATCH");
    }
  }

  private static JsonNode body(HttpExchange exchange, Set<String> fields) throws IOException {
    var types = exchange.getRequestHeaders().getOrDefault("Content-Type", java.util.List.of());
    if (types.size() != 1 || !"application/json".equals(types.getFirst())) {
      throw failure(415, "JSON_REQUIRED");
    }
    byte[] bytes = exchange.getRequestBody().readNBytes(65537);
    if (bytes.length > 65536) throw failure(413, "REQUEST_TOO_LARGE");
    JsonNode body;
    try {
      String utf8 = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
      body = JSON.readTree(utf8);
    } catch (IOException failure) { throw failure(400, "INVALID_REQUEST"); }
    if (body == null || !body.isObject() || body.size() != fields.size()) {
      throw failure(400, "INVALID_REQUEST");
    }
    var names = body.fieldNames();
    while (names.hasNext()) if (!fields.contains(names.next())) throw failure(400, "INVALID_REQUEST");
    return body;
  }

  private static HttpFailure failure(int status, String code) { return new HttpFailure(status, code); }

  private static final class HttpFailure extends RuntimeException {
    private static final long serialVersionUID = 1L;
    final int status;
    final String code;
    HttpFailure(int status, String code) { this.status = status; this.code = code; }
  }

  private static void error(HttpExchange exchange, int status, String code) throws IOException {
    if (status == 401) exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.putObject("error").put("code", code);
    send(exchange, status, response);
  }

  private static void send(HttpExchange exchange, int status, JsonNode value) throws IOException {
    byte[] bytes = JsonSupport.MAPPER.writeValueAsBytes(value);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.getResponseHeaders().set("Cache-Control", "no-store");
    exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }
}
