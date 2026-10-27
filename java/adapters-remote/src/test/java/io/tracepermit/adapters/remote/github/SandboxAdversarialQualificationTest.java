package io.tracepermit.adapters.remote.github;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BusinessOperationScope;
import io.tracepermit.application.BusinessOperationStorageException;
import io.tracepermit.application.BusinessOperationTransitionPlanner;
import io.tracepermit.application.BusinessOperationWithdrawal;
import io.tracepermit.application.ReconciliationObservation;
import io.tracepermit.application.TrustedRequesterContext;
import io.tracepermit.contracts.BusinessOperationValidator;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.execution.ReconciliationCandidate;
import io.tracepermit.execution.TenantPartition;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Synthetic unit/mock probes of pure binding, supplied withdrawal contexts and the target adapter.
 * No JDBC transaction, actual row-lock wait, live identity or concrete credential exchange is used.
 */
final class SandboxAdversarialQualificationTest {
  private static final String REPO = "tracepermit/agentcore-pr-pilot";
  private static final String HEAD = "synthetic-change";
  private static final String BASE = "main";
  private static final String HASH_5 = "sha256:" + "5".repeat(64);
  private static final String HASH_6 = "sha256:" + "6".repeat(64);
  private static final String HASH_7 = "sha256:" + "7".repeat(64);
  private static final Instant NOW = Instant.parse("2026-10-24T12:00:00Z");
  private static final Instant DEADLINE = NOW.plusSeconds(3600);
  private static final String TOKEN = "synthetic-token-" + UUID.randomUUID();
  private static final String FP = "sha256:" + "a".repeat(64);

  private static final BusinessOperationScope SCOPE =
      new BusinessOperationScope(
          "synthetic-tenant", "synthetic-github-sandbox", "principal:synthetic-requester");

  private static final GitHubPilotTargetBinding TARGET =
      new GitHubPilotTargetBinding(
          71, 101, 201, REPO, HEAD, BASE, "a".repeat(40), "b".repeat(40));

  private final AtomicReference<Instant> now = new AtomicReference<>(NOW);
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneId getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now.get();
        }
      };

  private MockWebServer server;
  private URI origin;
  private InMemoryExecutionLedger ledger;
  private GitHubAppCredentialProvider provider;
  private GitHubPullRequestOutcomeResolver resolver;
  private ExecutionPermitAuthority permits;
  private GitHubDraftPullRequestAdapter adapter;
  private GitHubApiTransport transport;
  private volatile Function<RecordedRequest, MockResponse> override = request -> null;
  private final List<String> requests = new CopyOnWriteArrayList<>();

  @BeforeEach
  void start() throws Exception {
    server = new MockWebServer();
    server.setDispatcher(
        new okhttp3.mockwebserver.Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            requests.add(request.getMethod() + " " + request.getPath());
            MockResponse custom = override.apply(request);
            if (custom != null) return custom;
            String path = request.getRequestUrl().encodedPath();
            if (path.equals("/installation/repositories")) {
              return response(200, installation().toString());
            }
            if (path.equals("/repos/" + REPO)) {
              return response(200, repository().toString());
            }
            if (path.equals("/repos/" + REPO + "/git/ref/heads/" + HEAD)) {
              return response(200, ref(HEAD, TARGET.headSha()).toString());
            }
            if (path.equals("/repos/" + REPO + "/git/ref/heads/" + BASE)) {
              return response(200, ref(BASE, TARGET.baseSha()).toString());
            }
            if (path.equals("/repos/" + REPO + "/pulls") && request.getMethod().equals("POST")) {
              return response(201, confirmation().toString());
            }
            if (path.startsWith("/repos/" + REPO + "/pulls")
                && request.getMethod().equals("GET")) {
              var array = JsonSupport.MAPPER.createArrayNode().add(confirmation());
              return response(200, array.toString());
            }
            return response(404, "{\"message\":\"Not Found\"}");
          }
        });
    server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
    origin = URI.create("http://127.0.0.1:" + server.getPort());
    transport = GitHubApiTransport.synthetic(origin, Duration.ofSeconds(1), Duration.ofSeconds(2), 65_536);
    ledger = new InMemoryExecutionLedger(() -> "attempt-" + UUID.randomUUID());
    provider = repo -> new GitHubAppCredentials(TARGET.installationId(), FP, TOKEN, NOW.plusSeconds(3600));
    byte[] key = new byte[32];
    new java.security.SecureRandom().nextBytes(key);
    permits =
        new ExecutionPermitAuthority(
            ExecutionPermitAuthority.DEFAULT_ISSUER,
            "synthetic",
            key,
            60,
            clock,
            () -> "permit-" + UUID.randomUUID(),
            new InMemoryPermitConsumptionStore());
    resolver = GitHubPullRequestOutcomeResolver.synthetic(provider, TARGET, transport, clock);
    adapter =
        GitHubDraftPullRequestAdapter.synthetic(
            provider, ledger, resolver, permits, TARGET, transport, clock);
  }

  @AfterEach
  void stop() throws Exception {
    if (server != null) server.shutdown();
  }

  // --------------------------------------------------------------------------------------
  // 1. Scope and rehashed request substitution
  // --------------------------------------------------------------------------------------

  @Test
  void scopeAndRehashedActionsAreBoundWithoutGrantingExecution() {
    var expected = profile();
    ObjectNode source = operation(expected);

    // Positive control: exact matching scope and profile binds cleanly
    var bound = GitHubBusinessOperationBinding.bind(SCOPE, source, expected);
    assertThat(bound.operationId()).isEqualTo(source.path("operationId").textValue());
    assertThat(bound.canonicalActionHash()).isEqualTo(source.path("canonicalActionHash").textValue());
    assertThat(bound.profileFingerprint()).isEqualTo(expected.fingerprint());

    // Adversarial probe 1: Caller swaps trusted scope (cross-tenant substitution)
    for (var crossScope :
        List.of(
            new BusinessOperationScope("other-tenant", SCOPE.workspaceId(), SCOPE.requesterPrincipalId()),
            new BusinessOperationScope(SCOPE.tenantId(), "other-workspace", SCOPE.requesterPrincipalId()),
            new BusinessOperationScope(SCOPE.tenantId(), SCOPE.workspaceId(), "principal:attacker"))) {
      assertThatThrownBy(() -> GitHubBusinessOperationBinding.bind(crossScope, source, expected))
          .isInstanceOfSatisfying(
              GitHubBusinessOperationBinding.BindingException.class,
              failure -> assertThat(failure.code()).isEqualTo("GITHUB_OPERATION_BINDING_INVALID"));
    }

    // Adversarial probe 2: Attacker alters title and recomputes all hashes to match
    // Invariant (ADR 0075): Recomputed title produces distinct bound data with a distinct snapshot hash,
    // which cannot be executed with a permit issued for the original action.
    ObjectNode rehashedTitle = operation(expected);
    arguments(rehashedTitle).put("title", "Malicious unapproved PR");
    refreshHashes(rehashedTitle);
    var boundRehashed = GitHubBusinessOperationBinding.bind(SCOPE, rehashedTitle, expected);
    assertThat(boundRehashed.canonicalActionHash()).isNotEqualTo(bound.canonicalActionHash());
    assertThat(boundRehashed.bindingDigest()).isNotEqualTo(bound.bindingDigest());
    // Permit issued for original action is rejected when presented with altered rehashed action
    var originalAction = action();
    var permitForOriginal = permit(originalAction);
    var alteredAction = originalAction.deepCopy();
    args(alteredAction).put("title", "Malicious unapproved PR");
    assertThatThrownBy(() -> adapter.execute(alteredAction, permitForOriginal))
        .isInstanceOfSatisfying(
            io.tracepermit.security.ExecutionPermitException.class,
            failure -> assertThat(failure.code()).isEqualTo("PERMIT_ACTION_MISMATCH"));
    assertThat(requests).isEmpty();

    // Adversarial probe 3: Attacker alters target repository and recomputes hashes
    ObjectNode rehashedRepo = operation(expected);
    arguments(rehashedRepo).put("repository", "tracepermit/unauthorized-repo");
    target(rehashedRepo).put("accountId", "tracepermit/unauthorized-repo");
    refreshHashes(rehashedRepo);
    assertThatThrownBy(() -> GitHubBusinessOperationBinding.bind(SCOPE, rehashedRepo, expected))
        .isInstanceOf(GitHubBusinessOperationBinding.BindingException.class);

    // Adversarial probe 4: Attacker alters draft to false (requesting non-draft PR)
    ObjectNode nonDraft = operation(expected);
    arguments(nonDraft).put("draft", false);
    refreshHashes(nonDraft);
    assertThatThrownBy(() -> GitHubBusinessOperationBinding.bind(SCOPE, nonDraft, expected))
        .isInstanceOf(GitHubBusinessOperationBinding.BindingException.class);

    // Adversarial probe 5: Attacker injects extra unauthorized argument
    ObjectNode extraArg = operation(expected);
    arguments(extraArg).put("issue", 123);
    refreshHashes(extraArg);
    assertThatThrownBy(() -> GitHubBusinessOperationBinding.bind(SCOPE, extraArg, expected))
        .isInstanceOf(GitHubBusinessOperationBinding.BindingException.class);
  }

  // --------------------------------------------------------------------------------------
  // 2. Caller-authored approval/state labels
  // --------------------------------------------------------------------------------------

  @Test
  void callerAuthoredLabelsBindAsDataButDoNotSupplyATargetPermit() {
    var expected = profile();
    var planner = new BusinessOperationTransitionPlanner();
    ObjectNode initial = operation(expected);

    // Positive control: initial awaiting_approval state binds as data
    var boundInitial = GitHubBusinessOperationBinding.bind(SCOPE, initial, expected);
    assertThat(boundInitial.snapshot().path("authorization").path("status").asText()).isEqualTo("awaiting_approval");
    assertThat(boundInitial.toString()).contains("[redacted; no execution authority]");

    // Adversarial probe: Attacker authors "approved" authorization status
    ObjectNode approved = progress(planner, initial, BusinessOperationTransitionPlanner.Dimension.AUTHORIZATION, "approved");
    var boundApproved = GitHubBusinessOperationBinding.bind(SCOPE, approved, expected);
    assertThat(boundApproved.snapshot().path("authorization").path("status").asText()).isEqualTo("approved");
    // Possession of bound approved data does not bypass target execution requirements
    assertThat(boundApproved.toString()).contains("[redacted; no execution authority]");

    // Adversarial probe: Calling execute() directly without a permit fails closed
    var action = action();
    assertThatThrownBy(() -> adapter.execute(action, null))
        .isInstanceOfSatisfying(
            GitHubPullRequestException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo("MISSING_PERMIT"));

    assertThatThrownBy(() -> adapter.execute(action, "   "))
        .isInstanceOfSatisfying(
            GitHubPullRequestException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo("MISSING_PERMIT"));
  }

  // --------------------------------------------------------------------------------------
  // 3. Stale policy/recovery generation
  // --------------------------------------------------------------------------------------

  @Test
  void staleExpectedProfilesFailAtPureBindingBoundary() {
    var expected = profile();
    ObjectNode initial = operation(expected);

    // Positive control: current matching recovery generation and policy binds
    assertThatNoException().isThrownBy(() -> GitHubBusinessOperationBinding.bind(SCOPE, initial, expected));

    // Adversarial probe 1: Stale recovery generation in snapshot (G1 vs active G2)
    var bumpedGenerationProfile = profile("test", 2, policy(), artifacts(), TARGET);
    assertThatThrownBy(() -> GitHubBusinessOperationBinding.bind(SCOPE, initial, bumpedGenerationProfile))
        .isInstanceOfSatisfying(GitHubBusinessOperationBinding.BindingException.class,
            failure -> assertThat(failure.code()).isEqualTo("GITHUB_OPERATION_BINDING_INVALID"));

    // Adversarial probe 2: Stale policy version (version 1 vs active version 2)
    var bumpedPolicyProfile =
        profile(
            "test",
            1,
            new GitHubBusinessOperationBinding.PolicyRelease("synthetic-github-draft-pr", 2, HASH_5),
            artifacts(),
            TARGET);
    assertThatThrownBy(() -> GitHubBusinessOperationBinding.bind(SCOPE, initial, bumpedPolicyProfile))
        .isInstanceOfSatisfying(GitHubBusinessOperationBinding.BindingException.class,
            failure -> assertThat(failure.code()).isEqualTo("GITHUB_OPERATION_BINDING_INVALID"));

    // Adversarial probe 3: In-place modified policy content hash
    var alteredHashProfile =
        profile(
            "test",
            1,
            new GitHubBusinessOperationBinding.PolicyRelease("synthetic-github-draft-pr", 1, "sha256:" + "9".repeat(64)),
            artifacts(),
            TARGET);
    assertThatThrownBy(() -> GitHubBusinessOperationBinding.bind(SCOPE, initial, alteredHashProfile))
        .isInstanceOfSatisfying(GitHubBusinessOperationBinding.BindingException.class,
            failure -> assertThat(failure.code()).isEqualTo("GITHUB_OPERATION_BINDING_INVALID"));

    // Adversarial probe 4: Stale artifact digest
    var alteredArtifactProfile =
        profile(
            "test",
            1,
            policy(),
            Map.of("policy_bundle", "sha256:" + "e".repeat(64), "adapter_release", HASH_7),
            TARGET);
    assertThatThrownBy(() -> GitHubBusinessOperationBinding.bind(SCOPE, initial, alteredArtifactProfile))
        .isInstanceOfSatisfying(GitHubBusinessOperationBinding.BindingException.class,
            failure -> assertThat(failure.code()).isEqualTo("GITHUB_OPERATION_BINDING_INVALID"));
    assertThat(requests).isEmpty();
  }

  // --------------------------------------------------------------------------------------
  // 4. Supplied-time expiry boundary (actual JDBC lock waits are tested in persistence-operations)
  // --------------------------------------------------------------------------------------

  @Test
  void suppliedStoreTimeExpiryRejectsEqualityAndLaterInstants() {
    var token =
        new BusinessOperationTransitionPlanner.StateToken(
            "1e7d1ec3-af54-4c5b-8f73-f58e9c3c9863", 1, 3, 1, 1, 1, HASH_5);
    var context =
        new TrustedRequesterContext(
            new TenantPartition(SCOPE.tenantId()),
            SCOPE.requesterPrincipalId(),
            true,
            Set.of(BusinessOperationWithdrawal.ROLE),
            Set.of(BusinessOperationWithdrawal.SCOPE),
            "synthetic-test",
            FP,
            DEADLINE);
    var command = new BusinessOperationWithdrawal(SCOPE, token.operationId(), token, context);

    // Positive control: Authority verified before deadline succeeds
    assertThatNoException().isThrownBy(() -> command.requireAuthorizedAt(DEADLINE.minusMillis(1)));

    // Supplied equality is expired. No lock or database clock is involved in this unit probe.
    assertThatThrownBy(() -> command.requireAuthorizedAt(DEADLINE))
        .isInstanceOfSatisfying(
            BusinessOperationStorageException.class,
            failure -> assertThat(failure.code()).isEqualTo(BusinessOperationStorageException.Code.OPERATION_AUTHENTICATION_EXPIRED));

    // Adversarial probe 2: supplied store time is after deadline
    assertThatThrownBy(() -> command.requireAuthorizedAt(DEADLINE.plusMillis(1)))
        .isInstanceOfSatisfying(
            BusinessOperationStorageException.class,
            failure -> assertThat(failure.code()).isEqualTo(BusinessOperationStorageException.Code.OPERATION_AUTHENTICATION_EXPIRED));

    // Adversarial probe 3: Null store time fails closed
    assertThatThrownBy(() -> command.requireAuthorizedAt(null))
        .isInstanceOf(BusinessOperationStorageException.class);
  }

  // --------------------------------------------------------------------------------------
  // 5. Same-permit rejection and fresh-permit cached success in a process-local ledger
  // --------------------------------------------------------------------------------------

  @Test
  void samePermitReplayIsRejectedAndFreshPermitUsesCachedSuccess() {
    register();
    var action = action();
    var permit = permit(action);

    // Initial dispatch succeeds
    var first = adapter.execute(action, permit);
    assertThat(first.number()).isEqualTo(42);
    assertThat(first.htmlUrl()).isEqualTo("https://github.com/" + REPO + "/pull/42");
    assertThat(posts()).isEqualTo(1);

    // Reusing the exact consumed token is distinct from presenting a newly issued permit.
    int afterFirstDispatch = requests.size();
    assertThatThrownBy(() -> adapter.execute(action, permit))
        .isInstanceOfSatisfying(
            io.tracepermit.security.ExecutionPermitException.class,
            failure -> assertThat(failure.code()).isEqualTo("EXECUTION_PERMIT_REPLAYED"));
    assertThat(requests).hasSize(afterFirstDispatch);
    assertThat(posts()).isEqualTo(1);

    // Duplicate admission / replay attempt with same operation key
    // A fresh permit for the same action is presented; adapter returns cached success without second network call
    var second = adapter.execute(action, permit(action));
    assertThat(second.number()).isEqualTo(42);
    assertThat(second.htmlUrl()).isEqualTo(first.htmlUrl());
    // Invariant: zero additional POST requests sent to target
    assertThat(posts()).isEqualTo(1);
    assertThat(requests).hasSize(afterFirstDispatch);
  }

  // --------------------------------------------------------------------------------------
  // 6. Incomplete post-dispatch confirmation; no database transaction is composed here
  // --------------------------------------------------------------------------------------

  @Test
  void incompletePostDispatchConfirmationStaysUnknownAndCannotPostAgain() {
    register();
    var action = action();
    var permit = permit(action);

    // Valid JSON, but incomplete 201 confirmation after the target has received the POST.
    override =
        req -> {
          if (req.getMethod().equals("POST")) {
            return response(201, "{\"id\":42001,\"state\":\"open\"}"); // missing number, html_url, draft
          }
          return null;
        };

    assertThatThrownBy(() -> adapter.execute(action, permit))
        .isInstanceOfSatisfying(
            GitHubPullRequestException.class,
            failure ->
                assertThat(failure.errorCode())
                    .isEqualTo("TARGET_PREFLIGHT_OR_IDENTITY_REJECTED"));

    assertThat(status()).isEqualTo("unknown");
    assertThat(posts()).isEqualTo(1);
    int afterFirstDispatch = requests.size();

    override = req -> null; // Even a now-successful fake target cannot authorize another dispatch.
    assertThatThrownBy(() -> adapter.execute(action, permit(action)))
        .isInstanceOfSatisfying(
            GitHubPullRequestException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo("EXECUTION_OUTCOME_UNKNOWN"));
    assertThat(status()).isEqualTo("unknown");
    assertThat(posts()).isEqualTo(1);
    assertThat(requests).hasSize(afterFirstDispatch);
  }

  // --------------------------------------------------------------------------------------
  // 7. Changed supplied expected refs and resource bindings (pure binding, not a remote ref GET)
  // --------------------------------------------------------------------------------------

  @Test
  void changedExpectedRefOrCommitShaBreaksPureBinding() {
    var expected = profile();
    ObjectNode source = operation(expected);

    // Positive control: exact refs and SHAs match
    assertThatNoException().isThrownBy(() -> GitHubBusinessOperationBinding.bind(SCOPE, source, expected));

    // Adversarial probe 1: Head SHA changed on remote branch
    var changedHeadShaProfile =
        profile(
            "test",
            1,
            policy(),
            artifacts(),
            new GitHubPilotTargetBinding(71, 101, 201, REPO, HEAD, BASE, "c".repeat(40), "b".repeat(40)));
    assertThatThrownBy(() -> GitHubBusinessOperationBinding.bind(SCOPE, source, changedHeadShaProfile))
        .isInstanceOf(GitHubBusinessOperationBinding.BindingException.class);

    // Adversarial probe 2: Base SHA changed (main advanced)
    var changedBaseShaProfile =
        profile(
            "test",
            1,
            policy(),
            artifacts(),
            new GitHubPilotTargetBinding(71, 101, 201, REPO, HEAD, BASE, "a".repeat(40), "c".repeat(40)));
    assertThatThrownBy(() -> GitHubBusinessOperationBinding.bind(SCOPE, source, changedBaseShaProfile))
        .isInstanceOf(GitHubBusinessOperationBinding.BindingException.class);

    // Adversarial probe 3: Head branch ref name changed
    var changedHeadRefProfile =
        profile(
            "test",
            1,
            policy(),
            artifacts(),
            new GitHubPilotTargetBinding(71, 101, 201, REPO, "feature/other", BASE, "a".repeat(40), "b".repeat(40)));
    assertThatThrownBy(() -> GitHubBusinessOperationBinding.bind(SCOPE, source, changedHeadRefProfile))
        .isInstanceOf(GitHubBusinessOperationBinding.BindingException.class);
    assertThat(requests).isEmpty();
  }

  // --------------------------------------------------------------------------------------
  // 8. Uncertain target outcome becoming an illicit second POST
  // --------------------------------------------------------------------------------------

  @Test
  void uncertainTargetOutcomeBlocksSecondPostAndReconciliationNeverAssumesAbsence() {
    register();
    var action = action();
    var permit = permit(action);

    // Inject 500 Internal Server Error during POST
    override =
        req -> {
          if (req.getMethod().equals("POST")) {
            return response(500, "{\"message\":\"GitHub internal error\"}");
          }
          return null;
        };

    assertThatThrownBy(() -> adapter.execute(action, permit))
        .isInstanceOfSatisfying(
            GitHubPullRequestException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo("OUTCOME_UNCERTAIN"));

    assertThat(status()).isEqualTo("unknown");
    assertThat(posts()).isEqualTo(1);

    // Adversarial probe 1: Caller re-attempts execute() after uncertainty
    // Invariant: adapter refuses immediately with EXECUTION_OUTCOME_UNKNOWN without sending another POST
    override = req -> null; // restore normal responses
    assertThatThrownBy(() -> adapter.execute(action, permit(action)))
        .isInstanceOfSatisfying(
            GitHubPullRequestException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo("EXECUTION_OUTCOME_UNKNOWN"));

    assertThat(posts()).isEqualTo(1); // exactly 1 POST ever sent

    // Adversarial probe 2: Reconciler lookup returns empty array [] (eventual consistency delay)
    // Invariant: lookup must return unknown("LOOKUP_AMBIGUOUS_OR_ABSENT"), NEVER assume absence
    override =
        req -> {
          if (req.getMethod().equals("GET") && req.getPath().startsWith("/repos/" + REPO + "/pulls")) {
            return response(200, "[]");
          }
          return null;
        };

    ReconciliationObservation obsEmpty = resolver.lookup(candidate());
    assertThat(obsEmpty.confirmed()).isFalse();
    assertThat(obsEmpty.reasonCode()).isEqualTo("LOOKUP_AMBIGUOUS_OR_ABSENT");

    // Adversarial probe 3: Reconciler lookup returns multiple matching PRs
    override =
        req -> {
          if (req.getMethod().equals("GET") && req.getPath().startsWith("/repos/" + REPO + "/pulls")) {
            var arr = JsonSupport.MAPPER.createArrayNode().add(confirmation()).add(confirmation());
            return response(200, arr.toString());
          }
          return null;
        };

    ReconciliationObservation obsMulti = resolver.lookup(candidate());
    assertThat(obsMulti.confirmed()).isFalse();
    assertThat(obsMulti.reasonCode()).isEqualTo("LOOKUP_AMBIGUOUS_OR_ABSENT");

    // Positive control: Reconciler lookup returns single exact matching PR
    override = req -> null;
    ReconciliationObservation obsSuccess = resolver.lookup(candidate());
    assertThat(obsSuccess.confirmed()).isTrue();
    assertThat(status()).isEqualTo("unknown"); // Lookup observes; it does not commit a ledger outcome.
    assertThat(posts()).isEqualTo(1);
  }

  // --------------------------------------------------------------------------------------
  // 9. Supplied withdrawal-context checks and separate permit audience; no delegation proof
  // --------------------------------------------------------------------------------------

  @Test
  void withdrawalContextAuthorizationAndPermitAudienceAreSeparateBoundaries() {
    var token =
        new BusinessOperationTransitionPlanner.StateToken(
            "1e7d1ec3-af54-4c5b-8f73-f58e9c3c9863", 1, 3, 1, 1, 1, HASH_5);

    // Positive control: matching owner, tenant, authenticated, correct role & scope
    var validContext =
        new TrustedRequesterContext(
            new TenantPartition(SCOPE.tenantId()),
            SCOPE.requesterPrincipalId(),
            true,
            Set.of(BusinessOperationWithdrawal.ROLE),
            Set.of(BusinessOperationWithdrawal.SCOPE),
            "synthetic-test",
            FP,
            DEADLINE);
    assertThatNoException().isThrownBy(() -> new BusinessOperationWithdrawal(SCOPE, token.operationId(), token, validContext));

    // Adversarial probe 1: Unrelated tenant partition (cross-tenant delegation attempt)
    var crossTenantContext =
        new TrustedRequesterContext(
            new TenantPartition("unrelated-tenant"),
            SCOPE.requesterPrincipalId(),
            true,
            Set.of(BusinessOperationWithdrawal.ROLE),
            Set.of(BusinessOperationWithdrawal.SCOPE),
            "synthetic-test",
            FP,
            DEADLINE);
    assertThatThrownBy(() -> new BusinessOperationWithdrawal(SCOPE, token.operationId(), token, crossTenantContext))
        .isInstanceOf(BusinessOperationStorageException.class);

    // Adversarial probe 2: Unrelated subject ID (impersonation / delegation attempt)
    var crossUserContext =
        new TrustedRequesterContext(
            new TenantPartition(SCOPE.tenantId()),
            "principal:unrelated-workload",
            true,
            Set.of(BusinessOperationWithdrawal.ROLE),
            Set.of(BusinessOperationWithdrawal.SCOPE),
            "synthetic-test",
            FP,
            DEADLINE);
    assertThatThrownBy(() -> new BusinessOperationWithdrawal(SCOPE, token.operationId(), token, crossUserContext))
        .isInstanceOf(BusinessOperationStorageException.class);

    // Adversarial probe 3: Unauthenticated context
    var unauthContext =
        new TrustedRequesterContext(
            new TenantPartition(SCOPE.tenantId()),
            SCOPE.requesterPrincipalId(),
            false,
            Set.of(BusinessOperationWithdrawal.ROLE),
            Set.of(BusinessOperationWithdrawal.SCOPE),
            "synthetic-test",
            FP,
            DEADLINE);
    assertThatThrownBy(() -> new BusinessOperationWithdrawal(SCOPE, token.operationId(), token, unauthContext))
        .isInstanceOf(BusinessOperationStorageException.class);

    // Adversarial probe 4: Missing operation_requester role
    var wrongRoleContext =
        new TrustedRequesterContext(
            new TenantPartition(SCOPE.tenantId()),
            SCOPE.requesterPrincipalId(),
            true,
            Set.of("operation_admin"),
            Set.of(BusinessOperationWithdrawal.SCOPE),
            "synthetic-test",
            FP,
            DEADLINE);
    assertThatThrownBy(() -> new BusinessOperationWithdrawal(SCOPE, token.operationId(), token, wrongRoleContext))
        .isInstanceOf(BusinessOperationStorageException.class);

    // Adversarial probe 5: Missing operations.cancel scope
    var wrongScopeContext =
        new TrustedRequesterContext(
            new TenantPartition(SCOPE.tenantId()),
            SCOPE.requesterPrincipalId(),
            true,
            Set.of(BusinessOperationWithdrawal.ROLE),
            Set.of("operations.read"),
            "synthetic-test",
            FP,
            DEADLINE);
    assertThatThrownBy(() -> new BusinessOperationWithdrawal(SCOPE, token.operationId(), token, wrongScopeContext))
        .isInstanceOf(BusinessOperationStorageException.class);

    // Adversarial probe 6: Target adapter permit with audience mismatch
    register();
    var action = action();
    var evaluation = evaluation(action);
    var wrongAudiencePermit = permits.issue(action, evaluation, "github.pull_request.merge");
    assertThatThrownBy(() -> adapter.execute(action, wrongAudiencePermit))
        .isInstanceOfSatisfying(
            io.tracepermit.security.ExecutionPermitException.class,
            failure -> assertThat(failure.code()).isEqualTo("WRONG_PERMIT_AUDIENCE"));

    // Adversarial probe 7: Missing or blank permit token
    assertThatThrownBy(() -> adapter.execute(action, ""))
        .isInstanceOfSatisfying(
            GitHubPullRequestException.class,
            failure -> assertThat(failure.errorCode()).isEqualTo("MISSING_PERMIT"));
    assertThat(requests).isEmpty();
  }

  // --------------------------------------------------------------------------------------
  // 10. Selected diagnostic strings omit a canary; the full snapshot remains sensitive data
  // --------------------------------------------------------------------------------------

  @Test
  void selectedErrorsAndDiagnosticStringsOmitCanaryButSnapshotIsSensitive() {
    var expected = profile();
    ObjectNode source = operation(expected);

    // Injected sensitive canary value
    String sensitiveCanary = "ghs_SECRET_INSTALLATION_BEARER_TOKEN_CANARY_987654";
    arguments(source).put("body", sensitiveCanary);
    refreshHashes(source);

    // Probe 1: BindingException has fixed code and no sensitive details
    try {
      GitHubBusinessOperationBinding.bind(
          new BusinessOperationScope("other", SCOPE.workspaceId(), SCOPE.requesterPrincipalId()),
          source,
          expected);
      fail("expected BindingException");
    } catch (GitHubBusinessOperationBinding.BindingException failure) {
      assertThat(failure.code()).isEqualTo("GITHUB_OPERATION_BINDING_INVALID");
      assertThat(failure.getMessage()).isEqualTo("GITHUB_OPERATION_BINDING_INVALID");
      assertThat(failure.getCause()).isNull();
      assertThat(failure.getSuppressed()).isEmpty();
      assertThat(failure.toString()).doesNotContain(sensitiveCanary, REPO, SCOPE.tenantId());
      assertThatThrownBy(() -> failure.initCause(new RuntimeException(sensitiveCanary)))
          .isInstanceOf(IllegalStateException.class);
    }

    // Probe 2: BusinessOperationStorageException has fixed code and no sensitive details
    var storageEx =
        new BusinessOperationStorageException(
            BusinessOperationStorageException.Code.OPERATION_REQUEST_KEY_CONFLICT);
    storageEx.addSuppressed(new RuntimeException(sensitiveCanary));
    assertThat(storageEx.getSuppressed()).isEmpty();
    assertThat(storageEx.getCause()).isNull();
    assertThat(storageEx.toString()).doesNotContain(sensitiveCanary);

    // Probe 3: toString() representations of internal components are all redacted
    var bound = GitHubBusinessOperationBinding.bind(SCOPE, source, expected);
    assertThat(bound.toString()).isEqualTo("GitHubBoundOperationData[redacted; no execution authority]");
    assertThat(bound.snapshot().path("canonicalAction").path("action").path("arguments")
        .path("body").textValue().equals(sensitiveCanary)).isTrue();
    assertThat(expected.toString()).isEqualTo("GitHubExpectedOperationProfile[redacted]");
    assertThat(expected.policyRelease().toString()).isEqualTo("GitHubExpectedPolicyRelease[redacted]");

    var withdrawal =
        new BusinessOperationWithdrawal(
            SCOPE,
            "1e7d1ec3-af54-4c5b-8f73-f58e9c3c9863",
            new BusinessOperationTransitionPlanner.StateToken(
                "1e7d1ec3-af54-4c5b-8f73-f58e9c3c9863", 1, 3, 1, 1, 1, HASH_5),
            new TrustedRequesterContext(
                new TenantPartition(SCOPE.tenantId()),
                SCOPE.requesterPrincipalId(),
                true,
                Set.of(BusinessOperationWithdrawal.ROLE),
                Set.of(BusinessOperationWithdrawal.SCOPE),
                "synthetic-test",
                FP,
                DEADLINE));
    assertThat(withdrawal.toString())
        .isEqualTo("BusinessOperationWithdrawal[redacted; pending only; not target authority]");
  }

  // --------------------------------------------------------------------------------------
  // Helper methods
  // --------------------------------------------------------------------------------------

  private static ObjectNode progress(
      BusinessOperationTransitionPlanner planner,
      ObjectNode current,
      BusinessOperationTransitionPlanner.Dimension dimension,
      String status) {
    Instant next = Instant.parse(current.path("updatedAt").textValue()).plusMillis(1);
    return (ObjectNode)
        planner
            .propose(
                SCOPE,
                current,
                planner.observe(SCOPE, current),
                dimension,
                status,
                "SYNTHETIC_DATA_ONLY",
                next)
            .snapshot();
  }

  private static GitHubBusinessOperationBinding.ExpectedProfile profile() {
    return profile("test", 1, policy(), artifacts(), TARGET);
  }

  private static GitHubBusinessOperationBinding.ExpectedProfile profile(
      String environment,
      long generation,
      GitHubBusinessOperationBinding.PolicyRelease policy,
      Map<String, String> artifacts,
      GitHubPilotTargetBinding target) {
    return new GitHubBusinessOperationBinding.ExpectedProfile(
        GitHubBusinessOperationBinding.ADAPTER_ID, environment, generation, policy, artifacts, target);
  }

  private static GitHubBusinessOperationBinding.PolicyRelease policy() {
    return new GitHubBusinessOperationBinding.PolicyRelease("synthetic-github-draft-pr", 1, HASH_5);
  }

  private static Map<String, String> artifacts() {
    return Map.of("policy_bundle", HASH_6, "adapter_release", HASH_7);
  }

  private static ObjectNode genericOperation() {
    Path root = Path.of(System.getProperty("tracepermit.repositoryRoot"));
    return (ObjectNode)
        JsonSupport.read(root.resolve("contracts/vectors/business-operation-v1.json"))
            .path("operation")
            .deepCopy();
  }

  private static ObjectNode operation(GitHubBusinessOperationBinding.ExpectedProfile expected) {
    ObjectNode operation = genericOperation();
    arguments(operation).remove("owner");
    arguments(operation).put("repository", expected.targetBinding().repository());
    arguments(operation).put("head", expected.targetBinding().head());
    arguments(operation).put("base", expected.targetBinding().base());
    target(operation).put("accountId", expected.targetBinding().repository());
    target(operation).put("resourceType", "pull_request");
    target(operation).put("environment", expected.environment());
    operation.put("adapterId", expected.adapterId());
    operation.put("recoveryGeneration", expected.recoveryGeneration());
    operation.set("resourceBinding", expected.resourceBinding());
    operation
        .putObject("policyRelease")
        .put("policyId", expected.policyRelease().policyId())
        .put("version", expected.policyRelease().version())
        .put("contentHash", expected.policyRelease().contentHash());
    ArrayNode artifacts = operation.putArray("artifactDigests");
    expected.artifactDigests().forEach((k, v) -> artifacts.addObject().put("kind", k).put("digest", v));
    refreshHashes(operation);
    return operation;
  }

  private static void refreshHashes(ObjectNode operation) {
    operation.put("canonicalActionHash", CanonicalJson.sha256(operation.path("canonicalAction")));
    operation.put("semanticPayloadDigest", CanonicalJson.sha256(operation.path("canonicalAction").path("action")));
    operation.put("resourceBindingHash", CanonicalJson.sha256(operation.path("resourceBinding")));
    operation.put(
        "targetIdempotencyKeyHash",
        BusinessOperationValidator.targetIdempotencyKeyHash(
            operation.path("operationId").textValue(),
            operation.path("adapterId").textValue(),
            operation.path("recoveryGeneration").longValue()));
  }

  private static ObjectNode action(ObjectNode operation) {
    return (ObjectNode) operation.path("canonicalAction").path("action");
  }

  private static ObjectNode arguments(ObjectNode operation) {
    return (ObjectNode) action(operation).path("arguments");
  }

  private static ObjectNode target(ObjectNode operation) {
    return (ObjectNode) action(operation).path("target");
  }

  private static ObjectNode repository() {
    var repo =
        JsonSupport.MAPPER
            .createObjectNode()
            .put("id", 101)
            .put("name", "agentcore-pr-pilot")
            .put("full_name", REPO)
            .put("private", true)
            .put("archived", false)
            .put("disabled", false)
            .put("fork", false);
    repo.putObject("owner").put("id", 201).put("login", "tracepermit");
    return repo;
  }

  private static ObjectNode installation() {
    var node = JsonSupport.MAPPER.createObjectNode().put("total_count", 1);
    node.putArray("repositories").add(repository());
    return node;
  }

  private static ObjectNode ref(String ref, String sha) {
    var node = JsonSupport.MAPPER.createObjectNode().put("ref", "refs/heads/" + ref);
    node.putObject("object").put("type", "commit").put("sha", sha);
    return node;
  }

  private static ObjectNode confirmation() {
    var pr =
        JsonSupport.MAPPER
            .createObjectNode()
            .put("id", 42001)
            .put("number", 42)
            .put("draft", true)
            .put("state", "open")
            .put("html_url", "https://github.com/" + REPO + "/pull/42")
            .put("title", "Synthetic draft")
            .put("body", "Synthetic body");
    pr.putObject("head").put("ref", HEAD).put("sha", TARGET.headSha()).set("repo", repository());
    pr.putObject("base").put("ref", BASE).put("sha", TARGET.baseSha()).set("repo", repository());
    return pr;
  }

  private static MockResponse response(int status, String body) {
    return new MockResponse()
        .setResponseCode(status)
        .setHeader("Content-Type", "application/json")
        .setBody(body);
  }

  private static String key() {
    return GitHubDraftPullRequestAdapter.computeOperationKey(71, REPO, HEAD, BASE);
  }

  private void register() {
    resolver.registerOperation(key(), TARGET, "Synthetic draft", "Synthetic body");
  }

  private static ReconciliationCandidate candidate() {
    return new ReconciliationCandidate(
        new TenantPartition(SCOPE.tenantId()),
        "attempt",
        GitHubDraftPullRequestAction.OPERATION,
        key(),
        NOW,
        1,
        "worker",
        NOW.plusSeconds(30));
  }

  private long posts() {
    return requests.stream().filter(s -> s.startsWith("POST ")).count();
  }

  private String status() {
    return ledger.findByIdempotencyKey(key()).status();
  }

  private static ObjectNode args(ObjectNode action) {
    return (ObjectNode) action.path("action").path("arguments");
  }

  private static ObjectNode action() {
    try {
      var action =
          (ObjectNode)
              JsonSupport.MAPPER
                  .readTree(
                      Files.readString(
                          Path.of(
                              System.getProperty("tracepermit.repositoryRoot"),
                              "contracts/vectors/business-operation-v1.json")))
                  .path("operation")
                  .path("canonicalAction")
                  .deepCopy();
      action.put("eventId", UUID.randomUUID().toString());
      var args = args(action);
      args.removeAll();
      args.put("repository", REPO)
          .put("head", HEAD)
          .put("base", BASE)
          .put("draft", true)
          .put("title", "Synthetic draft")
          .put("body", "Synthetic body");
      ((ObjectNode) action.path("action").path("target"))
          .put("accountId", REPO)
          .put("resourceType", "pull_request");
      return action;
    } catch (IOException failure) {
      throw new IllegalStateException("TEST_FIXTURE_UNAVAILABLE");
    }
  }

  private static ObjectNode evaluation(JsonNode action) {
    var evaluation =
        JsonSupport.MAPPER
            .createObjectNode()
            .put("schemaVersion", "1.0")
            .put("evaluationId", "synthetic")
            .put("eventId", action.path("eventId").asText())
            .put("evaluatedAt", NOW.toString());
    evaluation
        .putObject("decision")
        .put("outcome", "allow")
        .put("reasonCode", "SYNTHETIC_ALLOWED")
        .put("policyId", "synthetic")
        .put("policyVersion", 1);
    return evaluation;
  }

  private String permit(JsonNode action) {
    return permits.issue(action, evaluation(action), GitHubDraftPullRequestAction.OPERATION);
  }
}
