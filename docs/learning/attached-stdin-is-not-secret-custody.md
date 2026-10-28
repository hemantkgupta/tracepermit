# Attached stdin is not secret custody

A container's interactive-input flag does not prove what descriptor the Java
process receives. The same installed provenance check must inspect the actual
parent and worker descriptors. Creating another pipe inside the container would
test a different path and could hide a transport incompatibility.

The first rehearsal gate therefore uses the unchanged checker on actual attached
stdin, with independent file, linked/unlinked FIFO and terminal negatives. No
private frame is sent through those negative paths. Valid application framing
and mandatory EOF are then tested on the original parent/worker command.

Even successful descriptor checks do not authenticate the caller, approve an
operation or hide keys from the Docker daemon, host administrator or remote
stream recorder. Configuration remains operator data, and current trusted
identity, independent review, original permit and acknowledged durable start
remain required for a simulated effect. Unknown completion still forbids resend.

An archive built under `umask 077` may contain0600 libraries. Giving those files
root ownership and removing writes does not make them readable by a non-root
runtime. Normalize permissions for public compiled artifacts separately from
the private input/evidence policy, then actually open the installed libraries
as the runtime UID. Never apply that public readability rule to frames or keys.

Keep local OCI observations separate from EKS input/audit custody, kernel
isolation and target-side authorization. See [ADR0098](../architecture/0098-container-private-input-qualification.md)
and its [manual runbook](../runbooks/eks-container-private-input.md).
