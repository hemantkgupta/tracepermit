import {
  Blocks,
  GitBranch,
  KeyRound,
  LockKeyhole,
  Network,
  UserRound,
} from "lucide-react";
import { useMemo, useState } from "react";
import { loadLineage } from "./api.js";
import { formatTimeWithZone } from "./formatters.js";
import type { LineageSummary } from "./types.js";
import { useReadDetail } from "./use-read-detail.js";

export function LineageView({
  items,
  search,
}: {
  items: LineageSummary[];
  search: string;
}) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const visibleItems = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return items;
    return items.filter((item) =>
      `${item.traceId} ${item.eventId} ${item.intent} ${item.rootPrincipalId}`
        .toLowerCase()
        .includes(query),
    );
  }, [items, search]);
  const activeId =
    visibleItems.find((item) => item.traceId === selectedId)?.traceId ??
    visibleItems[0]?.traceId ??
    null;
  const detail = useReadDetail(activeId, loadLineage);
  const delegationClaimCount = items.filter(
    (item) => item.delegationClaimCount > 0,
  ).length;
  const hopCount = items.reduce((total, item) => total + item.hopCount, 0);

  return (
    <>
      <main className="workspace query-workspace">
        <section className="query-hero">
          <div>
            <span className="eyebrow">Lineage queries</span>
            <h1>Trace causality without inventing authority</h1>
            <p>
              Causal hops explain how an action arose. Structurally validated
              delegation claims are labeled without implying cryptographic
              authority.
            </p>
          </div>
          <span className="simulation-badge">
            <span /> Simulated data
          </span>
        </section>

        <section className="query-metrics" aria-label="Lineage metrics">
          <article>
            <Network size={19} />
            <span>Traces</span>
            <strong>{items.length}</strong>
          </article>
          <article>
            <GitBranch size={19} />
            <span>Causal hops</span>
            <strong>{hopCount}</strong>
          </article>
          <article>
            <KeyRound size={19} />
            <span>Delegation claims</span>
            <strong>{delegationClaimCount}</strong>
          </article>
        </section>

        <section className="query-card">
          <div className="query-card-heading">
            <div>
              <h2>Action traces</h2>
              <p>
                Root principals, causal depth, and bounded authority labels.
              </p>
            </div>
            <span>{visibleItems.length} shown</span>
          </div>
          <div className="query-list" role="list">
            {visibleItems.length === 0 ? (
              <QueryEmpty message="No lineage matches this search." />
            ) : (
              visibleItems.map((item) => (
                <button
                  aria-pressed={item.traceId === activeId}
                  className={`query-row${item.traceId === activeId ? " selected" : ""}`}
                  key={item.traceId}
                  onClick={() => setSelectedId(item.traceId)}
                  type="button"
                >
                  <span className="query-status lineage">
                    <GitBranch size={18} />
                  </span>
                  <span className="query-row-copy">
                    <strong>{item.intent}</strong>
                    <small>{item.rootPrincipalId}</small>
                  </span>
                  <span className="query-row-meta">
                    <strong>{item.hopCount} hops</strong>
                    <small>{authorityLabel(item.authoritySummary)}</small>
                  </span>
                </button>
              ))
            )}
          </div>
        </section>
      </main>

      <aside
        className="details-panel query-details"
        aria-label="Lineage detail"
      >
        <div className="query-detail-header">
          <span className="detail-icon lineage">
            <GitBranch size={21} />
          </span>
          <div>
            <span className="eyebrow">Selected trace</span>
            <h2>{detail.data?.intent ?? "Lineage"}</h2>
          </div>
        </div>
        <div className="detail-scroll">
          {detail.loading ? (
            <QueryEmpty message="Loading lineage…" />
          ) : detail.error ? (
            <QueryEmpty message={detail.error} />
          ) : detail.data ? (
            <>
              <section className="detail-section">
                <h3>
                  <Network size={17} /> Trace summary
                </h3>
                <Definition label="Trace" value={detail.data.traceId} />
                <Definition
                  label="Observed"
                  value={formatTimeWithZone(detail.data.observedAt)}
                />
                <Definition label="Root" value={detail.data.rootPrincipalId} />
                <Definition
                  label="Target"
                  value={`${detail.data.targetSystem} · ${detail.data.targetTool}`}
                />
              </section>
              <section className="detail-section">
                <h3>
                  <GitBranch size={17} /> Causal path
                </h3>
                <div className="lineage-list">
                  {detail.data.hops.map((hop, index, hops) => (
                    <div className="lineage-hop" key={hop.hopId}>
                      <div className="lineage-rail">
                        <span>
                          {index === 0 ? (
                            <UserRound size={16} />
                          ) : (
                            <Blocks size={16} />
                          )}
                        </span>
                        {index < hops.length - 1 ? <i /> : null}
                      </div>
                      <div>
                        <strong>{hop.kind}</strong>
                        <span>{hop.subjectId}</span>
                      </div>
                      <small>
                        {relationLabel(hop.relation)}
                        <br />
                        {hop.assurance}
                      </small>
                    </div>
                  ))}
                </div>
              </section>
              <section className="detail-section authority-callout">
                <h3>
                  <KeyRound size={17} /> Authority interpretation
                </h3>
                <strong>{authorityLabel(detail.data.authoritySummary)}</strong>
                <p>
                  {detail.data.delegationClaimCount > 0
                    ? "A structurally validated delegation claim is present. Cryptographic delegation authority is not implemented, and the read model grants no execution authority."
                    : "These hops establish causality only. No delegated execution authority is claimed."}
                </p>
              </section>
            </>
          ) : (
            <QueryEmpty message="Select a lineage." />
          )}
        </div>
        <div className="query-readonly">
          <LockKeyhole size={15} /> Lineage records cannot authorize execution
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

function authorityLabel(value: LineageSummary["authoritySummary"]): string {
  return value === "validated_delegation_claim"
    ? "Validated delegation claim"
    : "Causal only";
}

function relationLabel(value: "root" | "causal" | "delegation_claim"): string {
  if (value === "delegation_claim") return "delegation claim";
  return value;
}
