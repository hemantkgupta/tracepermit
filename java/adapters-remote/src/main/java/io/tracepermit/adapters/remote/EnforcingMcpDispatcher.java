package io.tracepermit.adapters.remote;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.ActionEnforcer;
import io.tracepermit.application.ExecutionResult;
import io.tracepermit.application.TargetExecutionException;
import io.tracepermit.application.TenantActionEnforcer;
import io.tracepermit.application.TrustedTenantContext;
import io.tracepermit.contracts.ActionValidator;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.identity.AuthenticatedConnector;
import io.tracepermit.identity.ConnectorOrigin;
import io.tracepermit.identity.ConnectorScope;
import io.tracepermit.identity.ScopeAuthorizer;
import io.tracepermit.identity.VerifiedSourceEvidence;
import io.tracepermit.security.ExecutionPermitException;
import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Remote MCP dispatcher that maps a bounded tool call into the complete Java enforcement path.
 * Authentication remains the responsibility of {@link RemoteOAuthMcpServer}.
 */
public final class EnforcingMcpDispatcher implements RemoteMcpDispatcher {
  public static final String FILTER_LOG_EVENTS = "aws.logs.filter_log_events";
  public static final String TERMINATE_INSTANCES = "aws.ec2.terminate_instances";
  public static final String GET_PROFILE = "tracepermit.get_profile";
  private static final ConnectorScope INVOCATION_SCOPE = ConnectorScope.of("aws.logs.read");
  private static final Set<String> CALL_FIELDS = Set.of("name", "arguments", "_meta");
  private static final Set<String> LOG_FIELDS = Set.of("logGroupName", "filterPattern", "limit");
  private static final Set<String> TERMINATE_FIELDS = Set.of("instanceIds", "region");
  private static final Set<String> LOG_GROUPS = Set.of("/app/prod", "/app/staging");
  private static final Set<String> REGIONS = Set.of("us-east-1", "us-west-2");
  private static final Pattern INSTANCE_ID = Pattern.compile("^i-simulated-[a-z0-9-]{1,32}$");
  static final int DEFAULT_ENFORCEMENT_CAPACITY = 64;

  private final HandshakeOnlyMcpDispatcher foundation = new HandshakeOnlyMcpDispatcher();
  private final TenantActionEnforcer enforcer;
  private final Clock clock;
  private final Supplier<String> createEventId;
  private final Supplier<String> createTraceId;
  private final McpOutcomeObserver outcomeObserver;
  private final boolean hostedClientOAuthMetadata;
  private final McpToolRegistry toolRegistry;
  private final Semaphore enforcementCapacity;

  public EnforcingMcpDispatcher(
      ActionEnforcer enforcer,
      Clock clock,
      Supplier<String> createEventId,
      Supplier<String> createTraceId) {
    this(asTenantEnforcer(enforcer), clock, createEventId, createTraceId, McpOutcomeObserver.NONE);
  }

  public EnforcingMcpDispatcher(
      TenantActionEnforcer enforcer,
      Clock clock,
      Supplier<String> createEventId,
      Supplier<String> createTraceId) {
    this(enforcer, clock, createEventId, createTraceId, McpOutcomeObserver.NONE);
  }

  public EnforcingMcpDispatcher(
      ActionEnforcer enforcer,
      Clock clock,
      Supplier<String> createEventId,
      Supplier<String> createTraceId,
      McpOutcomeObserver outcomeObserver) {
    this(asTenantEnforcer(enforcer), clock, createEventId, createTraceId, outcomeObserver, false);
  }

  public EnforcingMcpDispatcher(
      ActionEnforcer enforcer,
      Clock clock,
      Supplier<String> createEventId,
      Supplier<String> createTraceId,
      McpOutcomeObserver outcomeObserver,
      boolean hostedClientOAuthMetadata) {
    this(
        asTenantEnforcer(enforcer),
        clock,
        createEventId,
        createTraceId,
        outcomeObserver,
        hostedClientOAuthMetadata);
  }

  public EnforcingMcpDispatcher(
      TenantActionEnforcer enforcer,
      Clock clock,
      Supplier<String> createEventId,
      Supplier<String> createTraceId,
      McpOutcomeObserver outcomeObserver) {
    this(enforcer, clock, createEventId, createTraceId, outcomeObserver, false);
  }

  public EnforcingMcpDispatcher(
      ActionEnforcer enforcer,
      Clock clock,
      Supplier<String> createEventId,
      Supplier<String> createTraceId,
      McpOutcomeObserver outcomeObserver,
      McpToolRegistry toolRegistry) {
    this(asTenantEnforcer(enforcer), clock, createEventId, createTraceId, outcomeObserver, false, toolRegistry);
  }

  public EnforcingMcpDispatcher(
      ActionEnforcer enforcer,
      Clock clock,
      Supplier<String> createEventId,
      Supplier<String> createTraceId,
      McpOutcomeObserver outcomeObserver,
      boolean hostedClientOAuthMetadata,
      McpToolRegistry toolRegistry) {
    this(asTenantEnforcer(enforcer), clock, createEventId, createTraceId, outcomeObserver,
        hostedClientOAuthMetadata, toolRegistry);
  }

  public EnforcingMcpDispatcher(
      TenantActionEnforcer enforcer,
      Clock clock,
      Supplier<String> createEventId,
      Supplier<String> createTraceId,
      McpOutcomeObserver outcomeObserver,
      McpToolRegistry toolRegistry) {
    this(enforcer, clock, createEventId, createTraceId, outcomeObserver, false, toolRegistry);
  }

  public EnforcingMcpDispatcher(
      TenantActionEnforcer enforcer,
      Clock clock,
      Supplier<String> createEventId,
      Supplier<String> createTraceId,
      McpOutcomeObserver outcomeObserver,
      boolean hostedClientOAuthMetadata) {
    this(enforcer, clock, createEventId, createTraceId, outcomeObserver, hostedClientOAuthMetadata, createDefaultRegistry());
  }

  public EnforcingMcpDispatcher(
      TenantActionEnforcer enforcer,
      Clock clock,
      Supplier<String> createEventId,
      Supplier<String> createTraceId,
      McpOutcomeObserver outcomeObserver,
      boolean hostedClientOAuthMetadata,
      McpToolRegistry toolRegistry) {
    this(
        enforcer,
        clock,
        createEventId,
        createTraceId,
        outcomeObserver,
        hostedClientOAuthMetadata,
        toolRegistry,
        DEFAULT_ENFORCEMENT_CAPACITY);
  }

  /**
   * Full construction with an explicit bound on concurrently executing enforcement
   * attempts. The bound is admission control, not a throughput target or per-tenant
   * fairness guarantee: tryAcquire is non-blocking admission, and saturated capacity
   * fails closed immediately without queueing. Admitted calls can still wait on
   * downstream principal stripes, sequence stores, execution ledgers, or target locks.
   */
  public EnforcingMcpDispatcher(
      TenantActionEnforcer enforcer,
      Clock clock,
      Supplier<String> createEventId,
      Supplier<String> createTraceId,
      McpOutcomeObserver outcomeObserver,
      boolean hostedClientOAuthMetadata,
      McpToolRegistry toolRegistry,
      int enforcementConcurrency) {
    this.enforcer = Objects.requireNonNull(enforcer);
    this.clock = Objects.requireNonNull(clock);
    this.createEventId = Objects.requireNonNull(createEventId);
    this.createTraceId = Objects.requireNonNull(createTraceId);
    this.outcomeObserver = Objects.requireNonNull(outcomeObserver);
    this.hostedClientOAuthMetadata = hostedClientOAuthMetadata;
    this.toolRegistry = Objects.requireNonNull(toolRegistry).snapshot();
    if (enforcementConcurrency < 1 || enforcementConcurrency > 1_024) {
      throw new IllegalArgumentException("Enforcement concurrency is outside its safe bound.");
    }
    this.enforcementCapacity = new Semaphore(enforcementConcurrency);
  }

  public McpToolRegistry toolRegistry() {
    return toolRegistry;
  }

  private static TenantActionEnforcer asTenantEnforcer(ActionEnforcer enforcer) {
    Objects.requireNonNull(enforcer);
    return (context, event) -> enforcer.execute(event);
  }

  @Override
  public RemoteMcpResponse dispatch(JsonNode request, AuthenticatedConnector connector) {
    return dispatch(request, connector, Optional.empty());
  }

  @Override
  public Optional<RemoteMcpResponse> dispatchPublic(JsonNode request) {
    Objects.requireNonNull(request);
    if (!hostedClientOAuthMetadata) return Optional.empty();
    String method = request.path("method").asText();
    if (!Set.of("server/discover", "initialize", "notifications/initialized", "ping", "tools/list")
        .contains(method)) {
      return Optional.empty();
    }
    RemoteMcpResponse foundationResponse = foundation.dispatchPublic(request).orElseThrow();
    return Optional.of(
        switch (method) {
          case "server/discover", "initialize" -> invocationEnabled(foundationResponse);
          case "tools/list" ->
              withDestructiveSimulation(foundationResponse, hostedClientOAuthMetadata);
          default -> foundationResponse;
        });
  }

  /**
   * Dispatches a request after a trusted transport has verified optional endpoint source evidence.
   * Remote web transports call the two-argument form; only the loopback development adapter may
   * supply CLI source evidence.
   */
  public RemoteMcpResponse dispatch(
      JsonNode request,
      AuthenticatedConnector connector,
      Optional<VerifiedSourceEvidence> sourceEvidence) {
    Objects.requireNonNull(sourceEvidence);
    RemoteMcpResponse foundationResponse = foundation.dispatch(request, connector);
    String method = request.path("method").asText();
    return switch (method) {
      case "server/discover", "initialize" -> invocationEnabled(foundationResponse);
      case "tools/list" ->
          withDestructiveSimulation(foundationResponse, hostedClientOAuthMetadata);
      case "tools/call" ->
          isError(foundationResponse, "REMOTE_TOOL_INVOCATION_NOT_CONFIGURED")
              ? call(request, connector, sourceEvidence)
              : foundationResponse;
      default -> foundationResponse;
    };
  }

  private RemoteMcpResponse call(
      JsonNode request,
      AuthenticatedConnector connector,
      Optional<VerifiedSourceEvidence> sourceEvidence) {
    JsonNode id = request.get("id");
    if (!validId(id)) {
      return jsonRpcError(id, -32600, "INVALID_REQUEST", "Tool calls require a bounded request ID.");
    }
    JsonNode params = request.get("params");
    if (params == null
        || !params.isObject()
        || !fields(params).stream().allMatch(CALL_FIELDS::contains)
        || (params.has("_meta") && !params.path("_meta").isObject())) {
      return invalidArguments(id);
    }
    JsonNode name = params.get("name");
    JsonNode arguments = params.get("arguments");
    if (!boundedText(name, 128) || arguments == null || !arguments.isObject()) {
      return invalidArguments(id);
    }
    if (hostedClientOAuthMetadata && GET_PROFILE.equals(name.textValue())) {
      return profile(id, arguments, connector);
    }
    McpToolDefinition tool = toolRegistry.find(name.textValue()).orElse(null);
    if (tool == null) {
      return jsonRpcError(
          id, -32602, "TOOL_NOT_FOUND", "The requested MCP tool is not registered.");
    }
    boolean webOrigin =
        connector.origin() == ConnectorOrigin.CHATGPT_WEB
            || connector.origin() == ConnectorOrigin.CLAUDE_WEB;
    boolean attestedCli =
        connector.origin() == ConnectorOrigin.CLI && sourceEvidence.isPresent();
    if ((!webOrigin && !attestedCli) || (webOrigin && sourceEvidence.isPresent())) {
      return jsonRpcError(
          id,
          -32602,
          "REMOTE_CONNECTOR_ORIGIN_NOT_SUPPORTED",
          "This connector origin is not enabled for remote invocation.");
    }

    ScopeAuthorizer.require(connector, tool.requiredScope());
    JsonNode normalizedArguments;
    try {
      normalizedArguments = tool.normalizeArguments(arguments);
    } catch (RuntimeException exception) {
      return invalidArguments(id);
    }
    if (normalizedArguments == null || !normalizedArguments.isObject()) return invalidArguments(id);

    JsonNode event;
    try {
      event = ActionValidator.validate(
          tool.createEvent(
              createEventId.get(),
              createTraceId.get(),
              clock.instant(),
              normalizedArguments,
              connector,
              sourceEvidence));
    } catch (RuntimeException exception) {
      return jsonRpcError(
          id, -32603, "INVALID_ACTION", "The remote action could not be normalized safely.");
    }

    if (!enforcementCapacity.tryAcquire()) {
      return failedToolResult(
          id,
          "ENFORCEMENT_CAPACITY_EXCEEDED",
          "The action was not executed; enforcement capacity is saturated.",
          "not_executed");
    }
    try {
      return enforce(id, connector, event);
    } finally {
      enforcementCapacity.release();
    }
  }

  private RemoteMcpResponse enforce(
      JsonNode id, AuthenticatedConnector connector, JsonNode event) {
    try {
      TrustedTenantContext tenant =
          new TrustedTenantContext(new TenantPartition(connector.organizationId()));
      long startedAt = System.nanoTime();
      ExecutionResult result = enforcer.execute(tenant, event);
      notifyObserver(event, result, elapsedMillis(startedAt));
      return toolResult(id, result);
    } catch (TargetExecutionException exception) {
      return failedToolResult(
          id, "TARGET_EXECUTION_FAILED", "The simulated target call failed.", "failed");
    } catch (ExecutionPermitException exception) {
      return failedToolResult(
          id,
          "EXECUTION_PERMIT_ISSUANCE_FAILED",
          "Execution authority could not be established.",
          "not_executed");
    } catch (IllegalStateException exception) {
      String state = exception.getMessage();
      if (state == null) {
        return failedToolResult(
            id, "REMOTE_ENFORCEMENT_FAILED", "The action outcome is unknown.", "unknown");
      }
      return switch (state) {
        case "SECURITY_DEPENDENCY_UNAVAILABLE" ->
            failedToolResult(
                id,
                "SECURITY_DEPENDENCY_UNAVAILABLE",
                "The action was not executed.",
                "not_executed");
        case "SEQUENCE_STATE_UNAVAILABLE" ->
            failedToolResult(
                id,
                "SEQUENCE_STATE_UNAVAILABLE",
                "The action was not executed.",
                "not_executed");
        case "EXECUTION_ALREADY_IN_PROGRESS" ->
            failedToolResult(
                id, "EXECUTION_ALREADY_IN_PROGRESS", "The action is already executing.", "executing");
        case "EXECUTION_OUTCOME_UNKNOWN" ->
            failedToolResult(
                id, "EXECUTION_OUTCOME_UNKNOWN", "The action outcome is unknown.", "unknown");
        case "EXECUTION_PREVIOUSLY_FAILED" ->
            failedToolResult(
                id, "EXECUTION_PREVIOUSLY_FAILED", "The action previously failed.", "failed");
        default ->
            failedToolResult(
                id, "REMOTE_ENFORCEMENT_FAILED", "The action outcome is unknown.", "unknown");
      };
    } catch (RuntimeException exception) {
      return failedToolResult(
          id, "REMOTE_ENFORCEMENT_FAILED", "The action outcome is unknown.", "unknown");
    }
  }

  private void notifyObserver(JsonNode event, ExecutionResult result, long latencyMs) {
    try {
      outcomeObserver.completed(event.deepCopy(), result, latencyMs);
    } catch (RuntimeException ignored) {
      // Read projections are deliberately non-authoritative. Enforcement already completed.
    }
  }

  private static long elapsedMillis(long startedAt) {
    return Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
  }

  public JsonNode createEvent(
      String toolName,
      JsonNode arguments,
      AuthenticatedConnector connector,
      Optional<VerifiedSourceEvidence> sourceEvidence) {
    McpToolDefinition tool = toolRegistry.find(toolName).orElseThrow(
        () -> new IllegalArgumentException("The requested MCP tool is not registered."));
    JsonNode normalized = tool.normalizeArguments(arguments);
    if (normalized == null) throw new IllegalArgumentException("The tool arguments are invalid.");
    return tool.createEvent(
        createEventId.get(),
        createTraceId.get(),
        clock.instant(),
        normalized,
        connector,
        sourceEvidence);
  }

  public static JsonNode buildStandardEvent(
      String toolName,
      String targetSystem,
      String targetEnvironment,
      String resourceType,
      JsonNode arguments,
      String eventId,
      String traceId,
      Instant observedAt,
      AuthenticatedConnector connector,
      Optional<VerifiedSourceEvidence> sourceEvidence) {
    Objects.requireNonNull(eventId);
    Objects.requireNonNull(traceId);
    Objects.requireNonNull(observedAt);
    String principalHopId = "principal:" + eventId;
    String processHopId = "process:" + eventId;
    String connectorHopId = "connector:" + eventId;
    VerifiedSourceEvidence source = sourceEvidence.orElse(null);
    String processSubjectId =
        source == null
            ? null
            : "process:"
                + source.device().deviceId()
                + ":"
                + source.process().executableName();

    ObjectNode event = JsonSupport.MAPPER.createObjectNode();
    event.put("schemaVersion", "1.0");
    event.put("eventId", eventId);
    event.put("observedAt", observedAt.toString());
    event.put("traceId", traceId);

    ObjectNode identity = event.putObject("identity");
    ObjectNode subject = identity.putObject("subject");
    subject.put("id", connector.subjectId());
    subject.put("type", "human");
    subject.put("displayName", connector.displayName());
    subject.put("organizationId", connector.organizationId());
    subject.put("authenticationMethod", "oidc");
    subject.put("assurance", "authenticated");
    ObjectNode actor = identity.putObject("actor");
    actor.put("id", source == null ? connector.clientId() : processSubjectId);
    actor.put("type", source == null ? "orchestrator" : "agent");
    actor.put(
        "displayName", source == null ? connector.clientName() : source.process().executableName());
    actor.put(
        "organizationId",
        source == null ? "external-provider" : connector.organizationId());
    actor.put("authenticationMethod", source == null ? "client_metadata" : "os");
    actor.put("assurance", source == null ? "asserted" : "authenticated");
    ArrayNode evidence = identity.putArray("evidence");
    ObjectNode tokenEvidence = evidence.addObject();
    tokenEvidence.put("evidenceId", "oidc:" + eventId);
    tokenEvidence.put("subjectId", connector.subjectId());
    tokenEvidence.put("kind", "oidc_token");
    tokenEvidence.put("assurance", "authenticated");
    tokenEvidence.put("issuer", connector.issuer());
    tokenEvidence.put("verifiedAt", connector.verifiedAt().toString());
    tokenEvidence.put("credentialFingerprint", connector.credentialFingerprint().value());
    connector
        .senderConstraint()
        .ifPresent(
            constraint -> {
              ObjectNode senderEvidence = evidence.addObject();
              senderEvidence.put("evidenceId", "sender:" + eventId);
              senderEvidence.put("subjectId", connector.clientId());
              senderEvidence.put("kind", "mtls_certificate");
              senderEvidence.put("assurance", "authenticated");
              senderEvidence.put("issuer", "direct-tls-peer");
              senderEvidence.put("verifiedAt", observedAt.toString());
              senderEvidence.put(
                  "credentialFingerprint", constraint.certificateFingerprint().value());
            });
    ObjectNode endpointEvidence = evidence.addObject();
    endpointEvidence.put("evidenceId", (source == null ? "client:" : "endpoint:") + eventId);
    endpointEvidence.put("subjectId", source == null ? connector.clientId() : processSubjectId);
    endpointEvidence.put("kind", source == null ? "client_metadata" : "os_session");
    endpointEvidence.put("assurance", source == null ? "asserted" : "authenticated");
    endpointEvidence.put("issuer", source == null ? "mcp-transport" : source.issuer());
    if (source != null) {
      endpointEvidence.put("verifiedAt", source.verifiedAt().toString());
      endpointEvidence.put("credentialFingerprint", source.keyFingerprint());
    }

    ObjectNode trigger = event.putObject("trigger");
    trigger.put("kind", "human");
    trigger.put("origin", connector.origin().value());
    trigger.put("requestId", "mcp:" + eventId);
    trigger.put("clientName", connector.clientName());

    ObjectNode action = event.putObject("action");
    action.put("protocol", "mcp");
    action.put("toolName", toolName);
    action.set("arguments", arguments.deepCopy());
    ObjectNode target = action.putObject("target");
    target.put("system", targetSystem);
    target.put("environment", targetEnvironment);
    target.put("accountId", "simulated-123");
    target.put("resourceType", resourceType);

    if (source != null) event.set("source", sourceProjection(source));

    ObjectNode lineage = event.putObject("lineage");
    lineage.put("rootPrincipalId", connector.subjectId());
    ArrayNode hops = lineage.putArray("hops");
    ObjectNode principalHop = hops.addObject();
    principalHop.put("hopId", principalHopId);
    principalHop.put("kind", "principal");
    principalHop.put("subjectId", connector.subjectId());
    principalHop.put("assurance", "authenticated");
    principalHop.put("source", connector.issuer());
    principalHop.put("observedAt", connector.verifiedAt().toString());
    if (source != null) {
      ObjectNode processHop = hops.addObject();
      processHop.put("hopId", processHopId);
      processHop.put("parentHopId", principalHopId);
      processHop.put("kind", "agent");
      processHop.put("subjectId", processSubjectId);
      processHop.put("assurance", "authenticated");
      processHop.put("source", source.issuer());
      processHop.put("observedAt", source.verifiedAt().toString());
    }
    ObjectNode connectorHop = hops.addObject();
    connectorHop.put("hopId", connectorHopId);
    connectorHop.put("parentHopId", source == null ? principalHopId : processHopId);
    connectorHop.put("kind", "orchestrator");
    connectorHop.put("subjectId", connector.clientId());
    connectorHop.put("assurance", "asserted");
    connectorHop.put("source", "mcp-transport");
    connectorHop.put("observedAt", observedAt.toString());
    lineage.putArray("delegations");
    ObjectNode clientClaims = event.putObject("clientClaims");
    clientClaims.put("transport", source == null ? "direct-tls-mcp" : "loopback-http-mcp");
    clientClaims.put("authenticationProfile", connector.authenticationProfile());
    clientClaims.put("verificationKeyId", connector.verificationKeyId());
    return ActionValidator.validate(event);
  }

  public static McpToolRegistry createDefaultRegistry() {
    McpToolRegistry registry = new McpToolRegistry();
    registry.register(new FilterLogEventsTool());
    registry.register(new TerminateInstancesTool());
    return registry;
  }

  private static final class FilterLogEventsTool implements McpToolDefinition {
    @Override
    public String name() {
      return FILTER_LOG_EVENTS;
    }

    @Override
    public String description() {
      return "Read bounded, strictly simulated AWS log events.";
    }

    @Override
    public JsonNode inputSchema() {
      ObjectNode schema = JsonSupport.MAPPER.createObjectNode();
      schema.put("type", "object");
      schema.put("additionalProperties", false);
      schema.putArray("required").add("logGroupName");
      ObjectNode properties = schema.putObject("properties");
      ObjectNode logGroup = properties.putObject("logGroupName");
      logGroup.put("type", "string");
      logGroup.set("enum", JsonSupport.MAPPER.valueToTree(LOG_GROUPS.stream().sorted().toList()));
      ObjectNode filterPattern = properties.putObject("filterPattern");
      filterPattern.put("type", "string");
      filterPattern.put("maxLength", 128);
      ObjectNode limit = properties.putObject("limit");
      limit.put("type", "integer");
      limit.put("minimum", 1);
      limit.put("maximum", 1_000);
      limit.put("default", 20);
      limit.put("description", "Requested result window; values above 100 require server-side sanitization.");
      return schema;
    }

    @Override
    public ConnectorScope requiredScope() {
      return INVOCATION_SCOPE;
    }

    @Override
    public JsonNode normalizeArguments(JsonNode rawArguments) {
      return normalizeLogArguments(rawArguments);
    }

    @Override
    public JsonNode createEvent(
        String eventId,
        String traceId,
        Instant observedAt,
        JsonNode normalizedArguments,
        AuthenticatedConnector connector,
        Optional<VerifiedSourceEvidence> sourceEvidence) {
      String environment =
          "/app/staging".equals(normalizedArguments.path("logGroupName").asText())
              ? "staging"
              : "prod";
      return buildStandardEvent(
          FILTER_LOG_EVENTS,
          "aws",
          environment,
          "cloudwatch-log-group",
          normalizedArguments,
          eventId,
          traceId,
          observedAt,
          connector,
          sourceEvidence);
    }
  }

  private static final class TerminateInstancesTool implements McpToolDefinition {
    @Override
    public String name() {
      return TERMINATE_INSTANCES;
    }

    @Override
    public String description() {
      return "Request a strictly simulated EC2 termination; policy blocks it before execution.";
    }

    @Override
    public JsonNode inputSchema() {
      return terminateInputSchema();
    }

    @Override
    public ConnectorScope requiredScope() {
      return INVOCATION_SCOPE;
    }

    @Override
    public JsonNode normalizeArguments(JsonNode rawArguments) {
      return normalizeTerminateArguments(rawArguments);
    }

    @Override
    public JsonNode createEvent(
        String eventId,
        String traceId,
        Instant observedAt,
        JsonNode normalizedArguments,
        AuthenticatedConnector connector,
        Optional<VerifiedSourceEvidence> sourceEvidence) {
      return buildStandardEvent(
          TERMINATE_INSTANCES,
          "aws",
          "prod",
          "ec2-instance",
          normalizedArguments,
          eventId,
          traceId,
          observedAt,
          connector,
          sourceEvidence);
    }
  }

  private static ObjectNode sourceProjection(VerifiedSourceEvidence source) {
    ObjectNode projection = JsonSupport.MAPPER.createObjectNode();
    projection.put("issuer", source.issuer());
    projection.put("keyId", source.keyId());
    projection.put("keyFingerprint", source.keyFingerprint());
    projection.put("verifiedAt", source.verifiedAt().toString());
    ObjectNode device = projection.putObject("device");
    device.put("deviceId", source.device().deviceId());
    device.put("platform", source.device().platform());
    device.put("assurance", source.device().assurance());
    ObjectNode process = projection.putObject("process");
    process.put("executableName", source.process().executableName());
    process.put("executableHash", source.process().executableHash());
    process.put("parentExecutableName", source.process().parentExecutableName());
    process.put("startedAt", source.process().startedAt().toString());
    ObjectNode trigger = projection.putObject("triggerEvidence");
    trigger.put("requestedMode", source.triggerEvidence().requestedMode());
    trigger.put("terminalAttached", source.triggerEvidence().terminalAttached());
    return projection;
  }

  private static JsonNode normalizeLogArguments(JsonNode input) {
    if (!fields(input).stream().allMatch(LOG_FIELDS::contains)) return null;
    String logGroup = text(input.get("logGroupName"), 32);
    if (logGroup == null || !LOG_GROUPS.contains(logGroup)) return null;
    String filter = input.has("filterPattern") ? text(input.get("filterPattern"), 128) : "";
    if (filter == null) return null;
    int limit = input.has("limit") ? integer(input.get("limit"), 1, 1_000) : 20;
    if (limit < 0) return null;
    return JsonSupport.MAPPER.createObjectNode()
        .put("logGroupName", logGroup)
        .put("filterPattern", filter)
        .put("limit", limit);
  }

  private static JsonNode normalizeTerminateArguments(JsonNode input) {
    if (!fields(input).stream().allMatch(TERMINATE_FIELDS::contains)) return null;
    JsonNode ids = input.get("instanceIds");
    if (ids == null || !ids.isArray() || ids.isEmpty() || ids.size() > 10) return null;
    ArrayNode normalizedIds = JsonSupport.MAPPER.createArrayNode();
    for (JsonNode id : ids) {
      if (!boundedText(id, 48) || !INSTANCE_ID.matcher(id.textValue()).matches()) return null;
      normalizedIds.add(id.textValue());
    }
    String region = input.has("region") ? text(input.get("region"), 16) : "us-east-1";
    if (region == null || !REGIONS.contains(region)) return null;
    ObjectNode normalized = JsonSupport.MAPPER.createObjectNode();
    normalized.set("instanceIds", normalizedIds);
    normalized.put("region", region);
    return normalized;
  }

  private static RemoteMcpResponse invocationEnabled(RemoteMcpResponse response) {
    if (response.status() != 200 || !response.body().path("result").isObject()) return response;
    ObjectNode body = response.body().deepCopy();
    ((ObjectNode) body.path("result"))
        .put(
            "instructions",
            "Bounded simulated tools are independently authorized and policy enforced before execution.");
    return new RemoteMcpResponse(200, body);
  }

  private RemoteMcpResponse withDestructiveSimulation(
      RemoteMcpResponse response, boolean hostedClientOAuthMetadata) {
    if (response.status() != 200 || !response.body().path("result").path("tools").isArray()) {
      return response;
    }
    ObjectNode body = response.body().deepCopy();
    ArrayNode tools = ((ObjectNode) body.path("result")).putArray("tools");
    for (McpToolDefinition tool : toolRegistry.allTools()) {
      ObjectNode entry = tools.addObject();
      entry.put("name", tool.name());
      if (FILTER_LOG_EVENTS.equals(tool.name())) {
        entry.put("title", "Read simulated AWS logs");
      } else if (TERMINATE_INSTANCES.equals(tool.name())) {
        entry.put("title", "Request simulated EC2 termination");
      }
      entry.put("description", tool.description());
      entry.set("inputSchema", tool.inputSchema());
      if (hostedClientOAuthMetadata) oauthScheme(entry, tool.requiredScope().value());
      boolean readOnly = FILTER_LOG_EVENTS.equals(tool.name());
      annotations(entry, readOnly, !readOnly);
    }

    if (hostedClientOAuthMetadata) {
      ObjectNode profile = tools.addObject();
      profile.put("name", GET_PROFILE);
      profile.put("title", "Get authenticated TracePermit profile");
      profile.put(
          "description",
          "Return the stable profile represented by the current verified OAuth credential.");
      ObjectNode profileInput = profile.putObject("inputSchema");
      profileInput.put("type", "object");
      profileInput.putObject("properties");
      profileInput.put("additionalProperties", false);
      ObjectNode output = profile.putObject("outputSchema");
      output.put("$schema", "https://json-schema.org/draft/2020-12/schema");
      output.put("type", "object");
      ObjectNode outputProperties = output.putObject("properties");
      outputProperties
          .putObject("id")
          .put("type", "string")
          .put("minLength", 1)
          .put("pattern", "\\S")
          .put(
              "description",
              "Opaque stable profile identifier derived from the verified issuer and subject.");
      outputProperties
          .putObject("name")
          .put("type", "string")
          .put("description", "Display name from the verified identity provider token.");
      output.putArray("required").add("id");
      output.put("additionalProperties", false);
      oauthScheme(profile, "aws.logs.discover");
      annotations(profile, true, false);
      profile.putObject("_meta").put("openai/profile", true);
    }
    return new RemoteMcpResponse(200, body);
  }

  public static JsonNode terminateInputSchema() {
    ObjectNode schema = JsonSupport.MAPPER.createObjectNode();
    schema.put("type", "object");
    schema.put("additionalProperties", false);
    schema.putArray("required").add("instanceIds");
    ObjectNode properties = schema.putObject("properties");
    ObjectNode instanceIds = properties.putObject("instanceIds");
    instanceIds.put("type", "array");
    instanceIds.put("minItems", 1);
    instanceIds.put("maxItems", 10);
    instanceIds.putObject("items").put("type", "string").put("pattern", INSTANCE_ID.pattern());
    ObjectNode region = properties.putObject("region");
    region.put("type", "string");
    region.set("enum", JsonSupport.MAPPER.valueToTree(REGIONS.stream().sorted().toList()));
    region.put("default", "us-east-1");
    return schema;
  }

  private static void oauthScheme(ObjectNode tool, String scope) {
    ObjectNode scheme = tool.putArray("securitySchemes").addObject();
    scheme.put("type", "oauth2");
    scheme.putArray("scopes").add(scope);
  }

  private static void annotations(ObjectNode tool, boolean readOnly, boolean destructive) {
    ObjectNode annotations = tool.putObject("annotations");
    annotations.put("readOnlyHint", readOnly);
    annotations.put("destructiveHint", destructive);
    annotations.put("openWorldHint", false);
  }

  private static RemoteMcpResponse profile(
      JsonNode id, JsonNode arguments, AuthenticatedConnector connector) {
    if (arguments.size() != 0) return invalidArguments(id);
    ObjectNode profile = JsonSupport.MAPPER.createObjectNode();
    profile.put("id", stableProfileId(connector));
    profile.put("name", connector.displayName());
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    result.put("isError", false);
    result.set("structuredContent", profile.deepCopy());
    ObjectNode item = result.putArray("content").addObject();
    item.put("type", "text");
    item.put("text", profile.toString());
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id.deepCopy());
    response.set("result", result);
    return new RemoteMcpResponse(200, response);
  }

  private static String stableProfileId(AuthenticatedConnector connector) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256")
              .digest(connector.subjectId().getBytes(StandardCharsets.UTF_8));
      return "prf_" + HexFormat.of().formatHex(digest, 0, 16);
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 is unavailable.", exception);
    }
  }

  private static RemoteMcpResponse toolResult(JsonNode id, ExecutionResult result) {
    ObjectNode body = JsonSupport.MAPPER.createObjectNode();
    body.put("schemaVersion", "1.0");
    body.put("simulated", true);
    ObjectNode evaluation = body.putObject("evaluation");
    evaluation.set("classification", classification(result));
    evaluation.set("decision", decision(result));
    ObjectNode execution = body.putObject("execution");
    switch (result.outcome()) {
      case "succeeded" -> {
        execution.put(
            "status", result.sanitization() == null ? "executed" : "executed_sanitized");
        execution.set("result", result.result());
        if (result.sanitization() != null) {
          ObjectNode sanitized = execution.putObject("sanitization");
          sanitized.put("transformerId", result.sanitization().transformerId());
          sanitized.set(
              "changes", JsonSupport.MAPPER.valueToTree(result.sanitization().changes()));
          sanitized.put("effectiveEventId", result.sanitization().effectiveEventId());
          sanitized.put(
              "finalDecision", result.effectiveEvaluation().decision().outcome());
        }
      }
      case "block" -> execution.put("status", "denied");
      case "require_approval" -> execution.put("status", "pending_approval");
      default -> execution.put("status", "not_executed");
    }
    return contentResult(id, !"succeeded".equals(result.outcome()), body);
  }

  private static ObjectNode classification(ExecutionResult result) {
    ObjectNode value = JsonSupport.MAPPER.createObjectNode();
    value.put("actionClass", result.evaluation().classification().actionClass());
    value.put("severity", result.evaluation().classification().severity());
    value.put("score", result.evaluation().classification().score());
    value.put("operation", result.evaluation().classification().operation());
    value.set("signals", JsonSupport.MAPPER.valueToTree(result.evaluation().classification().signals()));
    value.set(
        "semanticIntents",
        JsonSupport.MAPPER.valueToTree(
            result.evaluation().classification().semanticIntents()));
    value.put("classifierKind", result.evaluation().classification().classifierKind());
    value.put("classifierVersion", result.evaluation().classification().classifierVersion());
    return value;
  }

  private static ObjectNode decision(ExecutionResult result) {
    ObjectNode value = JsonSupport.MAPPER.createObjectNode();
    value.put("outcome", result.evaluation().decision().outcome());
    value.put("reasonCode", result.evaluation().decision().reasonCode());
    value.put("policyId", result.evaluation().decision().policyId());
    value.put("policyVersion", result.evaluation().decision().policyVersion());
    if (result.evaluation().decision().matchedRuleId() != null) {
      value.put("matchedRuleId", result.evaluation().decision().matchedRuleId());
    }
    if (result.evaluation().decision().approvalGroup() != null) {
      value.put("approvalGroup", result.evaluation().decision().approvalGroup());
    }
    return value;
  }

  private static RemoteMcpResponse failedToolResult(
      JsonNode id, String code, String message, String executionStatus) {
    ObjectNode content = JsonSupport.MAPPER.createObjectNode();
    content.put("error", code);
    content.put("message", message);
    content.putObject("execution").put("status", executionStatus);
    return contentResult(id, true, content);
  }

  private static RemoteMcpResponse contentResult(JsonNode id, boolean error, JsonNode content) {
    ObjectNode result = JsonSupport.MAPPER.createObjectNode();
    if (error) result.put("isError", true);
    ObjectNode item = result.putArray("content").addObject();
    item.put("type", "text");
    item.put("text", content.toString());
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    response.set("id", id.deepCopy());
    response.set("result", result);
    return new RemoteMcpResponse(200, response);
  }

  private static RemoteMcpResponse invalidArguments(JsonNode id) {
    return jsonRpcError(
        id, -32602, "INVALID_TOOL_ARGUMENTS", "The MCP tool arguments are invalid.");
  }

  private static RemoteMcpResponse jsonRpcError(
      JsonNode id, int number, String code, String message) {
    ObjectNode response = JsonSupport.MAPPER.createObjectNode();
    response.put("jsonrpc", "2.0");
    if (validId(id)) response.set("id", id.deepCopy());
    else response.putNull("id");
    ObjectNode error = response.putObject("error");
    error.put("code", number);
    error.put("message", message);
    error.putObject("data").put("code", code);
    return new RemoteMcpResponse(200, response);
  }

  private static boolean isError(RemoteMcpResponse response, String code) {
    return response.body() != null
        && code.equals(response.body().path("error").path("data").path("code").asText());
  }

  private static Set<String> fields(JsonNode value) {
    Set<String> result = new java.util.HashSet<>();
    value.fieldNames().forEachRemaining(result::add);
    return Set.copyOf(result);
  }

  private static boolean validId(JsonNode value) {
    if (value == null || (!value.isTextual() && !value.isIntegralNumber())) return false;
    return !value.isTextual() || boundedText(value, 128);
  }

  private static boolean boundedText(JsonNode value, int maximum) {
    return value != null
        && value.isTextual()
        && !value.textValue().isEmpty()
        && value.textValue().length() <= maximum
        && value.textValue().chars().noneMatch(Character::isISOControl);
  }

  private static String text(JsonNode value, int maximum) {
    if (value == null || !value.isTextual() || value.textValue().length() > maximum) return null;
    if (value.textValue().chars().anyMatch(Character::isISOControl)) return null;
    return value.textValue();
  }

  private static int integer(JsonNode value, int minimum, int maximum) {
    if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) return -1;
    int number = value.intValue();
    return number >= minimum && number <= maximum ? number : -1;
  }
}
