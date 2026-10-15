import { ShieldCheck } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import {
  ApprovalApiError,
  canExecute,
  canRequest,
  canReview,
  decideApproval,
  isExpired,
  loadApprovalSession,
  loadApprovals,
  signOut,
  submitApproval,
  type Approval,
  type ApprovalSession,
} from "./approval-api.js";

type Confirmation = {
  approval: Approval;
  decision: "approve" | "deny" | "execute";
};

export function ApprovalInbox() {
  const [session, setSession] = useState<ApprovalSession | null>(null);
  const [items, setItems] = useState<Approval[]>([]);
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const [revision, setRevision] = useState(0);
  const [port, setPort] = useState("443");
  const [cidr, setCidr] = useState("192.0.2.0/24");
  const selected = items.find((item) => item.approvalRequestId === selectedId);

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    setConfirmation(null);
    void Promise.all([
      loadApprovalSession(controller.signal),
      loadApprovals(controller.signal),
    ])
      .then(([nextSession, nextItems]) => {
        setSession(nextSession);
        setItems(nextItems);
        setSelectedId((current) =>
          nextItems.some((item) => item.approvalRequestId === current)
            ? current
            : (nextItems[0]?.approvalRequestId ?? null),
        );
        setError(null);
      })
      .catch((failure: unknown) => {
        if (controller.signal.aborted) return;
        setItems([]);
        setSession(null);
        if (!(failure instanceof ApprovalApiError && failure.status === 401))
          setError(message(failure));
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoading(false);
      });
    return () => controller.abort();
  }, [revision]);

  async function perform(operation: () => Promise<unknown>, success: string) {
    if (submitting.current) return;
    submitting.current = true;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await operation();
      setConfirmation(null);
      setNotice(success);
      setRevision((value) => value + 1);
    } catch (failure: unknown) {
      setError(message(failure));
      setConfirmation(null);
      if (failure instanceof ApprovalApiError && failure.status === 401) {
        setSession(null);
        setItems([]);
      }
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  }

  return (
    <main className="approval-shell">
      <header className="approval-header">
        <div className="brand-lockup">
          <div className="brand-mark">
            <ShieldCheck size={24} />
          </div>
          <div>
            <div className="brand-name">TracePermit</div>
            <div className="brand-subtitle">Exact-action approvals</div>
          </div>
        </div>
        <span className="approval-simulator">
          Simulator only · local browser profile
        </span>
      </header>
      <div className="approval-heading">
        <div>
          <p className="eyebrow">HUMAN CONTROL, PRECISE AUTHORITY</p>
          <h1>Approval inbox</h1>
          <p>Review the exact action. Approval is not execution.</p>
        </div>
        {session && (
          <div className="approval-toolbar">
            <button
              disabled={busy || loading}
              onClick={() => setRevision((value) => value + 1)}
            >
              Refresh
            </button>
            <button
              disabled={busy}
              onClick={() =>
                void perform(() => signOut(session), "Signed out.")
              }
            >
              Sign out
            </button>
          </div>
        )}
      </div>
      {error && (
        <p className="approval-error" role="alert">
          {error}
        </p>
      )}
      {notice && (
        <p className="approval-notice" role="status">
          {notice}
        </p>
      )}
      {loading ? (
        <p role="status">Loading current authority and approval state…</p>
      ) : !session ? (
        <section className="approval-card approval-signin">
          <ShieldCheck size={40} />
          <h2>Sign in to your approval workspace</h2>
          <p>
            Your identity comes from the configured provider. Your workspace and
            roles come from operator enrollment.
          </p>
          <a className="approval-primary" href="/auth/login">
            Sign in with identity provider
          </a>
          <p className="approval-muted">
            No tokens are pasted into this console or saved in browser storage.
            No real AWS service is contacted.
          </p>
        </section>
      ) : (
        <>
          <section
            className="approval-card approval-identity"
            aria-label="Current identity"
          >
            <div>
              <strong>{session.roles.join(" · ")}</strong>
              <p>
                {session.tenantId} · session ends{" "}
                {new Date(session.expiresAt).toLocaleTimeString()}
              </p>
              <code>{session.principalId}</code>
            </div>
            <span className="approval-muted">
              Server-verified identity
              <br />
              Operator-assigned roles
            </span>
          </section>
          {canRequest(session) && (
            <form
              className="approval-card approval-request"
              onSubmit={(event) => {
                event.preventDefault();
                void perform(
                  () =>
                    submitApproval(session, {
                      groupId: "sg-demo",
                      port: Number(port),
                      cidr,
                    }),
                  "Exact simulated change submitted for independent review; not executed.",
                );
              }}
            >
              <div>
                <h2>Request a simulated change</h2>
                <p>
                  Update security group <code>sg-demo</code>. Documentation-only
                  CIDRs, simulator target.
                </p>
              </div>
              <label>
                Port
                <input
                  required
                  type="number"
                  min="1"
                  max="65535"
                  value={port}
                  onChange={(event) => setPort(event.target.value)}
                />
              </label>
              <label>
                Source CIDR
                <select
                  value={cidr}
                  onChange={(event) => setCidr(event.target.value)}
                >
                  {["192.0.2.0/24", "198.51.100.0/24", "203.0.113.0/24"].map(
                    (value) => (
                      <option key={value}>{value}</option>
                    ),
                  )}
                </select>
              </label>
              <button className="approval-primary" disabled={busy}>
                Request approval
              </button>
            </form>
          )}
          <div className="approval-grid">
            <section
              className="approval-card approval-list"
              aria-label="Approval requests"
            >
              <h2>
                Requests{" "}
                <span className="approval-muted">
                  {items.length} / latest 50
                </span>
              </h2>
              <p className="approval-muted">
                {session.roles.length === 1 && session.roles[0] === "REQUESTER"
                  ? "Only your requests are visible."
                  : "Workspace-scoped requests."}
              </p>
              {items.length === 0 && (
                <p>No approval requests in your visible scope.</p>
              )}
              {items.map((item) => (
                <button
                  className={`approval-row${selectedId === item.approvalRequestId ? " selected" : ""}`}
                  aria-pressed={selectedId === item.approvalRequestId}
                  disabled={busy}
                  key={item.approvalRequestId}
                  onClick={() => {
                    setSelectedId(item.approvalRequestId);
                    setConfirmation(null);
                  }}
                >
                  <span>
                    <strong>{item.action.toolName}</strong>
                    <small>
                      {item.approvalRequestId.slice(0, 8)} ·{" "}
                      {new Date(item.expiresAt).toLocaleTimeString()}
                    </small>
                  </span>
                  <span className={`approval-status ${item.status}`}>
                    {isExpired(item) && item.status === "pending"
                      ? "expired"
                      : item.status}
                  </span>
                </button>
              ))}
            </section>
            <section
              className="approval-card approval-detail"
              aria-label="Exact action detail"
            >
              {selected ? (
                <>
                  <div className="approval-detail-title">
                    <h2>Exact action</h2>
                    <span className={`approval-status ${selected.status}`}>
                      {selected.status}
                    </span>
                  </div>
                  <h3>{selected.action.toolName}</h3>
                  <p className="approval-muted">
                    Safe-rendered data, not instructions. All downstream effects
                    are simulated.
                  </p>
                  <dl>
                    <dt>Requester</dt>
                    <dd>
                      <code>{selected.requesterId}</code>
                    </dd>
                    <dt>Reviewer</dt>
                    <dd>
                      <code>{selected.reviewerId ?? "Not yet reviewed"}</code>
                    </dd>
                    <dt>Policy</dt>
                    <dd>
                      {selected.policyId} · v{selected.policyVersion} ·{" "}
                      {selected.originalPolicyOutcome}
                    </dd>
                    <dt>Expiry</dt>
                    <dd>
                      {new Date(selected.expiresAt).toLocaleString()}{" "}
                      {isExpired(selected) ? "(expired)" : ""}
                    </dd>
                    <dt>Execution</dt>
                    <dd>
                      <strong>
                        {selected.executionStatus.replaceAll("_", " ")}
                      </strong>
                    </dd>
                    <dt>Request binding</dt>
                    <dd>
                      <code>{selected.requestHash}</code>
                    </dd>
                  </dl>
                  <h3>Target and arguments</h3>
                  <pre>
                    {JSON.stringify(
                      {
                        target: selected.action.target,
                        arguments: selected.action.arguments,
                      },
                      null,
                      2,
                    )}
                  </pre>
                  <div className="approval-toolbar">
                    {canReview(session, selected) && (
                      <>
                        <button
                          className="approval-primary"
                          disabled={busy}
                          onClick={() =>
                            setConfirmation({
                              approval: selected,
                              decision: "approve",
                            })
                          }
                        >
                          Approve exact action
                        </button>
                        <button
                          className="approval-danger"
                          disabled={busy}
                          onClick={() =>
                            setConfirmation({
                              approval: selected,
                              decision: "deny",
                            })
                          }
                        >
                          Deny
                        </button>
                      </>
                    )}
                    {canExecute(session, selected) && (
                      <button
                        className="approval-primary"
                        disabled={busy}
                        onClick={() =>
                          setConfirmation({
                            approval: selected,
                            decision: "execute",
                          })
                        }
                      >
                        Execute approved simulation
                      </button>
                    )}
                  </div>
                  {selected.requesterId === session.principalId &&
                    selected.status === "pending" && (
                      <p className="approval-muted">
                        A different enrolled human reviewer must decide this
                        request.
                      </p>
                    )}
                  {session.roles.includes("VIEWER") &&
                    !canReview(session, selected) &&
                    !canExecute(session, selected) && (
                      <p className="approval-muted">
                        Read-only access. No authorized mutation is available.
                      </p>
                    )}
                  {confirmation && (
                    <section
                      className="approval-confirm"
                      aria-label="Confirm exact action"
                    >
                      <h3>Confirm: {confirmation.decision}</h3>
                      <p>
                        This applies only to the displayed request and hash.{" "}
                        {confirmation.decision === "approve"
                          ? "It does not execute the action."
                          : confirmation.decision === "execute"
                            ? "The server rechecks policy and atomically claims one simulator execution."
                            : "The action will not execute."}
                      </p>
                      <code>{confirmation.approval.requestHash}</code>
                      <div className="approval-toolbar">
                        <button
                          className="approval-primary"
                          disabled={busy}
                          onClick={() =>
                            void perform(
                              () =>
                                decideApproval(
                                  session,
                                  confirmation.approval,
                                  confirmation.decision,
                                ),
                              `${confirmation.decision === "execute" ? "Simulator execution completed" : "Review decision recorded"}. Refresh shows durable server state.`,
                            )
                          }
                        >
                          {busy
                            ? "Submitting…"
                            : `Confirm ${confirmation.decision}`}
                        </button>
                        <button
                          disabled={busy}
                          onClick={() => setConfirmation(null)}
                        >
                          Cancel
                        </button>
                      </div>
                    </section>
                  )}
                </>
              ) : (
                <p>Select a request to inspect its exact action.</p>
              )}
            </section>
          </div>
        </>
      )}
      <footer className="approval-footer">
        Local simulator foundation · no real infrastructure credentials ·
        authority is enforced by Java, not these buttons
      </footer>
    </main>
  );
}

function message(failure: unknown) {
  return failure instanceof ApprovalApiError
    ? failure.message
    : "The local approval service is unavailable. No operation was automatically retried.";
}
