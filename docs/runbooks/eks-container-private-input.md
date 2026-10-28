# Manual EKS container/private-input rehearsal

Status: **public-artifact permission repair implemented; refreshed validation pending**.
No cluster deployment, provider access or real credential is authorized.
See [ADR0098](../architecture/0098-container-private-input-qualification.md), the
[offline bundle](eks-synthetic-sandbox-bundle.md) and the
[organization acceptance plan](org-sandbox-acceptance-plan.md).

## Boundary

The rehearsal tests local Linux-container plumbing for the unchanged merged
synthetic lifecycle operator. It does not install Air's unpublished organization
operator or import another worker's fault helpers. Only generated fixture keys,
tokens, test-store credentials and fake target identities are used. Application
frames are created in host memory and sent through attach stdin, never argv,
environment, mounted files, image layers or evidence. JVM/daemon memory erasure
and protection from privileged operators are not established.

File/FIFO negatives contain a fixed public sentinel, not a real configuration
or credential. The TTY negative is a descriptor-only probe with no private input.
Actual application calls always use no TTY and the exact production receipt.

At `6f35ba2`, human focused18/18 and local image assembly passed. Transport then
reported6pass/1failure/1fail-fast skip, with4 planned cases unrun. The first
application launch failed before a receipt; saved counts are exit1,0 stdout
bytes and182 stderr bytes. All7 owned containers were observed removed. Archive
inspection found the lifecycle JAR at0600; root ownership and write-bit removal
left it unreadable to UID10001. Both image assembly paths now normalize only
public installed artifacts to readable/traversable, immutable permissions.
The containment probe also opens every bounded installed library as UID10001.
Re-run the source-pinned stages on the revised candidate manually; the earlier
image and receipts remain historical evidence and do not qualify this repair.

Docker CLI clients use a private empty configuration with an explicit local
Unix socket, so client proxy settings are not injected. Global Docker settings
are not rewritten. Image/container environment checks reject credential, proxy
and injected JVM/shell options before private frames. The cached base must match
the daemon's native Linux architecture; no emulation fallback is selected.

## Ordered human-run stages

The coordinator supplies a private, checkout-specific Bash runner. It pins branch,
commit/tree, source and manual-policy hashes, prepared Java21/Gradle8.14.3,
Node22+/pnpm11.25.0 and the offline one-worker cache. Logs and numeric exits go
to a fresh0700 directory outside Git. The writer does not execute the runner.

1. **focused:** compile the new container classes without executing them, then
   run `:eks-sandbox-bundle:test` freshly. Expected EKS cases:18, comprising the
   original10 plus8 offline harness controls. No Docker call/image/container is
   needed. Retain archive digest/inventory and source guards. Stop on failure.
2. **inventory:** inspect the existing local Unix-socket Docker context and
   cached image identifiers only. No pull, build, create, start or prune. The
   human selects an actual cached Linux Java21 repository digest that provides
   `/opt/java/openjdk/bin/java` and the shell/tar/checksum utilities already
   required by the existing image recipe. Missing prerequisites are blockers,
   not permission to install a replacement. Keep architecture consistent.
3. **image:** with the selected exact digest, assemble a local image from the
   actual current lifecycle and EKS archives. Capture before/after hashes,
   inventory, immutable base/runtime IDs and source identity. Only an owned
   network-disabled assembly container is started; it is removed after the
   stopped filesystem is committed. The resulting local image is retained as
   evidence, without push/tag cleanup or claims of qualification.
4. **transport:** run only `ContainerPrivateInputQualificationTest`,12 cases,
   fail-fast. The first descriptor positive must pass. Other cases cover actual
   UID/capability/write and installed-library readability observations,
   file/linked FIFO/unlinked FIFO/TTY refusal,
   valid offline check-config in parent+worker, trailing/oversized frames, held
   EOF under the unchanged parent budget, capped stdout/stderr pressure and
   interrupted attach with observed owned-container removal. If provenance
   refuses, report the incompatibility and stop without an alternate pipe path.
5. **postgres:** only after the transport receipt passes, run the two
   `ContainerPostgresLifecycleQualificationTest` cases. Require prepared PG16.4
   and Testcontainers helper images. Their JVM's image policy refuses missing
   images rather than pulling. Each case uses exact restricted test stores and
   independent host observations; execution/reconciliation runs in fresh
   containers. Registration and profile activation use accepted fixture APIs,
   not a new installed registration command. Retain success versus known
   synthetic unknown and refuse another delivery without new durable events.
6. **repo:** after the preceding exact-source stages pass, run `pnpm check`
   once, formatting, links/helpers,13-flow/drift and diff checks. Retain aggregate
   coverage at least94% LINE/75% BRANCH and actual reused/up-to-date limits.
   Do not schedule a redundant second full `checkAll` invocation.

All new container cases must execute; no new skipped-case fallback is defined.
The default repository gate does not invoke container qualification, so a green
default gate alone is insufficient. Stop each stage at its first nonzero exit;
the human reviews any retry separately. Preserve every failure and prior source
snapshot. Re-pin commands after a source change rather than reusing old receipts.

## Observations and limits

The runtime profile requires exact owner/source/image metadata and constrained
mounts, namespaces, capabilities, resources and logging. The fixture JAR is not
shipped in the ordinary distribution, and application calls mount no host files.
The local image is assembled by executing the existing extract/permission recipe
against an immutable cached base ID. It does not prove Dockerfile-frontend or
registry build reproducibility. No generic artifact/evidence verifier is added;
that remains Cloud2's separate owner scope.

PID/resource declarations are not independent measurements of kernel exhaustion
limits. Force-removal observations prove the owned container is absent from the
queried local daemon, not that a target had no late effect. No target invocation
count or exactly-once result follows from a database event count. The temporary
PG network-namespace topology is not EKS native-sidecar/PVC qualification.

Docker daemon/root visibility, EKS attach/exec streaming, API audit/recording,
operator enrollment, real provider keys, bypass-resistant routing, CNI/RBAC and
hostile-node isolation remain open. Do not send customer credentials or turn
these commands into a deployment/resume procedure. A successful synthetic
container test does not authorize the org sandbox or promote a requirement ID.
