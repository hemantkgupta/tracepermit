package io.tracepermit.adapters.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.tracepermit.adapters.remote.EnforcingMcpDispatcher;
import io.tracepermit.application.HumanApprovalService;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.TrustedReviewerContext;
import io.tracepermit.application.TrustedRequesterContext;
import io.tracepermit.application.TrustedTenantContext;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.contracts.redaction.EvidenceRedactor;
import io.tracepermit.execution.ApprovalRequestState;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.AuthenticationException;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.OidcAccessTokenVerifier;
import io.tracepermit.persistence.postgres.PostgresDurableEnforcementState;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.policy.PolicyLifecycleStore;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.simulator.StatefulTargetSimulator;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

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
  private final BooleanSupplier readiness;

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
        simulator, clock, browser, ApprovalAdmissionExecutors.DEFAULT_ACTIVE_LANES,
        ApprovalAdmissionExecutors.DEFAULT_PENDING_BOUND, () -> true);
  }

  static SimulatorApprovalServer start(
      InetSocketAddress address, OidcAccessTokenVerifier verifier, ApprovalMemberships memberships,
      PostgresDurableEnforcementState state, JsonNode policy, ExecutionPermitAuthority permits,
      StatefulTargetSimulator simulator, Clock clock, ApprovalBrowserAdapter browser,
      BooleanSupplier readiness) throws IOException {
    if (address.getAddress() == null || !"127.0.0.1".equals(address.getAddress().getHostAddress())) {
      throw new IllegalArgumentException("The approval foundation must bind to IPv4 loopback.");
    }
    return new SimulatorApprovalServer(address, verifier, memberships, state, policy, permits,
        simulator, clock, browser, ApprovalAdmissionExecutors.DEFAULT_ACTIVE_LANES,
        ApprovalAdmissionExecutors.DEFAULT_PENDING_BOUND, readiness);
  }

  static SimulatorApprovalServer start(
      InetSocketAddress address, OidcAccessTokenVerifier verifier, ApprovalMemberships memberships,
      PostgresDurableEnforcementState state, JsonNode policy, ExecutionPermitAuthority permits,
      StatefulTargetSimulator simulator, Clock clock, int activeLanes, int pendingBound)
      throws IOException {
    if (address.getAddress() == null || !"127.0.0.1".equals(address.getAddress().getHostAddress())) {
      throw new IllegalArgumentException("The approval foundation must bind to IPv4 loopback.");
    }
    ApprovalAdmissionExecutors.validate(activeLanes, pendingBound);
    return new SimulatorApprovalServer(address, verifier, memberships, state, policy, permits,
        simulator, clock, null, activeLanes, pendingBound, () -> true);
  }

  public static SimulatorApprovalServer start(
      InetSocketAddress address, OidcAccessTokenVerifier verifier, ApprovalMemberships memberships,
      PostgresDurableEnforcementState state, PolicyLifecycleStore policyStore, String workspaceId,
      String policyId, ExecutionPermitAuthority permits, StatefulTargetSimulator simulator, Clock clock)
      throws IOException {
    if (address.getAddress() == null
        || !"127.0.0.1".equals(address.getAddress().getHostAddress())) {
      throw new IllegalArgumentException("The approval foundation must bind to IPv4 loopback.");
    }
    return start(address, verifier, memberships, state, policyStore, workspaceId, policyId, permits, simulator, clock, null);
  }

  static SimulatorApprovalServer start(
      InetSocketAddress address, OidcAccessTokenVerifier verifier, ApprovalMemberships memberships,
      PostgresDurableEnforcementState state, PolicyLifecycleStore policyStore, String workspaceId,
      String policyId, ExecutionPermitAuthority permits, StatefulTargetSimulator simulator, Clock clock,
      ApprovalBrowserAdapter browser) throws IOException {
    if (address.getAddress() == null || !"127.0.0.1".equals(address.getAddress().getHostAddress())) {
      throw new IllegalArgumentException("The approval foundation must bind to IPv4 loopback.");
    }
    return new SimulatorApprovalServer(address, verifier, memberships, state, policyStore, workspaceId,
        policyId, permits, simulator, clock, browser, ApprovalAdmissionExecutors.DEFAULT_ACTIVE_LANES,
        ApprovalAdmissionExecutors.DEFAULT_PENDING_BOUND, () -> true);
  }

  private SimulatorApprovalServer(
      InetSocketAddress address, OidcAccessTokenVerifier verifier, ApprovalMemberships memberships,
      PostgresDurableEnforcementState state, JsonNode policy, ExecutionPermitAuthority permits,
      StatefulTargetSimulator simulator, Clock clock, ApprovalBrowserAdapter browser,
      int activeLanes, int pendingBound, BooleanSupplier readiness) throws IOException {
    this.browser = browser;
    this.verifier = java.util.Objects.requireNonNull(verifier);
    this.memberships = java.util.Objects.requireNonNull(memberships);
    this.state = java.util.Objects.requireNonNull(state);
    this.clock = java.util.Objects.requireNonNull(clock);
    this.readiness = java.util.Objects.requireNonNull(readiness);
    this.approvals = new HumanApprovalService(new PolicyEvaluator(), policy, permits, state, state,
        simulator, () -> SecurityReadiness.allReady(clock.instant()), clock,
        () -> UUID.randomUUID().toString(), "urn:tracepermit:approval-api:simulator", "simulator", 300);
    this.server = HttpServer.create(address, 32);
    // Bounded admission; this local foundation is not a public abuse-control layer.
    this.workers = ApprovalAdmissionExecutors.newDispatchPool(activeLanes, pendingBound);
    server.setExecutor(workers);
    server.createContext("/", this::handle);
    server.start();
  }

  private SimulatorApprovalServer(
      InetSocketAddress address, OidcAccessTokenVerifier verifier, ApprovalMemberships memberships,
      PostgresDurableEnforcementState state, PolicyLifecycleStore policyStore, String workspaceId,
      String policyId, ExecutionPermitAuthority permits, StatefulTargetSimulator simulator, Clock clock,
      ApprovalBrowserAdapter browser, int activeLanes, int pendingBound, BooleanSupplier readiness)
      throws IOException {
    this.browser = browser;
    this.verifier = java.util.Objects.requireNonNull(verifier);
    this.memberships = java.util.Objects.requireNonNull(memberships);
    this.state = java.util.Objects.requireNonNull(state);
    this.clock = java.util.Objects.requireNonNull(clock);
    this.readiness = java.util.Objects.requireNonNull(readiness);
    this.approvals = new HumanApprovalService(new PolicyEvaluator(), policyStore, workspaceId, policyId,
        permits, state, state, simulator, () -> SecurityReadiness.allReady(clock.instant()), clock,
        () -> UUID.randomUUID().toString(), "urn:tracepermit:approval-api:simulator", "simulator", 300);
    this.server = HttpServer.create(address, 32);
    // Bounded admission; this local foundation is not a public abuse-control layer.
    this.workers = ApprovalAdmissionExecutors.newDispatchPool(activeLanes, pendingBound);
    server.setExecutor(workers);
    server.createContext("/", this::handle);
    server.start();
  }

  public int port() { return server.getAddress().getPort(); }

  @Override public void close() {
    if (closed.compareAndSet(false, true)) {
      server.stop(0);
      workers.shutdown();
      try {
        if (!workers.awaitTermination(ApprovalAdmissionExecutors.SHUTDOWN_JOIN_SECONDS, TimeUnit.SECONDS)) {
          workers.shutdownNow();
        }
      } catch (InterruptedException interrupted) {
        workers.shutdownNow();
        Thread.currentThread().interrupt();
      }
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
      if (closed.get()) throw failure(503, "SERVER_SHUTDOWN");
      // Admission happened before this handler runs: the bounded dispatch pool only starts
      // a lane for an exchange it took from its bounded queue, so authentication, body
      // parsing, database work, and dispatch all happen inside the accepted capacity.
      dispatch(exchange, headers, uri);
    } catch (ApprovalBrowserAdapter.BrowserFailure failure) {
      error(exchange, failure.status, failure.code);
    } catch (HttpFailure failure) {
      error(exchange, failure.status, failure.code);
    } catch (AuthenticationException failure) {
      error(exchange, 401, "INVALID_ACCESS_TOKEN");
    } catch (SecurityException failure) {
      if ("CANCELLATION_AUTHENTICATION_EXPIRED".equals(failure.getMessage())) {
        error(exchange, 401, "INVALID_ACCESS_TOKEN");
      } else error(exchange, 403, "NOT_AUTHORIZED");
    } catch (IllegalArgumentException failure) {
      if ("SELF_APPROVAL_DENIED".equals(failure.getMessage())) {
        error(exchange, 403, "SELF_APPROVAL_DENIED");
      } else if ("APPROVAL_BINDING_MISMATCH".equals(failure.getMessage())) {
        error(exchange, 409, "APPROVAL_BINDING_MISMATCH");
      } else error(exchange, 400, "INVALID_REQUEST");
    } catch (IllegalStateException failure) {
      String code = failure.getMessage();
      if (Set.of("APPROVAL_ALREADY_DECIDED", "APPROVAL_NOT_EXECUTABLE", "APPROVAL_NOT_CANCELLABLE", "APPROVAL_REPLAYED",
          "APPROVAL_EXPIRED", "APPROVAL_BINDING_MISMATCH", "APPROVAL_POLICY_MISMATCH",
          "APPROVAL_NOT_REQUIRED", "STALE_POLICY_GENERATION")
          .contains(code == null ? "" : code)) error(exchange, 409, code);
      else error(exchange, 503, "SECURITY_STATE_UNAVAILABLE");
    } catch (RuntimeException failure) {
      error(exchange, 503, "SECURITY_STATE_UNAVAILABLE");
    } finally { exchange.close(); }
  }

  private void dispatch(HttpExchange exchange, Headers headers, URI uri) throws IOException {
    String path = uri.getRawPath();
    if ("/health".equals(path) || "/ready".equals(path)) {
      status(exchange, headers, uri, path);
      return;
    }
    if (browser != null && browser.handle(exchange, port())) return;
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
    if (uri.getRawQuery() != null
        && !(ROOT.equals(path) && "GET".equals(exchange.getRequestMethod()))) {
      throw failure(400, "INVALID_REQUEST");
    }
    String method = exchange.getRequestMethod();
    if ("/mcp".equals(path)) {
      if (!"POST".equals(method)) throw failure(405, "METHOD_NOT_ALLOWED");
      if (headers.containsKey("tracepermit-source-attestation")) {
        throw failure(403, "SOURCE_PROOF_NOT_SUPPORTED");
      }
      String version = headers.getFirst("MCP-Protocol-Version");
      if (headers.getOrDefault("MCP-Protocol-Version", java.util.List.of()).size() > 1
          || (version != null && !ApprovalMcpAdapter.PROTOCOLS.contains(version))) {
        throw failure(400, "UNSUPPORTED_MCP_VERSION");
      }
      var response = new ApprovalMcpAdapter((name, arguments) -> switch (name) {
        case ApprovalMcpAdapter.REQUEST -> request(connector, member, arguments, true);
        case ApprovalMcpAdapter.STATUS -> {
          require(connector, member, ApprovalMemberships.Role.REQUESTER, "approvals.read");
          yield projection(owned(member, arguments.path("approvalRequestId").asText()), member);
        }
        case ApprovalMcpAdapter.RESUME -> execute(connector, member,
            arguments.path("approvalRequestId").asText(), arguments, true);
        default -> throw failure(400, "TOOL_NOT_FOUND");
      }, mcpTools(connector, member)).dispatch(readBody(exchange));
      if (response.body() == null) exchange.sendResponseHeaders(response.status(), -1);
      else send(exchange, response.status(), response.body());
    } else if (ROOT.equals(path)) {
      if ("POST".equals(method)) {
        require(connector, member, ApprovalMemberships.Role.REQUESTER, "approvals.request");
        JsonNode arguments = body(exchange, Set.of("groupId", "port", "cidr"));
        send(exchange, 201, request(connector, member, arguments, false));
      } else if ("GET".equals(method)) {
        requireRead(connector, member);
        ObjectNode page = JsonSupport.MAPPER.createObjectNode().put("mode", "simulated");
        var items = page.putArray("items");
        String owner = member.roles().equals(Set.of(ApprovalMemberships.Role.REQUESTER))
            ? member.principalId() : null;
        var parameters = ApprovalHistoryParameters.parse(uri.getRawQuery(), member.tenant().value(), member.principalId(), owner);
        var history = state.approvalHistory(member.tenant(), owner, parameters.query());
        for (var approval : history.items()) {
          items.add(projection(approval, member));
        }
        page.put("pageSize", parameters.query().limit());
        if (history.hasMore()) page.put("nextCursor", parameters.nextCursor(history.items().getLast()));
        else page.putNull("nextCursor");
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
          && "cancel".equals(parts[1])) {
        require(connector, member, ApprovalMemberships.Role.REQUESTER, HumanApprovalService.CANCELLATION_SCOPE);
        ApprovalRequestState approval = owned(member, parts[0]);
        JsonNode intent = body(exchange, Set.of("requestHash"));
        if (!intent.path("requestHash").isTextual()
            || !intent.path("requestHash").asText().matches("^sha256:[a-f0-9]{64}$")) {
          throw failure(400, "INVALID_REQUEST");
        }
        checkHash(intent, approval);
        var deadline = connector.expiresAt();
        if (browserRequest) {
          var sessionDeadline = browser.authorityExpiresAt(exchange);
          if (sessionDeadline.isBefore(deadline)) deadline = sessionDeadline;
        }
        var requester = new TrustedRequesterContext(member.tenant(), member.principalId(), true,
            Set.of(HumanApprovalService.REQUESTER_ROLE), Set.of(HumanApprovalService.CANCELLATION_SCOPE),
            connector.authenticationProfile(), connector.credentialFingerprint().value(), deadline);
        send(exchange, 200, projection(approvals.cancel(requester, parts[0],
            intent.path("requestHash").asText()), member));
      } else if ("POST".equals(method) && parts.length == 2
          && "execute".equals(parts[1])) {
        require(connector, member, ApprovalMemberships.Role.REQUESTER, "approvals.execute");
        send(exchange, 200, execute(connector, member, parts[0],
            body(exchange, Set.of("requestHash")), false));
      } else throw failure(405, "METHOD_NOT_ALLOWED");
    } else throw failure(404, "NOT_FOUND");
  }

  private void status(HttpExchange exchange, Headers headers, URI uri, String path)
      throws IOException {
    if (!"GET".equals(exchange.getRequestMethod())) throw failure(405, "METHOD_NOT_ALLOWED");
    String contentLength = headers.getFirst("Content-Length");
    if (uri.getRawQuery() != null || headers.containsKey("Authorization")
        || headers.containsKey("Cookie") || headers.containsKey("Origin")
        || headers.getOrDefault("Content-Length", java.util.List.of()).size() > 1
        || (contentLength != null && !"0".equals(contentLength))
        || headers.containsKey("Transfer-Encoding")) {
      throw failure(400, "INVALID_REQUEST");
    }
    ObjectNode response = JsonSupport.MAPPER.createObjectNode()
        .put("schemaVersion", "1.0")
        .put("service", "tracepermit-simulator-approval");
    if ("/health".equals(path)) {
      response.put("status", "live");
      send(exchange, 200, response);
      return;
    }
    boolean ready;
    try {
      ready = readiness.getAsBoolean();
    } catch (RuntimeException failure) {
      ready = false;
    }
    response.put("status", ready ? "ready" : "not_ready");
    send(exchange, ready ? 200 : 503, response);
  }

  // Both ingress adapters converge here before any approval/permit/execution operation.
  private static Set<String> mcpTools(AuthenticatedConnector connector, ApprovalMemberships.Entry member) {
    if (!member.roles().contains(ApprovalMemberships.Role.REQUESTER)) return Set.of();
    Set<String> names = new java.util.HashSet<>();
    if (connector.hasScope(ConnectorScope.of("approvals.request"))) names.add(ApprovalMcpAdapter.REQUEST);
    if (connector.hasScope(ConnectorScope.of("approvals.read"))) names.add(ApprovalMcpAdapter.STATUS);
    if (connector.hasScope(ConnectorScope.of("approvals.execute"))) names.add(ApprovalMcpAdapter.RESUME);
    return Set.copyOf(names);
  }

  private ObjectNode request(AuthenticatedConnector connector, ApprovalMemberships.Entry member,
      JsonNode arguments, boolean mcp) {
    require(connector, member, ApprovalMemberships.Role.REQUESTER, "approvals.request");
    validateArguments(arguments);
    var pending = approvals.requestApprovalWithSnapshot(context(member), normalize(connector, member, arguments, mcp));
    return projection(pending.approval(), member);
  }

  private ApprovalRequestState owned(ApprovalMemberships.Entry member, String id) {
    ApprovalRequestState approval = visible(member, id);
    if (!member.principalId().equals(approval.binding().subjectId())) {
      throw failure(403, "REQUESTER_REQUIRED");
    }
    return approval;
  }

  private ObjectNode execute(AuthenticatedConnector connector, ApprovalMemberships.Entry member,
      String id, JsonNode arguments, boolean mcp) {
    require(connector, member, ApprovalMemberships.Role.REQUESTER, "approvals.execute");
    ApprovalRequestState approval = owned(member, id);
    checkHash(arguments, approval);
    JsonNode event = exactAction(approval);
    // MCP resumption cannot substitute a different enrolled client or origin. HTTP execution of
    // an MCP snapshot must satisfy the same actor binding, not provide a weaker alternate route.
    if ((mcp && !"mcp".equals(event.path("action").path("protocol").asText()))
        || ("mcp".equals(event.path("action").path("protocol").asText())
            && (!connector.clientId().equals(event.path("identity").path("actor").path("id").asText())
                || !connector.origin().value().equals(event.path("trigger").path("origin").asText())))) {
      throw failure(409, "APPROVAL_BINDING_MISMATCH");
    }
    if ("approved".equals(approval.status())
        && !memberships.isActiveReviewer(member.tenant(), approval.reviewerSubjectId())) {
      throw failure(403, "REVIEWER_AUTHORITY_REVOKED");
    }
    var executed = approvals.executeApproved(context(member), id, event);
    ObjectNode result = projection(executed.approval(), member);
    result.put("executionStatus", executed.outcome());
    return result;
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
    result.put("createdAt", approval.binding().createdAt().toString());
    result.put("expiresAt", approval.binding().expiresAt().toString());
    result.put("expired", !approval.binding().expiresAt().isAfter(clock.instant()));
    result.put("policyId", approval.binding().policyId());
    result.put("policyVersion", approval.binding().policyVersion());
    result.put("originalPolicyOutcome", approval.binding().originalDecision());
    result.set("action", EvidenceRedactor.enterpriseDefault().redact(event.path("action")));
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
      JsonNode arguments, boolean mcp) {
    AuthenticatedConnector pseudonymous = new AuthenticatedConnector(member.principalId(),
        "Enrolled approval principal", member.tenant().value(), connector.origin(), connector.clientId(),
        connector.clientName(), connector.issuer(), connector.verifiedAt(),
        connector.authenticationProfile(), connector.verificationKeyId(),
        connector.credentialFingerprint(), connector.scopes(), connector.expiresAt());
    ObjectNode event = (ObjectNode) EnforcingMcpDispatcher.buildStandardEvent(
        "aws.ec2.update_security_group", "aws", "prod", "security-group", arguments,
        UUID.randomUUID().toString(), UUID.randomUUID().toString().replace("-", ""),
        clock.instant(), pseudonymous, Optional.empty());
    ((ObjectNode) event.path("identity").path("subject")).put("type", member.human() ? "human" : "workload");
    // A bearer proves a principal, not that a human typed the prompt or which executable ran.
    ((ObjectNode) event.path("trigger")).put("kind", "service").put("origin", mcp ? connector.origin().value() : "api");
    ((ObjectNode) event.path("action")).put("protocol", mcp ? "mcp" : "http");
    ((ObjectNode) event.path("clientClaims")).put("transport", mcp ? "loopback-http-approval-mcp" : "loopback-http-approval-api");
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
    JsonNode body = readBody(exchange);
    if (body.size() != fields.size()) throw failure(400, "INVALID_REQUEST");
    var names = body.fieldNames();
    while (names.hasNext()) if (!fields.contains(names.next())) throw failure(400, "INVALID_REQUEST");
    return body;
  }

  private static JsonNode readBody(HttpExchange exchange) throws IOException {
    var types = exchange.getRequestHeaders().getOrDefault("Content-Type", java.util.List.of());
    if (types.size() != 1 || !Set.of("application/json", "application/json; charset=utf-8").contains(types.getFirst())) {
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
    if (body == null || !body.isObject()) {
      throw failure(400, "INVALID_REQUEST");
    }
    return body;
  }

  private static HttpFailure failure(int status, String code) { return new HttpFailure(status, code); }

  static final class HttpFailure extends RuntimeException {
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
