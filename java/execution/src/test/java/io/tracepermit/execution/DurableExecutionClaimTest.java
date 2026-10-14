package io.tracepermit.execution;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DurableExecutionClaimTest {
  private static final Instant NOW = Instant.parse("2026-10-08T10:15:31Z");

  @Test
  void trustedTenantAndClaimFieldsAreBoundedAndDefensivelyCopied() {
    assertThat(new TenantPartition("tenant-1").value()).isEqualTo("tenant-1");
    assertThatThrownBy(() -> new TenantPartition("../tenant"))
        .isInstanceOf(IllegalArgumentException.class);
    java.util.ArrayList<String> fingerprints = new java.util.ArrayList<>(List.of(hash('a')));
    DurableExecutionClaim claim = claim(fingerprints);
    fingerprints.clear();
    assertThat(claim.credentialFingerprints()).containsExactly(hash('a'));
    DurableExecutionClaim issuerQualified =
        new DurableExecutionClaim(
            new TenantPartition("acme"),
            "permit",
            NOW.plusSeconds(30),
            "adapter",
            hash('1'),
            "event",
            "trace",
            "evaluation",
            "https://identity.example.invalid#user:alice",
            hash('2'),
            "policy",
            1,
            "allow",
            List.of(),
            NOW);
    assertThat(issuerQualified.subjectId())
        .isEqualTo("https://identity.example.invalid#user:alice");
  }

  @Test
  void invalidSecurityFieldsFailBeforePersistence() {
    assertThatThrownBy(() -> new DurableExecutionClaim(
        new TenantPartition("acme"), "permit", NOW.plusSeconds(1), "adapter", hash('1'),
        "event", "trace", "evaluation", "subject", "not-a-hash", "policy", 1,
        "allow", List.of(), NOW)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new DurableExecutionClaim(
        new TenantPartition("acme"), "permit", NOW, "adapter", hash('1'),
        "event", "trace", "evaluation", "subject", hash('2'), "policy", 1,
        "allow", List.of(), NOW)).hasMessage("The execution permit is expired.");
    assertThatThrownBy(() -> new DurableExecutionClaim(
        new TenantPartition("acme"), "permit", NOW.plusSeconds(1), "adapter", hash('1'),
        "event", "trace", "evaluation", "subject", hash('2'), "policy", 1,
        "block", List.of(), NOW)).hasMessage("Only allow decisions can be durably claimed.");
  }

  @Test
  void invalidPolicyAndCredentialBoundsFailBeforePersistence() {
    assertThatThrownBy(() -> new DurableExecutionClaim(
        new TenantPartition("acme"), "permit", NOW.plusSeconds(1), "adapter", hash('1'),
        "event", "trace", "evaluation", "subject", hash('2'), "policy", 0,
        "allow", List.of(), NOW)).hasMessage("The policy version is invalid.");
    assertThatThrownBy(() -> claim(java.util.Collections.nCopies(17, hash('a'))))
        .hasMessage("Too many credential fingerprints.");
    assertThatThrownBy(() -> claim(List.of("sha256:not-a-fingerprint")))
        .hasMessage("The credential fingerprint is invalid.");
    assertThatThrownBy(() -> new DurableExecutionClaim(
        new TenantPartition("acme"), "", NOW.plusSeconds(1), "adapter", hash('1'),
        "event", "trace", "evaluation", "subject", hash('2'), "policy", 1,
        "allow", List.of(), NOW)).hasMessage("The permit ID is invalid.");
    assertThatThrownBy(() -> new DurableExecutionClaim(
        new TenantPartition("acme"), "p".repeat(257), NOW.plusSeconds(1), "adapter", hash('1'),
        "event", "trace", "evaluation", "subject", hash('2'), "policy", 1,
        "allow", List.of(), NOW)).hasMessage("The permit ID is invalid.");
    assertThatThrownBy(() -> new DurableExecutionClaim(
        new TenantPartition("acme"), "../permit", NOW.plusSeconds(1), "adapter", hash('1'),
        "event", "trace", "evaluation", "subject", hash('2'), "policy", 1,
        "allow", List.of(), NOW)).hasMessage("The permit ID is invalid.");
    assertThatThrownBy(() -> new DurableExecutionClaim(
        new TenantPartition("acme"), "permit#fragment", NOW.plusSeconds(1), "adapter", hash('1'),
        "event", "trace", "evaluation", "subject", hash('2'), "policy", 1,
        "allow", List.of(), NOW)).hasMessage("The permit ID is invalid.");
  }

  @Test
  void approvalBindingsRequireExactSingleUseApprovalSemantics() {
    ApprovalBinding binding = approvalBinding();
    assertThat(binding.originalDecision()).isEqualTo("require_approval");
    assertThat(binding.maxUses()).isEqualTo(1);
    assertThatThrownBy(() -> new ApprovalBinding(
        binding.tenant(), binding.approvalRequestId(), binding.requestHash(), binding.actionHash(),
        binding.eventId(), binding.evaluationId(), binding.subjectId(), binding.actorLineageHash(),
        binding.policyId(), binding.policyVersion(), "allow", binding.toolName(),
        binding.resourceHash(), binding.approvalGroup(), binding.createdAt(), binding.expiresAt(), 1))
        .hasMessage("An approval must preserve a require_approval decision.");
    assertThatThrownBy(() -> new ApprovalBinding(
        binding.tenant(), binding.approvalRequestId(), binding.requestHash(), binding.actionHash(),
        binding.eventId(), binding.evaluationId(), binding.subjectId(), binding.actorLineageHash(),
        binding.policyId(), binding.policyVersion(), binding.originalDecision(), binding.toolName(),
        binding.resourceHash(), binding.approvalGroup(), binding.createdAt(), binding.expiresAt(), 2))
        .hasMessage("Approvals must be single use.");
    assertThatThrownBy(() -> new ApprovalBinding(
        binding.tenant(), binding.approvalRequestId(), binding.requestHash(), binding.actionHash(),
        binding.eventId(), binding.evaluationId(), binding.subjectId(), binding.actorLineageHash(),
        binding.policyId(), 0, binding.originalDecision(), binding.toolName(),
        binding.resourceHash(), binding.approvalGroup(), binding.createdAt(), binding.expiresAt(), 1))
        .hasMessage("The policy version is invalid.");
    assertThatThrownBy(() -> new ApprovalBinding(
        binding.tenant(), binding.approvalRequestId(), binding.requestHash(), binding.actionHash(),
        binding.eventId(), binding.evaluationId(), binding.subjectId(), binding.actorLineageHash(),
        binding.policyId(), binding.policyVersion(), binding.originalDecision(), binding.toolName(),
        binding.resourceHash(), binding.approvalGroup(), binding.createdAt(), binding.createdAt(), 1))
        .hasMessage("The approval expiry must follow its creation time.");
  }

  @Test
  void approvalDecisionAndStateRejectInvalidLifecycleData() {
    ApprovalBinding binding = approvalBinding();
    assertThatThrownBy(() -> new ApprovalDecision(
        binding.tenant(), binding.approvalRequestId(), "decision-1", "reviewer-1", "oidc",
        hash('f'), "approve", "not-a-reason", NOW))
        .hasMessage("The approval reason code is invalid.");
    assertThatThrownBy(() -> new ApprovalRequestState(
        binding, "invalid", 1, null, null, null, null, null, null, NOW, null, null))
        .hasMessage("The approval status is invalid.");
  }

  @Test
  void approvalExecutionClaimRejectsAnyBindingMismatch() {
    ApprovalBinding binding = approvalBinding();
    DurableExecutionClaim execution = new DurableExecutionClaim(
        binding.tenant(), "permit", NOW.plusSeconds(30), "adapter", hash('1'),
        binding.eventId(), "trace", binding.evaluationId(), binding.subjectId(),
        binding.actionHash(), binding.policyId(), binding.policyVersion(), "allow", List.of(), NOW);
    assertThat(new ApprovalExecutionClaim(binding, "decision-1", execution, NOW).binding())
        .isEqualTo(binding);
    DurableExecutionClaim changed = new DurableExecutionClaim(
        binding.tenant(), "permit", NOW.plusSeconds(30), "adapter", hash('1'),
        binding.eventId(), "trace", binding.evaluationId(), binding.subjectId(),
        hash('e'), binding.policyId(), binding.policyVersion(), "allow", List.of(), NOW);
    assertThatThrownBy(() -> new ApprovalExecutionClaim(binding, "decision-1", changed, NOW))
        .hasMessage("The approval and execution claim bindings differ.");
    DurableExecutionClaim wrongTenant = new DurableExecutionClaim(
        new TenantPartition("other"), "permit", NOW.plusSeconds(30), "adapter", hash('1'),
        binding.eventId(), "trace", binding.evaluationId(), binding.subjectId(),
        binding.actionHash(), binding.policyId(), binding.policyVersion(), "allow", List.of(), NOW);
    assertThatThrownBy(() -> new ApprovalExecutionClaim(binding, "decision-1", wrongTenant, NOW))
        .hasMessage("The approval and execution claim bindings differ.");
  }

  private static DurableExecutionClaim claim(List<String> fingerprints) {
    return new DurableExecutionClaim(
        new TenantPartition("acme"), "permit", NOW.plusSeconds(30), "adapter", hash('1'),
        "event", "trace", "evaluation", "subject", hash('2'), "policy", 1,
        "allow", fingerprints, NOW);
  }

  private static ApprovalBinding approvalBinding() {
    return new ApprovalBinding(
        new TenantPartition("acme"), "approval-1", hash('a'), hash('b'), "event",
        "evaluation", "subject", hash('c'), "policy", 2, "require_approval",
        "aws.ec2.update_security_group", hash('d'), "security-reviewers", NOW,
        NOW.plusSeconds(300), 1);
  }

  private static String hash(char digit) {
    return "sha256:" + String.valueOf(digit).repeat(64);
  }
}
