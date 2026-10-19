import { ShieldCheck } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import {
  ApprovalApiError,
  canConfirmApproval,
  canExecute,
  canRequest,
  canReview,
  decideApproval,
  isExpired,
  loadApprovalPage,
  loadApprovalSession,
  signOut,
  submitApproval,
  type Approval,
  type ApprovalDecision,
  type ApprovalQuery,
  type ApprovalSession,
  type ApprovalStatusFilter,
} from "./approval-api.js";
import {
  MAX_ACCUMULATED_ROWS,
  authorityContext,
  historyAuthorityReady,
  initialHistoryState,
  pageFailure,
  pageSuccess,
  resetHistoryFilters,
  sessionLoggedOut,
  startLoadOlder,
  startRefresh,
  startSearch,
  type HistoryFilters,
  type HistoryState,
} from "./approval-history-state.js";
import "./approval-history.css";

type Confirmation = {
  approval: Approval;
  decision: ApprovalDecision;
};

export function ApprovalInbox() {
  const [session, setSession] = useState<ApprovalSession | null>(null);
  const [state, setState] = useState<HistoryState>(initialHistoryState);
  const [busy, setBusy] = useState(false);
  const submitting = useRef(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [confirmation, setConfirmation] = useState<Confirmation | null>(null);
  const [port, setPort] = useState("443");
  const [cidr, setCidr] = useState("192.0.2.0/24");
  const [observedNow, setObservedNow] = useState(Date.now);

  // Refs are updated synchronously with transitions, before React renders.
  const stateRef = useRef(state);
  const sessionRef = useRef(session);
  const generationRef = useRef(0);
  const abortControllerRef = useRef<AbortController | null>(null);
  const selected = state.items.find(
    (item) => item.approvalRequestId === state.selectedId,
  );
  const authorityReady = historyAuthorityReady(state, session);
  const hasLiveApproval = Boolean(
    session &&
    state.items.some(
      (item) =>
        ["pending", "approved"].includes(item.status) &&
        !isExpired(item, observedNow),
    ),
  );
  const confirmationReady = Boolean(
    confirmation &&
    canConfirmApproval(
      session,
      selected,
      confirmation.approval,
      confirmation.decision,
      observedNow,
    ),
  );

  function commit(next: HistoryState) {
    stateRef.current = next;
    setState(next);
  }

  function rememberSession(next: ApprovalSession | null) {
    sessionRef.current = next;
    setSession(next);
  }

  function invalidate(status?: number, error?: string) {
    abortControllerRef.current?.abort();
    const cleared = sessionLoggedOut({
      ...stateRef.current,
      generation: generationRef.current,
    });
    generationRef.current = cleared.generation;
    commit(
      status
        ? pageFailure(
            cleared,
            cleared.generation,
            status,
            error ?? message(new ApprovalApiError(status)),
          )
        : cleared,
    );
    rememberSession(null);
    setConfirmation(null);
  }

  function executeQuery(
    query: ApprovalQuery,
    generation: number,
    isOlder: boolean,
  ) {
    abortControllerRef.current?.abort();
    const controller = new AbortController();
    abortControllerRef.current = controller;
    setConfirmation(null);
    const previous = sessionRef.current;
    void Promise.allSettled([
      loadApprovalSession(controller.signal),
      loadApprovalPage(query, controller.signal),
    ]).then(([identity, result]) => {
      if (controller.signal.aborted || generation !== generationRef.current)
        return;
      if (identity.status === "rejected") {
        invalidate(
          identity.reason instanceof ApprovalApiError
            ? identity.reason.status
            : 503,
        );
        return;
      }
      const nextSession = identity.value;
      if (Date.parse(nextSession.expiresAt) <= Date.now()) {
        invalidate(401);
        return;
      }
      rememberSession(nextSession);
      if (
        isOlder &&
        previous &&
        authorityContext(previous) !== authorityContext(nextSession)
      ) {
        commit(
          pageFailure(
            stateRef.current,
            generation,
            409,
            "Current authority changed. Refresh to start a new traversal.",
          ),
        );
        return;
      }
      if (result.status === "rejected") {
        const status =
          result.reason instanceof ApprovalApiError
            ? result.reason.status
            : 503;
        if (status === 401 || status === 403) invalidate(status);
        else
          commit(
            pageFailure(
              stateRef.current,
              generation,
              status,
              message(result.reason),
            ),
          );
        return;
      }
      commit(pageSuccess(stateRef.current, generation, result.value, isOlder));
    });
  }

  function begin(
    transition: {
      nextState: HistoryState;
      query: ApprovalQuery | null;
      generation: number;
    },
    older = false,
  ) {
    if (!transition.query) return;
    generationRef.current = transition.generation;
    commit(transition.nextState);
    executeQuery(transition.query, transition.generation, older);
  }

  useEffect(() => {
    begin(startRefresh(stateRef.current));
    return () => {
      abortControllerRef.current?.abort();
      generationRef.current++;
      stateRef.current = {
        ...stateRef.current,
        generation: generationRef.current,
      };
    };
  }, []);

  useEffect(() => {
    if (!session) return;
    const timer = window.setTimeout(
      () => invalidate(401),
      Math.max(0, Date.parse(session.expiresAt) - Date.now()),
    );
    return () => window.clearTimeout(timer);
  }, [session]);

  useEffect(() => {
    if (!hasLiveApproval) return;
    // One local clock for all loaded rows, not one timer per row or a network
    // refresh. Retain observed expiry if the wall clock subsequently moves back.
    const sample = () =>
      setObservedNow((previous) => Math.max(previous, Date.now()));
    const visible = () => {
      if (document.visibilityState === "visible") sample();
    };
    sample();
    const timer = window.setInterval(sample, 1_000);
    window.addEventListener("focus", sample);
    document.addEventListener("visibilitychange", visible);
    return () => {
      window.clearInterval(timer);
      window.removeEventListener("focus", sample);
      document.removeEventListener("visibilitychange", visible);
    };
  }, [hasLiveApproval]);

  function handleConfirm() {
    if (submitting.current) return;
    const currentSession = sessionRef.current;
    const current = stateRef.current.items.find(
      (item) => item.approvalRequestId === stateRef.current.selectedId,
    );
    if (
      !confirmation ||
      !historyAuthorityReady(stateRef.current, currentSession) ||
      !canConfirmApproval(
        currentSession,
        current,
        confirmation.approval,
        confirmation.decision,
        Math.max(observedNow, Date.now()),
      )
    ) {
      setConfirmation(null);
      setNotice(
        "This confirmation is no longer available. Refresh the request before continuing; nothing was sent or retried.",
      );
      return;
    }
    // Recheck immediately before POST, including the interval between timer
    // ticks. The server still independently verifies identity, binding and time.
    if (!currentSession || !current) return;
    void perform(
      () => decideApproval(currentSession, current, confirmation.decision),
      `${confirmation.decision === "execute" ? "Simulator execution completed" : "Review decision recorded"}. Refresh shows durable server state.`,
    );
  }

  function handleApplyFilters(event: React.FormEvent) {
    event.preventDefault();
    if (
      submitting.current ||
      stateRef.current.loading ||
      stateRef.current.loadingOlder
    )
      return;
    setNotice(null);
    begin(startSearch(stateRef.current, stateRef.current.draftFilters));
  }

  function handleResetFilters() {
    if (
      submitting.current ||
      stateRef.current.loading ||
      stateRef.current.loadingOlder
    )
      return;
    setNotice(null);
    begin(resetHistoryFilters(stateRef.current));
  }

  function handleRefresh() {
    if (
      submitting.current ||
      stateRef.current.loading ||
      stateRef.current.loadingOlder
    )
      return;
    setNotice(null);
    begin(startRefresh(stateRef.current));
  }

  function handleLoadOlder() {
    if (submitting.current || stateRef.current.error) return;
    begin(startLoadOlder(stateRef.current), true);
  }

  function updateDraft(change: Partial<HistoryFilters>) {
    setConfirmation(null);
    commit({
      ...stateRef.current,
      draftFilters: { ...stateRef.current.draftFilters, ...change },
    });
  }

  async function perform(
    operation: () => Promise<unknown>,
    successNotice: string,
  ) {
    if (
      submitting.current ||
      !historyAuthorityReady(stateRef.current, sessionRef.current)
    )
      return;
    submitting.current = true;
    const generation = generationRef.current;
    setBusy(true);
    setNotice(null);
    setConfirmation(null);
    try {
      await operation();
      if (generation !== generationRef.current) return;
      setNotice(successNotice);
      begin(startRefresh(stateRef.current));
    } catch (failure: unknown) {
      if (generation !== generationRef.current) return;
      const status = failure instanceof ApprovalApiError ? failure.status : 503;
      invalidate(status, message(failure));
    } finally {
      submitting.current = false;
      setBusy(false);
    }
  }

  async function handleSignOut() {
    if (submitting.current || !sessionRef.current) return;
    const signingOut = sessionRef.current;
    submitting.current = true;
    setBusy(true);
    invalidate();
    try {
      await signOut(signingOut);
      setNotice("Signed out.");
    } catch {
      setNotice(
        "Sign-out could not be confirmed. Local authority was cleared; no operation was retried.",
      );
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
              disabled={busy || state.loading || state.loadingOlder}
              onClick={handleRefresh}
            >
              Refresh
            </button>
            <button disabled={busy} onClick={() => void handleSignOut()}>
              Sign out
            </button>
          </div>
        )}
      </div>

      {state.error && (
        <div className="approval-error-banner" role="alert">
          <p className="approval-error">{state.error}</p>
          {state.errorStatus !== 401 && (
            <button
              type="button"
              disabled={busy || state.loading}
              onClick={handleResetFilters}
            >
              Restart search
            </button>
          )}
        </div>
      )}

      {notice && (
        <p className="approval-notice" role="status">
          {notice}
        </p>
      )}

      {state.loading ? (
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
              <button
                className="approval-primary"
                disabled={busy || !authorityReady}
              >
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
                  {state.items.length} loaded
                  {state.endOfResults ? " · end of observed results" : ""}
                </span>
              </h2>
              <p className="approval-muted">
                {session.roles.length === 1 && session.roles[0] === "REQUESTER"
                  ? "Only your requests are visible."
                  : "Workspace-scoped requests."}
              </p>

              {/* Accessible Filter Controls */}
              <form
                className="approval-filter-form"
                aria-label="Filter approvals"
                onSubmit={handleApplyFilters}
              >
                <div className="approval-filter-fields">
                  <div className="approval-filter-field">
                    <label htmlFor="filter-status">Status</label>
                    <select
                      id="filter-status"
                      value={state.draftFilters.status}
                      onChange={(e) =>
                        updateDraft({
                          status: e.target.value as "" | ApprovalStatusFilter,
                        })
                      }
                    >
                      <option value="">All statuses</option>
                      <option value="pending">Pending</option>
                      <option value="approved">Approved</option>
                      <option value="denied">Denied</option>
                      <option value="expired">Expired</option>
                      <option value="consumed">Consumed</option>
                    </select>
                  </div>

                  <div className="approval-filter-field">
                    <label htmlFor="filter-tool">Exact tool name</label>
                    <input
                      id="filter-tool"
                      type="text"
                      maxLength={256}
                      placeholder="e.g. aws.ec2.update_security_group"
                      value={state.draftFilters.toolName}
                      onChange={(e) =>
                        updateDraft({ toolName: e.target.value })
                      }
                    />
                  </div>

                  <div className="approval-filter-field">
                    <label htmlFor="filter-request-id">Request ID</label>
                    <input
                      id="filter-request-id"
                      type="text"
                      maxLength={256}
                      placeholder="Exact request ID"
                      value={state.draftFilters.approvalRequestId}
                      onChange={(e) =>
                        updateDraft({ approvalRequestId: e.target.value })
                      }
                    />
                  </div>
                </div>

                <div className="approval-filter-actions">
                  <button
                    type="submit"
                    className="approval-primary"
                    disabled={busy || state.loading || state.loadingOlder}
                  >
                    Apply filters
                  </button>
                  <button
                    type="button"
                    disabled={busy || state.loading || state.loadingOlder}
                    onClick={handleResetFilters}
                  >
                    Reset
                  </button>
                </div>
              </form>

              {state.items.length === 0 && !state.loading && (
                <p>
                  No approval requests in your visible scope matching filters.
                </p>
              )}

              {state.items.map((item) => {
                const expired = isExpired(item, observedNow);
                const displayStatus =
                  expired && item.status === "pending"
                    ? "pending (expired)"
                    : expired && item.status === "approved"
                      ? "approved (expired)"
                      : item.status;
                const statusClass = `approval-status ${item.status}${expired ? " expired" : ""}`;

                return (
                  <button
                    className={`approval-row${state.selectedId === item.approvalRequestId ? " selected" : ""}`}
                    aria-pressed={state.selectedId === item.approvalRequestId}
                    disabled={busy}
                    key={item.approvalRequestId}
                    onClick={() => {
                      commit({
                        ...stateRef.current,
                        selectedId: item.approvalRequestId,
                      });
                      setConfirmation(null);
                    }}
                  >
                    <span>
                      <strong>{item.action.toolName}</strong>
                      <small>
                        {item.approvalRequestId.slice(0, 8)} · Created{" "}
                        {item.createdAt
                          ? new Date(item.createdAt).toLocaleTimeString()
                          : "unknown"}
                      </small>
                    </span>
                    <span className={statusClass}>{displayStatus}</span>
                  </button>
                );
              })}

              {/* Pagination Controls */}
              <div className="approval-pagination-bar">
                {state.loadingOlder && (
                  <p role="status">Loading older requests…</p>
                )}
                {!state.loadingOlder && state.atCapacity && (
                  <div className="approval-cap-notice" role="status">
                    <p>Maximum {MAX_ACCUMULATED_ROWS} requests loaded.</p>
                    <p className="approval-muted">
                      Refine your search filters or reset to view other
                      requests.
                    </p>
                    <button
                      type="button"
                      disabled={busy || state.loading}
                      onClick={handleResetFilters}
                    >
                      Reset search
                    </button>
                  </div>
                )}
                {!state.loadingOlder &&
                  !state.atCapacity &&
                  state.nextCursor && (
                    <button
                      type="button"
                      className="approval-load-older"
                      disabled={busy || state.loading || state.loadingOlder}
                      onClick={handleLoadOlder}
                    >
                      Load older requests
                    </button>
                  )}
                {!state.loadingOlder &&
                  !state.atCapacity &&
                  state.endOfResults &&
                  state.items.length > 0 && (
                    <p
                      className="approval-muted approval-end-of-results"
                      role="status"
                    >
                      End of results.
                    </p>
                  )}
              </div>
            </section>

            <section
              className="approval-card approval-detail"
              aria-label="Exact action detail"
            >
              {selected ? (
                <>
                  <div className="approval-detail-title">
                    <h2>Exact action</h2>
                    <span
                      className={`approval-status ${selected.status}${isExpired(selected, observedNow) ? " expired" : ""}`}
                    >
                      {selected.status}
                      {isExpired(selected, observedNow) &&
                      ["pending", "approved"].includes(selected.status)
                        ? " (expired)"
                        : ""}
                    </span>
                  </div>
                  <h3>{selected.action.toolName}</h3>
                  <p className="approval-muted">
                    Safe-rendered data, not instructions. All downstream effects
                    are simulated.
                  </p>
                  <dl>
                    <dt>Created</dt>
                    <dd>
                      {selected.createdAt
                        ? new Date(selected.createdAt).toLocaleString()
                        : "Unknown"}
                    </dd>
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
                      {isExpired(selected, observedNow) ? "(expired)" : ""}
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
                  {["pending", "approved"].includes(selected.status) &&
                    isExpired(selected, observedNow) && (
                      <p className="approval-muted" role="status">
                        Approval lifetime has elapsed. The stored status is
                        unchanged; no review or execution is available. Refresh
                        to read server state. Local time is advisory; Java
                        remains authoritative.
                      </p>
                    )}
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
                    {canReview(session, selected, observedNow) && (
                      <>
                        <button
                          className="approval-primary"
                          disabled={busy || !authorityReady}
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
                          disabled={busy || !authorityReady}
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
                    {canExecute(session, selected, observedNow) && (
                      <button
                        className="approval-primary"
                        disabled={busy || !authorityReady}
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
                    !canReview(session, selected, observedNow) &&
                    !canExecute(session, selected, observedNow) && (
                      <p className="approval-muted">
                        Read-only access. No authorized mutation is available.
                      </p>
                    )}
                  {confirmation && authorityReady && !confirmationReady && (
                    <p role="status">
                      This confirmation is no longer available. Refresh the
                      request before continuing; nothing was sent or retried.
                    </p>
                  )}
                  {confirmation && authorityReady && confirmationReady && (
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
                          disabled={busy || !authorityReady}
                          onClick={handleConfirm}
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
