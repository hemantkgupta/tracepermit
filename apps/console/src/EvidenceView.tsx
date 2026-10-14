import {
  AlertTriangle,
  CheckCircle2,
  Fingerprint,
  Link2,
  LockKeyhole,
  ShieldCheck,
} from "lucide-react";
import { useMemo, useState } from "react";
import { loadEvidenceIntegrity } from "./api.js";
import { formatTimeWithZone } from "./formatters.js";
import type { EvidenceIntegritySummary } from "./types.js";
import { useReadDetail } from "./use-read-detail.js";

export function EvidenceView({
  items,
  search,
}: {
  items: EvidenceIntegritySummary[];
  search: string;
}) {
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const visibleItems = useMemo(() => {
    const query = search.trim().toLowerCase();
    if (!query) return items;
    return items.filter((item) =>
      `${item.eventId} ${item.traceId} ${item.streamId}`
        .toLowerCase()
        .includes(query),
    );
  }, [items, search]);
  const activeId =
    visibleItems.find((item) => item.eventId === selectedId)?.eventId ??
    visibleItems[0]?.eventId ??
    null;
  const detail = useReadDetail(activeId, loadEvidenceIntegrity);
  const verifiedCount = items.filter((item) => item.valid).length;
  const eventCount = items.reduce((total, item) => total + item.eventCount, 0);
  const anchorCount = items.filter((item) => item.anchored).length;

  return (
    <>
      <main className="workspace query-workspace">
        <section className="query-hero">
          <div>
            <span className="eyebrow">Evidence integrity</span>
            <h1>Verify the recorded history</h1>
            <p>
              Local hash-chain verification detects edits and broken links. It
              does not prove semantic truth or replace an external anchor.
            </p>
          </div>
          <span className="simulation-badge">
            <span /> Simulated data
          </span>
        </section>

        <section className="query-metrics" aria-label="Evidence metrics">
          <article>
            <CheckCircle2 size={19} />
            <span>Verified streams</span>
            <strong>{verifiedCount}</strong>
          </article>
          <article>
            <Link2 size={19} />
            <span>Evidence events</span>
            <strong>{eventCount}</strong>
          </article>
          <article className="warning">
            <AlertTriangle size={19} />
            <span>External anchors</span>
            <strong>{anchorCount}</strong>
          </article>
        </section>

        <section className="query-card">
          <div className="query-card-heading">
            <div>
              <h2>Integrity streams</h2>
              <p>One bounded evidence stream per simulated action.</p>
            </div>
            <span>{visibleItems.length} shown</span>
          </div>
          <div className="query-list" role="list">
            {visibleItems.length === 0 ? (
              <QueryEmpty message="No evidence stream matches this search." />
            ) : (
              visibleItems.map((item) => (
                <button
                  aria-pressed={item.eventId === activeId}
                  className={`query-row${item.eventId === activeId ? " selected" : ""}`}
                  key={item.eventId}
                  onClick={() => setSelectedId(item.eventId)}
                  type="button"
                >
                  <span
                    className={`query-status ${item.valid ? "valid" : "invalid"}`}
                  >
                    {item.valid ? (
                      <ShieldCheck size={18} />
                    ) : (
                      <AlertTriangle size={18} />
                    )}
                  </span>
                  <span className="query-row-copy">
                    <strong>Action {shortId(item.eventId)}</strong>
                    <small>Trace {item.traceId.slice(0, 12)}…</small>
                  </span>
                  <span className="query-row-meta">
                    <strong>{item.eventCount} events</strong>
                    <small>
                      {item.anchored
                        ? "Externally anchored"
                        : "Local verification"}
                    </small>
                  </span>
                </button>
              ))
            )}
          </div>
        </section>
      </main>

      <aside
        className="details-panel query-details"
        aria-label="Evidence integrity detail"
      >
        <div className="query-detail-header">
          <span className="detail-icon success">
            <Fingerprint size={21} />
          </span>
          <div>
            <span className="eyebrow">Selected stream</span>
            <h2>
              {activeId
                ? `Action ${shortId(activeId)}`
                : "No evidence selected"}
            </h2>
          </div>
        </div>
        <div className="detail-scroll">
          {detail.loading ? (
            <QueryEmpty message="Loading integrity detail…" />
          ) : detail.error ? (
            <QueryEmpty message={detail.error} />
          ) : detail.data ? (
            <>
              <section className="detail-section">
                <h3>
                  <ShieldCheck size={17} /> Verification result
                </h3>
                <div className="integrity-callout">
                  <strong>
                    {detail.data.valid
                      ? "Chain verified"
                      : "Integrity issue detected"}
                  </strong>
                  <span>
                    {detail.data.anchored
                      ? "External anchor checked"
                      : "No external anchor configured"}
                  </span>
                </div>
                <Definition label="Stream" value={detail.data.streamId} />
                <Definition
                  label="Head hash"
                  value={shortHash(detail.data.headHash)}
                />
                <Definition label="Trace" value={detail.data.traceId} />
              </section>
              <section className="detail-section">
                <h3>
                  <Link2 size={17} /> Append-only sequence
                </h3>
                <div className="chain-list">
                  {detail.data.events.map((event) => (
                    <article key={event.evidenceEventId}>
                      <span>{event.sequence}</span>
                      <div>
                        <strong>{event.eventType.replaceAll(".", " ")}</strong>
                        <small>
                          {formatTimeWithZone(event.occurredAt)} ·{" "}
                          {shortHash(event.eventHash)}
                        </small>
                      </div>
                    </article>
                  ))}
                </div>
              </section>
              <section className="detail-section boundary-note">
                <h3>
                  <AlertTriangle size={17} /> Integrity is not truth
                </h3>
                <p>
                  This proves that the locally recorded bytes link correctly. It
                  cannot prove that the application’s original claim was true.
                </p>
              </section>
            </>
          ) : (
            <QueryEmpty message="Select an evidence stream." />
          )}
        </div>
        <ReadOnlyFooter label="Evidence queries are read-only" />
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

function ReadOnlyFooter({ label }: { label: string }) {
  return (
    <div className="query-readonly">
      <LockKeyhole size={15} /> {label}
    </div>
  );
}

function shortHash(hash: string): string {
  return hash.length > 24 ? `${hash.slice(0, 18)}…${hash.slice(-6)}` : hash;
}

function shortId(id: string): string {
  return `${id.slice(0, 8)}…${id.slice(-4)}`;
}
