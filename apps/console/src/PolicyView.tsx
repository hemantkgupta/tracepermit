import {
  CheckCircle2,
  FileKey2,
  Gauge,
  LockKeyhole,
  Shield,
  SlidersHorizontal,
} from "lucide-react";
import { useMemo, useState } from "react";
import { loadPolicyVersion } from "./api.js";
import { decisionLabel, formatTimeWithZone } from "./formatters.js";
import type { PolicyVersionSummary } from "./types.js";
import { useReadDetail } from "./use-read-detail.js";

export function PolicyView({
  items,
  search,
}: {
  items: PolicyVersionSummary[];
  search: string;
}) {
  const [selectedKey, setSelectedKey] = useState<string | null>(null);
  const visibleItems = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return items;
    return items.filter((item) =>
      `${item.policyId} ${item.name} ${item.status}`
        .toLowerCase()
        .includes(query),
    );
  }, [items, search]);
  const keys = visibleItems.map((item) => `${item.policyId}:${item.version}`);
  const activeKey = keys.includes(selectedKey ?? "")
    ? selectedKey
    : (keys[0] ?? null);
  const detail = useReadDetail(activeKey, loadPolicyVersion);
  const evaluationCount = items.reduce(
    (total, item) => total + item.evaluationCount,
    0,
  );

  return (
    <>
      <main className="workspace query-workspace">
        <section className="query-hero">
          <div>
            <span className="eyebrow">Policy provenance</span>
            <h1>Know which policy made the decision</h1>
            <p>
              This catalog identifies active policy and runtime versions. It
              does not activate, edit, stage, or roll back policy.
            </p>
          </div>
          <span className="simulation-badge">
            <span /> Simulated data
          </span>
        </section>

        <section className="query-metrics" aria-label="Policy metrics">
          <article>
            <CheckCircle2 size={19} />
            <span>Active versions</span>
            <strong>
              {items.filter((item) => item.status === "active").length}
            </strong>
          </article>
          <article>
            <Gauge size={19} />
            <span>Evaluations</span>
            <strong>{evaluationCount}</strong>
          </article>
          <article>
            <FileKey2 size={19} />
            <span>Lifecycle mode</span>
            <strong className="metric-word">Read only</strong>
          </article>
        </section>

        <section className="query-card">
          <div className="query-card-heading">
            <div>
              <h2>Policy versions</h2>
              <p>
                Bounded metadata and rule outcomes, never writable policy
                documents.
              </p>
            </div>
            <span>{visibleItems.length} shown</span>
          </div>
          <div className="query-list" role="list">
            {visibleItems.length === 0 ? (
              <QueryEmpty message="No policy version matches this search." />
            ) : (
              visibleItems.map((item) => {
                const key = `${item.policyId}:${item.version}`;
                return (
                  <button
                    aria-pressed={key === activeKey}
                    className={`query-row${key === activeKey ? " selected" : ""}`}
                    key={key}
                    onClick={() => setSelectedKey(key)}
                    type="button"
                  >
                    <span className="query-status policy">
                      <Shield size={18} />
                    </span>
                    <span className="query-row-copy">
                      <strong>{item.name}</strong>
                      <small>
                        {item.policyId} · version {item.version}
                      </small>
                    </span>
                    <span className="query-row-meta">
                      <strong className="status-text">{item.status}</strong>
                      <small>{item.evaluationCount} evaluations</small>
                    </span>
                  </button>
                );
              })
            )}
          </div>
        </section>
      </main>

      <aside
        className="details-panel query-details"
        aria-label="Policy version detail"
      >
        <div className="query-detail-header">
          <span className="detail-icon policy">
            <Shield size={21} />
          </span>
          <div>
            <span className="eyebrow">Selected policy</span>
            <h2>{detail.data?.name ?? "Policy version"}</h2>
          </div>
        </div>
        <div className="detail-scroll">
          {detail.loading ? (
            <QueryEmpty message="Loading policy version…" />
          ) : detail.error ? (
            <QueryEmpty message={detail.error} />
          ) : detail.data ? (
            <>
              <section className="detail-section">
                <h3>
                  <FileKey2 size={17} /> Version identity
                </h3>
                <div className="policy-version-banner">
                  <strong>
                    {detail.data.policyId} · v{detail.data.version}
                  </strong>
                  <span>{detail.data.status}</span>
                </div>
                <p className="detail-copy">{detail.data.description}</p>
                <Definition
                  label="Activated"
                  value={formatTimeWithZone(detail.data.activatedAt)}
                />
                <Definition
                  label="Content hash"
                  value={shortHash(detail.data.contentHash)}
                />
                <Definition
                  label="Default"
                  value={decisionLabel(detail.data.defaultOutcome)}
                />
              </section>
              <section className="detail-section">
                <h3>
                  <SlidersHorizontal size={17} /> Runtime versions
                </h3>
                <Definition
                  label="Schema"
                  value={detail.data.runtimeVersions.canonicalSchema}
                />
                <Definition
                  label="Classifier"
                  value={detail.data.runtimeVersions.classifier}
                />
                <Definition
                  label="Adapter"
                  value={detail.data.runtimeVersions.adapter}
                />
                <Definition
                  label="Execution key"
                  value={detail.data.runtimeVersions.executionKey}
                />
              </section>
              <section className="detail-section">
                <h3>
                  <Shield size={17} /> Ordered rules
                </h3>
                <div className="rule-list">
                  {detail.data.rules.map((rule) => (
                    <article key={rule.ruleId}>
                      <span>{rule.priority}</span>
                      <div>
                        <strong>{rule.ruleId}</strong>
                        <p>{rule.description}</p>
                        <small>
                          {decisionLabel(rule.outcome)} · {rule.reasonCode}
                        </small>
                      </div>
                    </article>
                  ))}
                </div>
              </section>
            </>
          ) : (
            <QueryEmpty message="Select a policy version." />
          )}
        </div>
        <div className="query-readonly">
          <LockKeyhole size={15} /> No policy mutation or rollback controls
        </div>
      </aside>
    </>
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

function QueryEmpty({ message }: { message: string }) {
  return <div className="query-empty">{message}</div>;
}

function shortHash(hash: string): string {
  return hash.length > 24 ? `${hash.slice(0, 18)}…${hash.slice(-6)}` : hash;
}
