import {
  Activity,
  BarChart3,
  Bell,
  Blocks,
  CalendarDays,
  Check,
  ChevronDown,
  ChevronRight,
  CircleHelp,
  Clock3,
  Cloud,
  Database,
  Fingerprint,
  GitBranch,
  Grid2X2,
  LockKeyhole,
  MoreHorizontal,
  Search,
  Settings,
  Shield,
  ShieldCheck,
  SlidersHorizontal,
  UserRound,
  UsersRound,
  X,
  Zap,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { loadConsoleData } from "./api.js";
import { EvidenceView } from "./EvidenceView.js";
import {
  LOCAL_TIME_ZONE_LABEL,
  decisionLabel,
  formatTime,
  formatTimeWithZone,
  initials,
  liveLatencyMetric,
  matchesLocalFilters,
  severityLabel,
} from "./formatters.js";
import { LineageView } from "./LineageView.js";
import { PolicyView } from "./PolicyView.js";
import type {
  ActionFeedEntry,
  ActionFeedOverview,
  EvidenceIntegritySummary,
  LineageSummary,
  PolicyVersionSummary,
} from "./types.js";

const emptyOverview: ActionFeedOverview = {
  schemaVersion: "1.0",
  simulated: true,
  total: 0,
  allowed: 0,
  blocked: 0,
  sanitized: 0,
  approvalRequired: 0,
  p95LatencyMs: 0,
  latencySampleSize: 0,
  latencySampleScope: "current_run",
};

type ViewKey = "actions" | "evidence" | "lineage" | "policies";
const LIVE_REFRESH_MS = 2_000;

const navigation: Array<{
  label: string;
  icon: typeof Activity;
  view?: ViewKey;
}> = [
  { label: "Overview", icon: Grid2X2 },
  { label: "Action feed", icon: Activity, view: "actions" },
  { label: "Evidence", icon: Fingerprint, view: "evidence" },
  { label: "Lineage", icon: GitBranch, view: "lineage" },
  { label: "Policies", icon: Shield, view: "policies" },
  { label: "Approvals", icon: UsersRound },
  { label: "Integrations", icon: Blocks },
];

export function App() {
  const [overview, setOverview] = useState(emptyOverview);
  const [actions, setActions] = useState<ActionFeedEntry[]>([]);
  const [evidence, setEvidence] = useState<EvidenceIntegritySummary[]>([]);
  const [policies, setPolicies] = useState<PolicyVersionSummary[]>([]);
  const [lineages, setLineages] = useState<LineageSummary[]>([]);
  const [activeView, setActiveView] = useState<ViewKey>("actions");
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const [search, setSearch] = useState("");
  const [decision, setDecision] = useState("");
  const [severity, setSeverity] = useState("");
  const [origin, setOrigin] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);
  const latencyMetric = liveLatencyMetric(
    overview.p95LatencyMs,
    overview.latencySampleSize,
  );

  useEffect(() => {
    const controller = new AbortController();
    setLoading(true);
    const refresh = async () => {
      try {
        const {
          overview: nextOverview,
          page,
          evidence: nextEvidence,
          policies: nextPolicies,
          lineages: nextLineages,
        } = await loadConsoleData(controller.signal);
        setOverview(nextOverview);
        setActions(page.items);
        setEvidence(nextEvidence.items);
        setPolicies(nextPolicies.items);
        setLineages(nextLineages.items);
        setSelectedId((current) => {
          if (current && page.items.some((entry) => entry.eventId === current))
            return current;
          return (
            page.items.find(
              (entry) =>
                entry.risk.severity === "critical" &&
                entry.decision.outcome === "block",
            )?.eventId ??
            page.items[0]?.eventId ??
            null
          );
        });
        setError(null);
      } catch (failure: unknown) {
        if (failure instanceof DOMException && failure.name === "AbortError")
          return;
        setError(
          "The local Java read API is unavailable. Start it with pnpm dev:backend.",
        );
      } finally {
        setLoading(false);
      }
    };
    void refresh();
    const interval = window.setInterval(() => void refresh(), LIVE_REFRESH_MS);
    return () => {
      window.clearInterval(interval);
      controller.abort();
    };
  }, [reloadKey]);

  const origins = useMemo(
    () => [...new Set(actions.map((entry) => entry.trigger.origin))].sort(),
    [actions],
  );
  const visibleActions = useMemo(
    () =>
      actions.filter((entry) =>
        matchesLocalFilters(entry, search, decision, severity, origin),
      ),
    [actions, decision, origin, search, severity],
  );
  const selected =
    actions.find((entry) => entry.eventId === selectedId) ??
    visibleActions[0] ??
    null;

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-lockup">
          <div className="brand-mark" aria-hidden="true">
            <ShieldCheck size={22} strokeWidth={2.2} />
          </div>
          <div>
            <div className="brand-name">TracePermit</div>
            <div className="brand-subtitle">Agentic Runtime Control Plane</div>
          </div>
        </div>

        <nav className="primary-nav" aria-label="Primary navigation">
          {navigation.map(({ label, icon: Icon, view }) => (
            <button
              aria-current={view === activeView ? "page" : undefined}
              className={`nav-item${view === activeView ? " active" : ""}`}
              disabled={!view}
              key={label}
              onClick={() => {
                if (view) {
                  setActiveView(view);
                  setSearch("");
                }
              }}
              type="button"
            >
              <Icon size={20} strokeWidth={1.8} />
              <span>{label}</span>
            </button>
          ))}
        </nav>

        <div className="sidebar-footer">
          <button className="organization-switcher" type="button">
            <Database size={19} />
            <span>
              <small>Organization</small>
              TracePermit Demo
            </span>
            <ChevronRight size={17} />
          </button>
          <button className="nav-item" type="button">
            <CircleHelp size={20} />
            <span>Help & docs</span>
          </button>
          <button className="nav-item" type="button">
            <Settings size={20} />
            <span>Settings</span>
          </button>
        </div>
      </aside>

      <header className="topbar">
        <label className="global-search">
          <Search size={19} aria-hidden="true" />
          <input
            aria-label={`Search ${viewLabel(activeView)}`}
            onChange={(event) => setSearch(event.target.value)}
            placeholder={`Search ${viewLabel(activeView)}...`}
            value={search}
          />
        </label>
        <label className="mobile-view-select">
          <span>View</span>
          <select
            aria-label="Select console view"
            onChange={(event) => {
              setActiveView(event.target.value as ViewKey);
              setSearch("");
            }}
            value={activeView}
          >
            <option value="actions">Action feed</option>
            <option value="evidence">Evidence integrity</option>
            <option value="lineage">Lineage</option>
            <option value="policies">Policy versions</option>
          </select>
        </label>
        <div className="topbar-actions">
          <button className="time-range" type="button">
            <CalendarDays size={17} />
            Last 24 hours
            <ChevronDown size={15} />
          </button>
          <button
            className="icon-button notification-button"
            aria-label="Notifications"
            type="button"
          >
            <Bell size={19} />
            <span />
          </button>
          <div className="user-avatar">JD</div>
          <div className="user-copy">
            <strong>Jordan Diaz</strong>
            <span>Security · Demo</span>
          </div>
          <ChevronDown size={16} />
        </div>
      </header>

      {activeView === "evidence" && !loading && !error ? (
        <EvidenceView items={evidence} search={search} />
      ) : activeView === "lineage" && !loading && !error ? (
        <LineageView items={lineages} search={search} />
      ) : activeView === "policies" && !loading && !error ? (
        <PolicyView items={policies} search={search} />
      ) : activeView !== "actions" ? (
        <ReadViewState
          error={error}
          loading={loading}
          onRetry={() => setReloadKey((value) => value + 1)}
        />
      ) : (
        <>
          <main className="workspace">
            <section className="metric-grid" aria-label="Action feed metrics">
              <MetricCard
                icon={<Check size={22} />}
                label="Allowed"
                tone="success"
                value={overview.allowed}
                trend="Policy-authorized"
              />
              <MetricCard
                icon={<X size={22} />}
                label="Blocked"
                tone="danger"
                value={overview.blocked}
                trend="Stopped pre-execution"
              />
              <MetricCard
                icon={<Clock3 size={21} />}
                label="Approval"
                tone="warning"
                value={overview.approvalRequired}
                trend="Workflow unavailable"
              />
              <MetricCard
                icon={<BarChart3 size={21} />}
                label="P95 latency"
                tone="neutral"
                value={latencyMetric.value}
                trend={latencyMetric.trend}
              />
            </section>

            <section className="feed-card">
              <div className="feed-heading">
                <div>
                  <div className="heading-row">
                    <h1>Action feed</h1>
                    <span className="simulation-badge">
                      <span /> Live simulated data
                    </span>
                  </div>
                  <p>AI-agent tool calls evaluated before execution.</p>
                </div>
                <button
                  className="icon-button"
                  aria-label="Action feed options"
                  type="button"
                >
                  <MoreHorizontal size={20} />
                </button>
              </div>

              <div className="filter-bar">
                <FilterSelect
                  label="All decisions"
                  onChange={setDecision}
                  value={decision}
                >
                  <option value="allow">Allowed</option>
                  <option value="block">Blocked</option>
                  <option value="require_approval">Require approval</option>
                </FilterSelect>
                <FilterSelect
                  label="All risk tiers"
                  onChange={setSeverity}
                  value={severity}
                >
                  <option value="low">Low</option>
                  <option value="medium">Medium</option>
                  <option value="high">High</option>
                  <option value="critical">Critical</option>
                </FilterSelect>
                <FilterSelect
                  label="All origins"
                  onChange={setOrigin}
                  value={origin}
                >
                  {origins.map((item) => (
                    <option key={item} value={item}>
                      {originLabel(item)}
                    </option>
                  ))}
                </FilterSelect>
                <label className="feed-search">
                  <Search size={18} />
                  <input
                    aria-label="Search action feed"
                    onChange={(event) => setSearch(event.target.value)}
                    placeholder="Search actions..."
                    value={search}
                  />
                </label>
              </div>

              <div className="table-wrap">
                {loading ? (
                  <FeedState
                    icon={<Activity size={24} />}
                    message="Loading simulated actions..."
                  />
                ) : error ? (
                  <FeedState
                    action={
                      <button
                        onClick={() => setReloadKey((value) => value + 1)}
                      >
                        Retry
                      </button>
                    }
                    icon={<Zap size={24} />}
                    message={error}
                  />
                ) : visibleActions.length === 0 ? (
                  <FeedState
                    icon={<Search size={24} />}
                    message="No actions match these filters."
                  />
                ) : (
                  <table>
                    <thead>
                      <tr>
                        <th>Time ({LOCAL_TIME_ZONE_LABEL})</th>
                        <th>Identity</th>
                        <th>Origin</th>
                        <th>Intent</th>
                        <th>Target tool</th>
                        <th>Risk tier</th>
                        <th>Decision</th>
                        <th>Latency</th>
                        <th aria-label="Open details" />
                      </tr>
                    </thead>
                    <tbody>
                      {visibleActions.map((entry, index) => (
                        <ActionRow
                          entry={entry}
                          key={entry.eventId}
                          onSelect={() => setSelectedId(entry.eventId)}
                          selected={entry.eventId === selected?.eventId}
                          toneIndex={index}
                        />
                      ))}
                    </tbody>
                  </table>
                )}
              </div>
            </section>
          </main>

          <aside className="details-panel" aria-label="Selected action detail">
            {selected ? (
              <ActionDetail entry={selected} />
            ) : (
              <FeedState icon={<Activity />} message="Select an action." />
            )}
          </aside>
        </>
      )}
    </div>
  );
}

function ReadViewState({
  error,
  loading,
  onRetry,
}: {
  error: string | null;
  loading: boolean;
  onRetry: () => void;
}) {
  return (
    <>
      <main className="workspace query-workspace state-workspace">
        <section className="query-card">
          <FeedState
            action={
              error ? <button onClick={onRetry}>Retry</button> : undefined
            }
            icon={loading ? <Activity size={24} /> : <Zap size={24} />}
            message={
              loading
                ? "Loading read-only query views…"
                : (error ?? "No data available.")
            }
          />
        </section>
      </main>
      <aside className="details-panel" aria-label="Query view status" />
    </>
  );
}

function MetricCard({
  icon,
  label,
  tone,
  trend,
  value,
}: {
  icon: React.ReactNode;
  label: string;
  tone: string;
  trend: string;
  value: number | string;
}) {
  return (
    <article className="metric-card">
      <div className={`metric-icon ${tone}`}>{icon}</div>
      <div className="metric-content">
        <span>{label}</span>
        <strong>{value}</strong>
        <small>{trend}</small>
      </div>
    </article>
  );
}

function FilterSelect({
  children,
  label,
  onChange,
  value,
}: {
  children: React.ReactNode;
  label: string;
  onChange: (value: string) => void;
  value: string;
}) {
  return (
    <label className="filter-select">
      <select
        aria-label={label}
        onChange={(event) => onChange(event.target.value)}
        value={value}
      >
        <option value="">{label}</option>
        {children}
      </select>
      <ChevronDown size={14} />
    </label>
  );
}

function ActionRow({
  entry,
  onSelect,
  selected,
  toneIndex,
}: {
  entry: ActionFeedEntry;
  onSelect: () => void;
  selected: boolean;
  toneIndex: number;
}) {
  return (
    <tr
      aria-selected={selected}
      className={selected ? "selected" : ""}
      onClick={onSelect}
      onKeyDown={(event) => {
        if (event.key === "Enter" || event.key === " ") onSelect();
      }}
      tabIndex={0}
    >
      <td className="time-cell">
        <time dateTime={entry.observedAt}>{formatTime(entry.observedAt)}</time>
      </td>
      <td>
        <div className="identity-cell">
          <span className={`mini-avatar tone-${toneIndex % 5}`}>
            {initials(entry.identity.subject.displayName)}
          </span>
          <span>
            <strong>{entry.identity.subject.displayName}</strong>
            <small>
              {entry.identity.subject.type === "human" ? "Human" : "Service"}
            </small>
          </span>
        </div>
      </td>
      <td>
        <div className="origin-cell">
          <OriginIcon origin={entry.trigger.origin} />
          <span>{originLabel(entry.trigger.origin)}</span>
        </div>
      </td>
      <td className="intent-cell">{entry.intent}</td>
      <td>
        <div className="tool-cell">
          <SystemIcon system={entry.target.system} />
          <span>
            <strong>{entry.target.system.toUpperCase()}</strong>
            <small>
              {entry.target.toolName.replace(`${entry.target.system}.`, "")}
            </small>
          </span>
        </div>
      </td>
      <td>
        <span className={`severity-badge ${entry.risk.severity}`}>
          {severityLabel(entry.risk.severity)}
        </span>
      </td>
      <td>
        <span className={`decision-badge ${entry.decision.outcome}`}>
          {decisionLabel(entry.decision.outcome)}
        </span>
      </td>
      <td className="latency-cell">{entry.latencyMs} ms</td>
      <td>
        <ChevronRight size={16} />
      </td>
    </tr>
  );
}

function ActionDetail({ entry }: { entry: ActionFeedEntry }) {
  return (
    <>
      <div className="detail-header">
        <div className="detail-title-row">
          <SystemIcon large system={entry.target.system} />
          <div>
            <h2>{entry.intent}</h2>
            <p>
              <time dateTime={entry.observedAt}>
                {formatTimeWithZone(entry.observedAt)}
              </time>{" "}
              · {entry.eventId.slice(0, 8)}
            </p>
          </div>
          <span className={`severity-badge ${entry.risk.severity}`}>
            {severityLabel(entry.risk.severity)} risk
          </span>
        </div>
      </div>

      <div className="detail-scroll">
        <DetailSection icon={<ShieldCheck size={17} />} title="Summary">
          <Definition label="Intent" value={entry.intent} />
          <Definition
            label="Target tool"
            value={`${entry.target.system.toUpperCase()} · ${entry.target.toolName}`}
          />
          <Definition
            label="Origin"
            value={originLabel(entry.trigger.origin)}
          />
          <Definition
            label="Identity"
            value={`${entry.identity.subject.displayName} · ${entry.identity.subject.id}`}
          />
          <Definition
            label="Semantic intent"
            value={
              entry.risk.semanticIntents.length === 0
                ? "No elevated semantic category"
                : entry.risk.semanticIntents
                    .map((value) => value.replaceAll("_", " "))
                    .join(" · ")
            }
          />
        </DetailSection>

        <DetailSection icon={<GitBranch size={17} />} title="Causal lineage">
          <div className="lineage-list">
            {entry.lineage.map((hop, index) => (
              <div className="lineage-hop" key={hop.hopId}>
                <div className="lineage-rail">
                  <span>
                    {index === 0 ? (
                      <UserRound size={16} />
                    ) : (
                      <Blocks size={16} />
                    )}
                  </span>
                  {index < entry.lineage.length - 1 ? <i /> : null}
                </div>
                <div>
                  <strong>
                    {hop.kind === "principal" ? "Principal" : hop.kind}
                  </strong>
                  <span>{hop.subjectId}</span>
                </div>
                <small>
                  {hop.assurance}
                  <br />
                  {hop.source}
                </small>
              </div>
            ))}
            <div className="lineage-hop">
              <div className="lineage-rail">
                <span>
                  <SystemIcon system={entry.target.system} />
                </span>
              </div>
              <div>
                <strong>{entry.target.system.toUpperCase()} simulator</strong>
                <span>{entry.target.toolName}</span>
              </div>
              <small>
                validated target
                <br />
                {entry.target.accountId}
              </small>
            </div>
          </div>
        </DetailSection>

        <DetailSection icon={<Shield size={17} />} title="Policy decision">
          <div className={`policy-callout ${entry.decision.outcome}`}>
            <div>
              <ShieldCheck size={18} />
              <strong>{decisionLabel(entry.decision.outcome)}</strong>
              <span>
                Policy: {entry.decision.policyId} v
                {entry.decision.policyVersion}
              </span>
            </div>
            <p>{reasonCopy(entry)}</p>
          </div>
          <div className="signal-list">
            {entry.risk.signals.map((signal) => (
              <span key={signal}>{signal.replaceAll("_", " ")}</span>
            ))}
          </div>
          {entry.sanitization ? (
            <div className="sanitization-callout">
              <strong>Sanitized, re-evaluated, then executed</strong>
              <span>
                {entry.sanitization.transformerId} ·{" "}
                {entry.sanitization.changes
                  .map((value) => value.replaceAll("_", " "))
                  .join(", ")}
              </span>
              <small>
                Effective decision:{" "}
                {decisionLabel(entry.sanitization.finalOutcome)} · event{" "}
                {entry.sanitization.effectiveEventId.slice(0, 8)}
              </small>
            </div>
          ) : null}
        </DetailSection>
      </div>

      <div className="detail-actions">
        <div className="readonly-notice">
          <LockKeyhole size={15} />
          Read-only console · approval is a separate authenticated workflow
        </div>
        <div>
          <button disabled type="button">
            <X size={17} /> Deny
          </button>
          <button className="approve-button" disabled type="button">
            <UserRound size={17} /> Approve and execute
          </button>
        </div>
      </div>
    </>
  );
}

function DetailSection({
  children,
  icon,
  title,
}: {
  children: React.ReactNode;
  icon: React.ReactNode;
  title: string;
}) {
  return (
    <section className="detail-section">
      <h3>
        {icon}
        {title}
      </h3>
      {children}
    </section>
  );
}

function Definition({ label, value }: { label: string; value: string }) {
  return (
    <div className="definition-row">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function SystemIcon({
  large = false,
  system,
}: {
  large?: boolean;
  system: string;
}) {
  return (
    <span className={`system-icon ${large ? "large" : ""} ${system}`}>
      {system === "jira" ? (
        <Database size={large ? 23 : 17} />
      ) : (
        <Cloud size={large ? 24 : 18} />
      )}
    </span>
  );
}

function OriginIcon({ origin }: { origin: string }) {
  if (origin.includes("scheduler"))
    return <CalendarDays size={18} className="origin-scheduler" />;
  if (origin.includes("api"))
    return <SlidersHorizontal size={18} className="origin-api" />;
  return <Zap size={18} className="origin-web" />;
}

function originLabel(origin: string): string {
  const labels: Record<string, string> = {
    "chatgpt-web": "ChatGPT Web",
    "claude-web": "Claude Web",
    api: "API",
    scheduler: "Scheduler",
  };
  return labels[origin] ?? origin;
}

function viewLabel(view: ViewKey): string {
  const labels: Record<ViewKey, string> = {
    actions: "actions, users, or tools",
    evidence: "evidence streams",
    lineage: "lineage traces",
    policies: "policy versions",
  };
  return labels[view];
}

function reasonCopy(entry: ActionFeedEntry): string {
  if (entry.decision.outcome === "allow") {
    return "This bounded simulated operation matched an explicit allow rule.";
  }
  if (entry.decision.outcome === "block") {
    return "The policy stopped this action before any target dispatch occurred.";
  }
  if (entry.decision.outcome === "sanitize") {
    return entry.sanitization
      ? "A reviewed transformer produced a new action that was re-evaluated before execution."
      : "Sanitization could not be resolved, so the action was not executed.";
  }
  return "No explicit allow matched. A request-bound human approval would be required.";
}

function FeedState({
  action,
  icon,
  message,
}: {
  action?: React.ReactNode;
  icon: React.ReactNode;
  message: string;
}) {
  return (
    <div className="feed-state">
      {icon}
      <p>{message}</p>
      {action}
    </div>
  );
}
