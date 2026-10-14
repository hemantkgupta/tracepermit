package io.tracepermit.adapters.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tracepermit.application.BoundedLogReadSanitizer;
import io.tracepermit.application.ExecutionResult;
import io.tracepermit.application.InMemoryActionSequenceStore;
import io.tracepermit.application.SecurityReadiness;
import io.tracepermit.application.SecureExecutionService;
import io.tracepermit.application.SimulatedTarget;
import io.tracepermit.contracts.CanonicalJson;
import io.tracepermit.contracts.JsonSupport;
import io.tracepermit.execution.InMemoryEvidenceLedger;
import io.tracepermit.execution.InMemoryExecutionLedger;
import io.tracepermit.identity.InMemoryCredentialRevocationChecker;
import io.tracepermit.identity.InMemoryWorkloadIdentityReplayStore;
import io.tracepermit.identity.ScheduledActionAdapter;
import io.tracepermit.identity.ScheduledWorkloadVerifier;
import io.tracepermit.identity.StaticWorkloadTrustRegistry;
import io.tracepermit.identity.TrustedScheduledActionContext;
import io.tracepermit.identity.WorkloadCredentialException;
import io.tracepermit.identity.WorkloadTrustRegistry;
import io.tracepermit.policy.PolicyEvaluator;
import io.tracepermit.security.ExecutionPermitAuthority;
import io.tracepermit.security.InMemoryPermitConsumptionStore;
import io.tracepermit.simulator.StatefulTargetSimulator;
import io.tracepermit.simulator.SyntheticExfilTargetSimulator;
import io.tracepermit.simulator.SyntheticPromptInjectionSimulator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Presenter-facing, simulator-only proof of signed workload and sequence enforcement. */
public final class SignedScheduledSemanticDemoMain {
  private static final String TENANT = "tenant:demo";
  private static final String SCHEDULER = "scheduler:local-demo";
  private static final String WORKLOAD = "workload:scheduled-log-review";
  private static final String TOOL = "tool:aws-logs-simulator";
  private static final String AUDIENCE = "tracepermit-gateway";
  private static final String SCOPE = "aws.logs.read";
  private static final String RESOURCE = "aws:simulated:log-group/app/prod";
  private static final String SCHEDULE = "schedule:log-review";
  private static final String SCHEDULER_ISSUER = "tracepermit-scheduler:local-demo";
  private static final String WORKLOAD_ISSUER = "tracepermit-workload:scheduled-log-review";
  private static final String SCHEDULER_KEY_ID = "scheduler-runtime-key";
  private static final String WORKLOAD_KEY_ID = "workload-runtime-key";
  private static final String SUB_AGENT = "agent:log-review-child";
  private static final String SUB_AGENT_ISSUER = "tracepermit-agent:log-review-child";
  private static final String SUB_AGENT_KEY_ID = "sub-agent-runtime-key";

  private SignedScheduledSemanticDemoMain() {}

  public static void main(String[] args) {
    boolean stopMode = args.length == 1 && ("--stop".equals(args[0]) || "--stop-auto".equals(args[0]));
    boolean exfilMode = args.length == 1 && ("--exfil".equals(args[0]) || "--exfil-auto".equals(args[0]));
    boolean injectionMode = args.length == 1 && ("--prompt-injection".equals(args[0]) || "--prompt-injection-auto".equals(args[0]));
    boolean delegationMode = args.length == 1 && ("--delegation".equals(args[0]) || "--delegation-auto".equals(args[0]));
    boolean automatic = args.length == 1 && ("--auto".equals(args[0]) || "--stop-auto".equals(args[0]) || "--exfil-auto".equals(args[0]) || "--prompt-injection-auto".equals(args[0]) || "--delegation-auto".equals(args[0]));
    if (args.length > 1 || (args.length == 1 && !automatic && !stopMode && !exfilMode && !injectionMode && !delegationMode)) {
      throw new IllegalArgumentException("Expected no arguments, --auto, --stop, --stop-auto, --exfil, --exfil-auto, --prompt-injection, --prompt-injection-auto, --delegation, or --delegation-auto.");
    }

    Clock clock = Clock.systemUTC();
    Signer schedulerSigner = signer(SCHEDULER_ISSUER, SCHEDULER_KEY_ID, SCHEDULER);
    Signer workloadSigner = signer(WORKLOAD_ISSUER, WORKLOAD_KEY_ID, WORKLOAD);
    Signer subAgentSigner = signer(SUB_AGENT_ISSUER, SUB_AGENT_KEY_ID, SUB_AGENT);
    StaticWorkloadTrustRegistry registry =
        new StaticWorkloadTrustRegistry(
            List.of(
                trusted(
                    schedulerSigner,
                    Set.of(WorkloadTrustRegistry.KeyUsage.WORKLOAD_IDENTITY)),
                trusted(
                    workloadSigner,
                    Set.of(WorkloadTrustRegistry.KeyUsage.DELEGATION)),
                trusted(
                    subAgentSigner,
                    Set.of(WorkloadTrustRegistry.KeyUsage.DELEGATION))));
    ScheduledActionAdapter adapter =
        new ScheduledActionAdapter(
            new ScheduledWorkloadVerifier(
                registry,
                new InMemoryCredentialRevocationChecker(),
                new InMemoryWorkloadIdentityReplayStore(),
                clock));

    AtomicInteger dispatches = new AtomicInteger();
    AtomicInteger attemptsCreated = new AtomicInteger();
    AtomicInteger ids = new AtomicInteger();
    SyntheticExfilTargetSimulator exfilSimulator = exfilMode ? new SyntheticExfilTargetSimulator() : null;
    SyntheticPromptInjectionSimulator injectionSimulator = injectionMode ? new SyntheticPromptInjectionSimulator() : null;
    SimulatedTarget simulator = injectionMode ? injectionSimulator : exfilMode ? exfilSimulator : new StatefulTargetSimulator();
    byte[] permitKey = new byte[32];
    new SecureRandom().nextBytes(permitKey);
    InMemoryEvidenceLedger evidence =
        new InMemoryEvidenceLedger(
            "urn:tracepermit:scheduled-semantic-demo",
            clock,
            () -> "evidence-" + ids.incrementAndGet());
    SecureExecutionService enforcement =
        new SecureExecutionService(
            new PolicyEvaluator(),
            JsonSupport.read(
                Path.of(System.getProperty("tracepermit.repositoryRoot"))
                    .resolve(injectionMode ? "policies/tool-result-iam-demo.json" :
                        exfilMode ? "policies/synthetic-pii-exfil-demo.json" :
                        stopMode ? "policies/aws-scheduled-stop-demo.json" : "policies/aws-agentic-demo.json")),
            new ExecutionPermitAuthority(
                ExecutionPermitAuthority.DEFAULT_ISSUER,
                "scheduled-demo-permit-key",
                permitKey,
                30,
                clock,
                () -> "permit-" + UUID.randomUUID(),
                new InMemoryPermitConsumptionStore()),
            new InMemoryExecutionLedger(() -> {
              attemptsCreated.incrementAndGet();
              return "attempt-" + UUID.randomUUID();
            }),
            evidence,
            action -> {
              dispatches.incrementAndGet();
              return simulator.invoke(action);
            },
            () -> SecurityReadiness.allReady(clock.instant()),
            clock,
            "urn:tracepermit:scheduled-demo-simulator",
            new BoundedLogReadSanitizer(),
            InMemoryActionSequenceStore.oneMinute());

    if (stopMode) {
      runScheduledStop(adapter, schedulerSigner, workloadSigner, clock, enforcement, dispatches, evidence, automatic);
      return;
    }
    if (exfilMode) {
      runSyntheticExfil(adapter, schedulerSigner, workloadSigner, clock, enforcement,
          exfilSimulator, dispatches, evidence, automatic);
      return;
    }
    if (injectionMode) {
      runPromptInjection(adapter, schedulerSigner, workloadSigner, clock, enforcement,
          injectionSimulator, dispatches, evidence, automatic);
      return;
    }
    if (delegationMode) {
      runDelegationAmplification(adapter, schedulerSigner, workloadSigner, subAgentSigner,
          clock, enforcement, dispatches, attemptsCreated, evidence, automatic);
      return;
    }

    heading();
    pause(automatic, "Press Enter to create and verify the first signed workload action...");

    PreparedAction oversized =
        prepare(adapter, schedulerSigner, workloadSigner, clock, "run:oversized", 500);
    ExecutionResult first = enforcement.execute(oversized.canonicalEvent());
    require("succeeded".equals(first.outcome()), "The sanitized scheduled action did not succeed.");
    require(
        "sanitize".equals(first.evaluation().decision().outcome()),
        "The oversized request was not classified for sanitization.");
    require(
        first.effectiveEvent().path("action").path("arguments").path("limit").asInt() == 100,
        "The effective request was not clamped to 100.");
    System.out.println("\n1. Signed scheduled workload -> verified attribution -> resolved sanitization");
    System.out.println("   Subject: workload:scheduled-log-review (derived from verified credential)");
    System.out.println("   Delegation: workload -> tool:aws-logs-simulator (signature verified)");
    System.out.println("   Requested result window: 500");
    System.out.println("   Initial decision: sanitize / LOG_RESULT_WINDOW_REQUIRES_SANITIZATION");
    System.out.println("   Effective result window: 100");
    System.out.println("   Re-evaluated decision: allow; permit bound to the derived action");
    System.out.println("   Target: deterministic local AWS simulator; dispatch count = 1");

    try {
      adapter.verifyAndNormalize(
          oversized.input(),
          oversized.workloadCredential(),
          List.of(oversized.delegationCredential()),
          oversized.context());
      throw new IllegalStateException("The workload credential replay was accepted.");
    } catch (WorkloadCredentialException failure) {
      require(
          "WORKLOAD_CREDENTIAL_REPLAYED".equals(failure.code()),
          "The replay failed for an unexpected reason.");
      System.out.println("   Replay proof: WORKLOAD_CREDENTIAL_REPLAYED; no extra dispatch");
    }

    pause(automatic, "Press Enter to submit two more independently signed reads...");
    ExecutionResult second =
        enforcement.execute(
            prepare(adapter, schedulerSigner, workloadSigner, clock, "run:bounded-2", 20)
                .canonicalEvent());
    ExecutionResult third =
        enforcement.execute(
            prepare(adapter, schedulerSigner, workloadSigner, clock, "run:bounded-3", 20)
                .canonicalEvent());
    require("succeeded".equals(second.outcome()), "The second bounded read did not succeed.");
    require("block".equals(third.outcome()), "The third rapid read was not blocked.");
    require(
        third.evaluation().classification().signals().contains(
            InMemoryActionSequenceStore.RAPID_READ),
        "The server-owned sequence signal is absent.");
    require(dispatches.get() == 2, "A blocked or replayed action reached the target.");

    System.out.println("\n2. Gateway-owned sequence context changes the decision");
    System.out.println("   Second independent signed read: allowed and simulated");
    System.out.println("   Third independent signed read: blocked");
    System.out.println("   Signal: RAPID_REPEATED_READ_SEQUENCE");
    System.out.println("   Reason: RAPID_LOG_EXTRACTION_SEQUENCE_BLOCKED");
    System.out.println("   Final target dispatch count: 2 (never 3 or 4)");
    System.out.println("   Evidence records: " + evidence.readAll().size() + " append-only events");
    System.out.println("\n[PASS] Signed identity, delegation, sanitization, replay, and sequence controls.");
    System.out.println("No compact credential, private key, bearer token, or real target was printed or used.");
  }

  private static void runScheduledStop(
      ScheduledActionAdapter adapter,
      Signer schedulerSigner,
      Signer workloadSigner,
      Clock clock,
      SecureExecutionService enforcement,
      AtomicInteger dispatches,
      InMemoryEvidenceLedger evidence,
      boolean automatic) {
    System.out.println("\nTracePermit Live Scenario 7 — Signed scheduled EC2 stop is blocked");
    System.out.println("================================================================");
    System.out.println("The production label is simulated; no AWS endpoint or credential is configured.");
    pause(automatic, "Press Enter to verify the signed scheduled stop request...");

    PreparedAction request =
        prepare(
            adapter,
            schedulerSigner,
            workloadSigner,
            clock,
            "run:simulated-ec2-stop",
            stopAction(),
            "schedule:ec2-maintenance",
            "aws.ec2.stop",
            "aws:simulated:ec2-instance/i-simulated-scheduled-demo",
            "tool:aws-ec2-simulator",
            "Stop a simulated production-labelled instance for scheduled maintenance");
    JsonNode canonical = request.canonicalEvent();
    require("scheduled".equals(canonical.path("trigger").path("kind").asText()), "The trigger was not scheduled.");
    require("authenticated".equals(canonical.path("identity").path("subject").path("assurance").asText()), "The workload was not authenticated.");
    require(canonical.path("lineage").path("delegations").size() == 1, "The verified delegation is missing.");

    ExecutionResult result = enforcement.execute(canonical);
    require("block".equals(result.outcome()), "The scheduled stop was not blocked.");
    require("modify".equals(result.evaluation().classification().actionClass()), "Stop must classify as modification.");
    require("medium".equals(result.evaluation().classification().severity()), "The stop severity was unexpected.");
    require(result.evaluation().classification().signals().contains("PRODUCTION_TARGET"), "The production-target signal is missing.");
    require("block-scheduled-ec2-stop".equals(result.evaluation().decision().matchedRuleId()), "The scheduled-stop rule did not match.");
    require("SCHEDULED_EC2_STOP_BLOCKED".equals(result.evaluation().decision().reasonCode()), "The denial reason was unexpected.");
    require(result.attempt() == null && result.result() == null, "A blocked request created an execution attempt.");
    require(dispatches.get() == 0, "A blocked request reached the simulator.");
    require(
        evidence.readAll().stream()
            .map(item -> item.path("payload").path("eventType").asText())
            .toList()
            .equals(List.of("action.observed", "policy.evaluated", "authorization.denied")),
        "The denial evidence lifecycle was unexpected.");

    System.out.println("\n1. Trusted adapter verified the scheduled origin");
    System.out.println("   Subject: authenticated workload:scheduled-log-review");
    System.out.println("   Signed delegation: aws.ec2.stop on one simulated instance");
    System.out.println("   Trigger: scheduled; target environment label: prod");
    System.out.println("\n2. Java classified and enforced the exact action");
    System.out.println("   Action class: modify; severity: medium; signal: PRODUCTION_TARGET");
    System.out.println("   Policy: block-scheduled-ec2-stop / SCHEDULED_EC2_STOP_BLOCKED");
    System.out.println("   Outcome: block; permit: none; execution: not_executed");
    System.out.println("   Simulator dispatch count: 0");
    System.out.println("   Evidence: action.observed -> policy.evaluated -> authorization.denied");
    System.out.println("\n[PASS] Verified automation was denied before any simulated side effect.");
  }

  private static void runSyntheticExfil(
      ScheduledActionAdapter adapter,
      Signer schedulerSigner,
      Signer workloadSigner,
      Clock clock,
      SecureExecutionService enforcement,
      SyntheticExfilTargetSimulator simulator,
      AtomicInteger dispatches,
      InMemoryEvidenceLedger evidence,
      boolean automatic) {
    System.out.println("\nTracePermit Live Scenario 9 — Synthetic PII read then external send blocked");
    System.out.println("=======================================================================");
    System.out.println("All customer names and addresses are invented; the outbox has no network transport.");
    pause(automatic, "Press Enter to test an independently signed send with no read history...");

    ExecutionResult baselineSend = enforcement.execute(
        prepare(adapter, schedulerSigner, workloadSigner, clock, "run:baseline-send",
            syntheticSendAction(), "schedule:customer-audit", "demo.egress.send",
            "synthetic:outbox/audit", "tool:synthetic-egress",
            "Send synthetic dataset to a fixed non-routable destination").canonicalEvent());
    require("block".equals(baselineSend.outcome()), "The baseline external send was not blocked.");
    require("EXTERNAL_SEND_NOT_AUTHORIZED".equals(baselineSend.evaluation().decision().reasonCode()),
        "The baseline send matched an unexpected rule.");
    require(baselineSend.attempt() == null && baselineSend.result() == null,
        "The baseline send created an execution attempt.");
    require(!baselineSend.evaluation().classification().signals().contains(
        InMemoryActionSequenceStore.PII_READ_THEN_EXTERNAL_SEND),
        "The caller manufactured server-owned read history.");
    require(dispatches.get() == 0 && simulator.sendCount() == 0,
        "The baseline external send reached the simulator.");

    pause(automatic, "Press Enter to verify and execute the synthetic customer read...");
    PreparedAction read = prepare(adapter, schedulerSigner, workloadSigner, clock,
        "run:synthetic-read", syntheticReadAction(), "schedule:customer-audit",
        "demo.customer.read", "synthetic:customers/demo", "tool:synthetic-crm",
        "Review twenty invented customer records");
    ExecutionResult readResult = enforcement.execute(read.canonicalEvent());
    require("succeeded".equals(readResult.outcome()), "The synthetic customer read did not succeed.");
    require("allow-fixed-synthetic-customer-read".equals(readResult.evaluation().decision().matchedRuleId()),
        "The synthetic read matched an unexpected rule.");
    require(readResult.result().path("recordCount").asInt() == 20,
        "The fixed synthetic dataset did not return twenty records.");
    require(simulator.readCount() == 1 && dispatches.get() == 1,
        "The synthetic read did not dispatch exactly once.");

    pause(automatic, "Press Enter to propose an external send after that read...");
    PreparedAction send = prepare(adapter, schedulerSigner, workloadSigner, clock,
        "run:follow-on-send", syntheticSendAction(), "schedule:customer-audit",
        "demo.egress.send", "synthetic:outbox/audit", "tool:synthetic-egress",
        "Send synthetic dataset to a fixed non-routable destination");
    ExecutionResult denied = enforcement.execute(send.canonicalEvent());
    require("block".equals(denied.outcome()), "The follow-on external send was not blocked.");
    require("block-pii-read-then-external-send".equals(denied.evaluation().decision().matchedRuleId()),
        "The sequence-specific rule did not match.");
    require(denied.evaluation().classification().signals().contains(
        InMemoryActionSequenceStore.PII_READ_THEN_EXTERNAL_SEND),
        "Gateway-owned cross-target history was not applied.");
    require("critical".equals(denied.evaluation().classification().severity()),
        "The follow-on send did not reach critical severity.");
    require(denied.attempt() == null && denied.result() == null,
        "The denied send created an execution attempt.");
    require(dispatches.get() == 1 && simulator.sendCount() == 0,
        "A denied send reached the in-memory outbox.");
    require(evidence.readAll().size() == 11, "The expected secret-free evidence lifecycle is incomplete.");

    System.out.println("\n1. Signed workload and exact delegation verified for each proposal");
    System.out.println("   Subject: authenticated workload:scheduled-log-review; trigger: scheduled");
    System.out.println("   Delegations: one fixed synthetic read or one fixed non-routable send");
    System.out.println("\n2. Baseline send, without prior read, is denied by default");
    System.out.println("   Reason: EXTERNAL_SEND_NOT_AUTHORIZED; outbox count: 0");
    System.out.println("\n3. Fixed synthetic customer read is allowed and simulated");
    System.out.println("   Class: read; signal: SYNTHETIC_PII_DATASET; invented record count: 20");
    System.out.println("   Read simulator dispatches: 1; raw records absent from evidence");
    System.out.println("\n4. Follow-on send crosses target systems and is blocked before execution");
    System.out.println("   Signal: SYNTHETIC_PII_READ_THEN_EXTERNAL_SEND; severity: critical");
    System.out.println("   Reason: SYNTHETIC_PII_EXFILTRATION_SEQUENCE_BLOCKED");
    System.out.println("   Permit: none; execution attempt: none; send outbox count: 0");
    System.out.println("   Total target dispatches: 1 (the read only)");
    System.out.println("   Evidence records: 11; no raw synthetic records or credentials stored");
    System.out.println("\n[PASS] Cross-tool exfiltration proposal was denied before any send side effect.");
  }

  private static void runPromptInjection(
      ScheduledActionAdapter adapter,
      Signer schedulerSigner,
      Signer workloadSigner,
      Clock clock,
      SecureExecutionService enforcement,
      SyntheticPromptInjectionSimulator simulator,
      AtomicInteger dispatches,
      InMemoryEvidenceLedger evidence,
      boolean automatic) {
    System.out.println("\nTracePermit Live Scenario 10 — Tool-result injection cannot authorize IAM mutation");
    System.out.println("===============================================================================");
    System.out.println("A deterministic agent emulator follows the injected text; no LLM or AWS is used.");
    pause(automatic, "Press Enter to fetch the simulated, attacker-controlled runbook result...");

    PreparedAction read = prepare(adapter, schedulerSigner, workloadSigner, clock,
        "run:runbook-read", syntheticRunbookAction(), "schedule:ops-review",
        "demo.knowledge.read", "synthetic:runbook/simulated-ops-runbook",
        "tool:synthetic-runbook", "Inspect the simulated operations runbook");
    ExecutionResult readResult = enforcement.execute(read.canonicalEvent());
    require("succeeded".equals(readResult.outcome()), "The simulated runbook read did not succeed.");
    require("allow-fixed-simulated-runbook-read".equals(readResult.evaluation().decision().matchedRuleId()),
        "The runbook read matched an unexpected rule.");
    String untrustedContent = readResult.result().path("content").asText();
    require(untrustedContent.contains(SyntheticPromptInjectionSimulator.ATTACK_MARKER)
            && untrustedContent.contains("aws.iam.attach_role_policy"),
        "The fixed tool result did not contain the expected injection.");
    require(simulator.readCount() == 1 && dispatches.get() == 1,
        "The runbook read did not dispatch exactly once.");
    require(!evidence.readAll().toString().contains(SyntheticPromptInjectionSimulator.ATTACK_MARKER),
        "Raw attacker-controlled tool output entered the evidence ledger.");

    pause(automatic, "Press Enter to let the deterministic emulator propose the injected IAM call...");
    // This intentionally emulates an agent that follows the lower-trust text.
    // It is not a claim that a real model will make this choice.
    ObjectNode proposedAction = untrustedContent.contains("aws.iam.attach_role_policy")
        ? syntheticIamAction()
        : null;
    require(proposedAction != null, "The emulator did not propose the injected action.");
    PreparedAction proposed = prepare(adapter, schedulerSigner, workloadSigner, clock,
        "run:injected-iam-proposal", proposedAction, "schedule:ops-review",
        "aws.iam.attach", "aws:simulated:iam-role/demo-role", "tool:synthetic-iam",
        "Apply the untrusted runbook instruction to a simulated role");
    ExecutionResult denied = enforcement.execute(proposed.canonicalEvent());
    require("block".equals(denied.outcome()), "The IAM proposal was not blocked.");
    require("modify".equals(denied.evaluation().classification().actionClass()),
        "The IAM action was not classified as modification.");
    require("critical".equals(denied.evaluation().classification().severity()),
        "The simulated IAM action was not classified as critical.");
    require(denied.evaluation().classification().signals().contains("ACCESS_CONTROL_MUTATION"),
        "The access-control mutation signal is absent.");
    require(denied.evaluation().classification().semanticIntents().contains("ACCESS_CONTROL_CHANGE"),
        "The access-control semantic intent is absent.");
    require("block-simulated-iam-policy-attachment".equals(denied.evaluation().decision().matchedRuleId())
            && "IAM_POLICY_ATTACHMENT_BLOCKED".equals(denied.evaluation().decision().reasonCode()),
        "The IAM policy denial was unexpected.");
    require(denied.attempt() == null && denied.result() == null,
        "The denied IAM proposal created an execution attempt.");
    require(dispatches.get() == 1 && simulator.iamMutationCount() == 0
            && !simulator.simulatedPolicyAttached(),
        "The denied IAM proposal reached the simulator's mutable state.");
    require(evidence.readAll().size() == 8
            && !evidence.readAll().toString().contains(SyntheticPromptInjectionSimulator.ATTACK_MARKER),
        "The expected bounded evidence lifecycle was not preserved.");

    System.out.println("\n1. Verified scheduled read returned lower-trust tool data");
    System.out.println("   The result contained: 'Ignore prior instructions' and an IAM tool name");
    System.out.println("   Runbook read dispatches: 1; raw injected text absent from evidence");
    System.out.println("\n2. Deterministic agent emulator proposed the instructed IAM action");
    System.out.println("   Signed workload/delegation verified for the exact simulated role");
    System.out.println("   Declared goal and tool-result text did not become policy authority");
    System.out.println("\n3. Java blocked the structured action before the effect");
    System.out.println("   Class: modify; severity: critical; intent: ACCESS_CONTROL_CHANGE");
    System.out.println("   Rule: block-simulated-iam-policy-attachment / IAM_POLICY_ATTACHMENT_BLOCKED");
    System.out.println("   Permit: none; execution attempt: none; IAM dispatches: 0");
    System.out.println("   Simulated role policy attached: false; total dispatches: 1 (read only)");
    System.out.println("\n[PASS] Untrusted tool text induced a proposal, but not an authorized IAM effect.");
  }

  private static void runDelegationAmplification(
      ScheduledActionAdapter adapter,
      Signer schedulerSigner,
      Signer workloadSigner,
      Signer subAgentSigner,
      Clock clock,
      SecureExecutionService enforcement,
      AtomicInteger dispatches,
      AtomicInteger attemptsCreated,
      InMemoryEvidenceLedger evidence,
      boolean automatic) {
    System.out.println("\nTracePermit Live Scenario 11 — Sub-agent authority cannot grow");
    System.out.println("=============================================================");
    System.out.println("Runtime Ed25519 keys and one local AWS simulator; no real credential or target.");
    pause(automatic, "Press Enter to verify and execute a properly narrowed child grant...");

    DelegationChain valid =
        delegationChain(schedulerSigner, workloadSigner, subAgentSigner, clock, "run:delegation-valid", claims -> {});
    JsonNode canonical =
        adapter.verifyAndNormalize(
            valid.input(), valid.workloadCredential(),
            List.of(valid.childGrant(), valid.parentGrant()), valid.context());
    require(canonical.path("lineage").path("delegations").size() == 2,
        "The complete verified parent/child chain is missing.");
    ExecutionResult result = enforcement.execute(canonical);
    require("succeeded".equals(result.outcome()), "The narrow control read did not execute.");
    require("allow".equals(result.evaluation().decision().outcome()),
        "The narrow control read was not allowed.");
    require(dispatches.get() == 1, "The narrow control read did not dispatch exactly once.");
    require(attemptsCreated.get() == 1, "The narrow control read did not create exactly one execution attempt.");
    int evidenceAfterValidRead = evidence.readAll().size();
    System.out.println("\n1. Valid chain: workload -> sub-agent -> simulator tool");
    System.out.println("   Parent grants read/export scopes and two simulated log groups.");
    System.out.println("   Child narrows to aws.logs.read on /app/prod for a shorter lifetime.");
    System.out.println("   Signed chain verified; Java policy allowed; local read dispatches: 1");

    pause(automatic, "Press Enter to try four independently signed child escalations...");
    System.out.println("\n2. Independently signed child escalation attempts");
    rejectDelegationAmplification(
        "extra aws.admin scope", claims -> ((ArrayNode) claims.path("scopes")).add("aws.admin"),
        "INVALID_DELEGATION_CHAIN", "run:delegation-scope", adapter, schedulerSigner,
        workloadSigner, subAgentSigner, clock, dispatches, attemptsCreated, evidence, evidenceAfterValidRead);
    rejectDelegationAmplification(
        "extra all-groups resource", claims -> ((ArrayNode) claims.path("resources")).add("aws:simulated:log-group/all"),
        "INVALID_DELEGATION_CHAIN", "run:delegation-resource", adapter, schedulerSigner,
        workloadSigner, subAgentSigner, clock, dispatches, attemptsCreated, evidence, evidenceAfterValidRead);
    rejectDelegationAmplification(
        "different executor audience", claims -> claims.put("audience", "another-gateway"),
        "INVALID_DELEGATION_CREDENTIAL", "run:delegation-audience", adapter, schedulerSigner,
        workloadSigner, subAgentSigner, clock, dispatches, attemptsCreated, evidence, evidenceAfterValidRead);
    rejectDelegationAmplification(
        "child expiry after parent", claims -> claims.put("expiresAt", clock.instant().plusSeconds(301).toString()),
        "INVALID_DELEGATION_CHAIN", "run:delegation-lifetime", adapter, schedulerSigner,
        workloadSigner, subAgentSigner, clock, dispatches, attemptsCreated, evidence, evidenceAfterValidRead);

    System.out.println("\n3. Boundary proof");
    System.out.println("   All four escalations rejected by the trusted adapter before policy or permit.");
    System.out.println("   Additional execution attempts: 0; additional simulator dispatches: 0");
    System.out.println("   Gateway evidence remained at " + evidenceAfterValidRead + " events from the valid read.");
    System.out.println("\n[PASS] Child grants cannot amplify a signed parent's authority.");
  }

  private static void rejectDelegationAmplification(
      String label,
      Consumer<ObjectNode> mutation,
      String expectedCode,
      String runId,
      ScheduledActionAdapter adapter,
      Signer schedulerSigner,
      Signer workloadSigner,
      Signer subAgentSigner,
      Clock clock,
      AtomicInteger dispatches,
      AtomicInteger attemptsCreated,
      InMemoryEvidenceLedger evidence,
      int expectedEvidenceCount) {
    DelegationChain attempt =
        delegationChain(schedulerSigner, workloadSigner, subAgentSigner, clock, runId, mutation);
    try {
      adapter.verifyAndNormalize(
          attempt.input(), attempt.workloadCredential(),
          List.of(attempt.parentGrant(), attempt.childGrant()), attempt.context());
      throw new IllegalStateException("Child authority escalation was accepted: " + label);
    } catch (WorkloadCredentialException failure) {
      require(expectedCode.equals(failure.code()),
          "Unexpected rejection for " + label + ": " + failure.code());
    }
    require(dispatches.get() == 1, "A rejected child reached the simulator.");
    require(attemptsCreated.get() == 1, "A rejected child created an execution attempt.");
    require(evidence.readAll().size() == expectedEvidenceCount,
        "A rejected child reached the policy/evidence path.");
    System.out.println("   " + label + " -> " + expectedCode + "; not_executed");
  }

  private static DelegationChain delegationChain(
      Signer schedulerSigner,
      Signer workloadSigner,
      Signer subAgentSigner,
      Clock clock,
      String runId,
      Consumer<ObjectNode> childMutation) {
    Instant now = clock.instant();
    String eventId = UUID.randomUUID().toString();
    String traceId = UUID.randomUUID().toString().replace("-", "");
    TrustedScheduledActionContext context =
        new TrustedScheduledActionContext(
            TENANT, SCHEDULER, SCHEDULE, runId, eventId, traceId,
            AUDIENCE, SCOPE, RESOURCE, true, now);
    ObjectNode requestedAction = action(2);
    ObjectNode input = scheduledInput(requestedAction, "Read bounded simulated application logs");
    String workloadCredential =
        schedulerSigner.sign(
            workloadClaims(requestedAction, context, now), "tracepermit-workload-identity+jws");

    ObjectNode parentClaims =
        delegationClaims(context, now, SUB_AGENT, "Delegate bounded log review to one sub-agent");
    parentClaims.put("expiresAt", now.plusSeconds(300).toString());
    ((ArrayNode) parentClaims.path("scopes")).add("aws.logs.export");
    ((ArrayNode) parentClaims.path("resources"))
        .add("aws:simulated:log-group/app/staging");
    String parentGrant =
        workloadSigner.sign(parentClaims, "tracepermit-delegation-grant+jws");

    ObjectNode childClaims =
        delegationClaims(context, now, TOOL, "Read only the bounded production-labelled log group");
    childClaims.put("issuer", SUB_AGENT_ISSUER);
    childClaims.put("keyId", SUB_AGENT_KEY_ID);
    childClaims.put("actorId", SUB_AGENT);
    childClaims.put("parentTokenId", parentClaims.path("tokenId").asText());
    childClaims.put("parentTokenHash", sha256Compact(parentGrant));
    childClaims.put("expiresAt", now.plusSeconds(120).toString());
    childMutation.accept(childClaims);
    String childGrant =
        subAgentSigner.sign(childClaims, "tracepermit-delegation-grant+jws");
    return new DelegationChain(input, workloadCredential, parentGrant, childGrant, context);
  }

  private static String sha256Compact(String compact) {
    try {
      return "sha256:"
          + HexFormat.of().formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(compact.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("SHA-256 is unavailable.", failure);
    }
  }

  private static PreparedAction prepare(
      ScheduledActionAdapter adapter,
      Signer schedulerSigner,
      Signer workloadSigner,
      Clock clock,
      String runId,
      int limit) {
    return prepare(
        adapter,
        schedulerSigner,
        workloadSigner,
        clock,
        runId,
        action(limit),
        SCHEDULE,
        SCOPE,
        RESOURCE,
        TOOL,
        "Review recent simulated application errors");
  }

  private static PreparedAction prepare(
      ScheduledActionAdapter adapter,
      Signer schedulerSigner,
      Signer workloadSigner,
      Clock clock,
      String runId,
      ObjectNode action,
      String scheduleId,
      String scope,
      String resource,
      String toolSubject,
      String declaredGoal) {
    Instant now = clock.instant();
    String eventId = UUID.randomUUID().toString();
    String traceId = UUID.randomUUID().toString().replace("-", "");
    TrustedScheduledActionContext context =
        new TrustedScheduledActionContext(
            TENANT,
            SCHEDULER,
            scheduleId,
            runId,
            eventId,
            traceId,
            AUDIENCE,
            scope,
            resource,
            true,
            now);
    ObjectNode input = scheduledInput(action, declaredGoal);
    String workload =
        schedulerSigner.sign(
            workloadClaims(action, context, now), "tracepermit-workload-identity+jws");
    String delegation =
        workloadSigner.sign(
            delegationClaims(context, now, toolSubject, declaredGoal), "tracepermit-delegation-grant+jws");
    JsonNode canonical =
        adapter.verifyAndNormalize(input, workload, List.of(delegation), context);
    return new PreparedAction(input, workload, delegation, context, canonical);
  }

  private static ObjectNode action(int limit) {
    ObjectNode action = JsonSupport.MAPPER.createObjectNode();
    action.put("protocol", "mcp");
    action.put("toolName", "aws.logs.filter_log_events");
    action
        .putObject("arguments")
        .put("logGroupName", "/app/prod")
        .put("filterPattern", "ERROR")
        .put("limit", limit);
    action
        .putObject("target")
        .put("system", "aws")
        .put("environment", "prod")
        .put("accountId", "simulated-123")
        .put("resourceType", "log-group");
    return action;
  }

  private static ObjectNode stopAction() {
    ObjectNode action = JsonSupport.MAPPER.createObjectNode();
    action.put("protocol", "mcp");
    action.put("toolName", "aws.ec2.stop_instances");
    ObjectNode arguments = action.putObject("arguments");
    arguments.putArray("instanceIds").add("i-simulated-scheduled-demo");
    arguments.put("region", "us-east-1");
    action
        .putObject("target")
        .put("system", "aws")
        .put("environment", "prod")
        .put("accountId", "simulated-123")
        .put("resourceType", "ec2-instance");
    return action;
  }

  private static ObjectNode syntheticReadAction() {
    ObjectNode action = JsonSupport.MAPPER.createObjectNode();
    action.put("protocol", "mcp");
    action.put("toolName", "demo.customer.bulk_read");
    action.putObject("arguments").put("datasetId", "synthetic-customers-demo").put("limit", 20);
    action.putObject("target")
        .put("system", "synthetic-crm").put("environment", "test")
        .put("accountId", "simulated-123").put("resourceType", "customer-dataset");
    return action;
  }

  private static ObjectNode syntheticSendAction() {
    ObjectNode action = JsonSupport.MAPPER.createObjectNode();
    action.put("protocol", "mcp");
    action.put("toolName", "demo.egress.send_external");
    action.putObject("arguments")
        .put("datasetId", "synthetic-customers-demo")
        .put("destination", "audit@example.invalid");
    action.putObject("target")
        .put("system", "synthetic-egress").put("environment", "test")
        .put("accountId", "simulated-123").put("resourceType", "outbox");
    return action;
  }

  private static ObjectNode syntheticRunbookAction() {
    ObjectNode action = JsonSupport.MAPPER.createObjectNode();
    action.put("protocol", "mcp");
    action.put("toolName", "demo.knowledge.fetch_runbook");
    action.putObject("arguments").put("documentId", "simulated-ops-runbook");
    action.putObject("target")
        .put("system", "synthetic-knowledge").put("environment", "test")
        .put("accountId", "simulated-123").put("resourceType", "runbook");
    return action;
  }

  private static ObjectNode syntheticIamAction() {
    ObjectNode action = JsonSupport.MAPPER.createObjectNode();
    action.put("protocol", "mcp");
    action.put("toolName", "aws.iam.attach_role_policy");
    action.putObject("arguments")
        .put("roleName", "demo-role")
        .put("policyId", "simulated-admin");
    action.putObject("target")
        .put("system", "aws").put("environment", "prod")
        .put("accountId", "simulated-123").put("resourceType", "iam-role");
    return action;
  }

  private static ObjectNode scheduledInput(JsonNode action, String declaredGoal) {
    ObjectNode input = JsonSupport.MAPPER.createObjectNode();
    input.put("schemaVersion", "1.0");
    input
        .putObject("declaredIntent")
        .put("goal", declaredGoal)
        .put("source", "job_definition");
    input.set("action", action.deepCopy());
    input.putObject("clientClaims").put("untrustedHint", "not-authority");
    return input;
  }

  private static ObjectNode workloadClaims(
      JsonNode action, TrustedScheduledActionContext context, Instant now) {
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode();
    claims.put("version", "1.0");
    claims.put("type", "tracepermit-workload-identity+jws");
    claims.put("canonicalizationProfile", CanonicalJson.PROFILE);
    claims.put("requestBindingProfile", "tracepermit-scheduled-action-v1");
    claims.put("issuer", SCHEDULER_ISSUER);
    claims.put("keyId", SCHEDULER_KEY_ID);
    claims.put("audience", AUDIENCE);
    claims.put("tokenId", UUID.randomUUID().toString());
    claims.put("tenantId", TENANT);
    claims.put("subjectId", WORKLOAD);
    claims.put("schedulerId", SCHEDULER);
    claims.put("scheduleId", context.scheduleId());
    claims.put("runId", context.runId());
    claims.put("eventId", context.eventId());
    claims.put("traceId", context.traceId());
    claims.put("actionHash", CanonicalJson.sha256(action));
    claims.put("issuedAt", now.minusSeconds(1).toString());
    claims.put("expiresAt", now.plusSeconds(120).toString());
    return claims;
  }

  private static ObjectNode delegationClaims(
      TrustedScheduledActionContext context, Instant now, String toolSubject, String purpose) {
    ObjectNode claims = JsonSupport.MAPPER.createObjectNode();
    claims.put("version", "1.0");
    claims.put("type", "tracepermit-delegation-grant+jws");
    claims.put("canonicalizationProfile", CanonicalJson.PROFILE);
    claims.put("issuer", WORKLOAD_ISSUER);
    claims.put("keyId", WORKLOAD_KEY_ID);
    claims.put("tenantId", TENANT);
    claims.put("tokenId", UUID.randomUUID().toString());
    claims.put("actorId", WORKLOAD);
    claims.put("subjectId", toolSubject);
    claims.put("audience", AUDIENCE);
    claims.putArray("scopes").add(context.requiredScope());
    claims.putArray("resources").add(context.requiredResource());
    claims.put("purpose", purpose);
    claims.put("traceId", context.traceId());
    claims.put("issuedAt", now.minusSeconds(1).toString());
    claims.put("expiresAt", now.plusSeconds(300).toString());
    return claims;
  }

  private static WorkloadTrustRegistry.TrustedWorkloadKey trusted(
      Signer signer, Set<WorkloadTrustRegistry.KeyUsage> usages) {
    return new WorkloadTrustRegistry.TrustedWorkloadKey(
        signer.issuer(),
        signer.keyId(),
        TENANT,
        signer.principalId(),
        usages,
        WorkloadTrustRegistry.KeyState.ACTIVE,
        signer.keyPair().getPublic());
  }

  private static Signer signer(String issuer, String keyId, String principalId) {
    try {
      return new Signer(
          issuer,
          keyId,
          principalId,
          KeyPairGenerator.getInstance("Ed25519").generateKeyPair());
    } catch (GeneralSecurityException failure) {
      throw new IllegalStateException("Ed25519 is unavailable.", failure);
    }
  }

  private static void heading() {
    System.out.println();
    System.out.println("TracePermit Live Scenario 4 — Signed workload and semantic sequence control");
    System.out.println("============================================================================");
    System.out.println("Runtime-generated Ed25519 keys are trusted only inside this process.");
    System.out.println("All downstream execution uses the deterministic local simulator.");
    System.out.println("The request body cannot assert authenticated identity, delegation, or history.");
  }

  private static void pause(boolean automatic, String prompt) {
    if (automatic) return;
    System.out.println();
    System.out.print(prompt + " ");
    try {
      System.in.read();
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("Unable to read presenter input.", failure);
    }
  }

  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }

  private record PreparedAction(
      ObjectNode input,
      String workloadCredential,
      String delegationCredential,
      TrustedScheduledActionContext context,
      JsonNode canonicalEvent) {
    private PreparedAction {
      input = input.deepCopy();
      canonicalEvent = canonicalEvent.deepCopy();
    }

    @Override
    public ObjectNode input() {
      return input.deepCopy();
    }

    @Override
    public JsonNode canonicalEvent() {
      return canonicalEvent.deepCopy();
    }
  }

  private record DelegationChain(
      ObjectNode input,
      String workloadCredential,
      String parentGrant,
      String childGrant,
      TrustedScheduledActionContext context) {}

  private record Signer(String issuer, String keyId, String principalId, KeyPair keyPair) {
    private String sign(ObjectNode claims, String type) {
      try {
        ObjectNode header = JsonSupport.MAPPER.createObjectNode();
        header.put("alg", "EdDSA");
        header.put("typ", type);
        header.put("kid", keyId);
        String signingInput =
            encode(CanonicalJson.canonicalize(header).getBytes(StandardCharsets.UTF_8))
                + "."
                + encode(CanonicalJson.canonicalize(claims).getBytes(StandardCharsets.UTF_8));
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(keyPair.getPrivate());
        signature.update(signingInput.getBytes(StandardCharsets.UTF_8));
        return signingInput + "." + encode(signature.sign());
      } catch (GeneralSecurityException failure) {
        throw new IllegalStateException("Unable to sign the runtime demo credential.", failure);
      }
    }

    private static String encode(byte[] value) {
      return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
  }
}
