import type { ActionFeedEntry, DecisionOutcome, Severity } from "./types.js";

const LOCAL_TIME_ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone;
const timeFormatters = new Map<string, Intl.DateTimeFormat>();

export const LOCAL_TIME_ZONE_LABEL = timeZoneLabel(LOCAL_TIME_ZONE);

export function formatTime(value: string, timeZone = LOCAL_TIME_ZONE): string {
  let formatter = timeFormatters.get(timeZone);
  if (!formatter) {
    formatter = new Intl.DateTimeFormat("en-GB", {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit",
      hour12: false,
      timeZone,
    });
    timeFormatters.set(timeZone, formatter);
  }
  return formatter.format(new Date(value));
}

export function formatTimeWithZone(
  value: string,
  timeZone = LOCAL_TIME_ZONE,
): string {
  return `${formatTime(value, timeZone)} ${timeZoneLabel(timeZone, new Date(value))}`;
}

export function timeZoneLabel(timeZone: string, instant = new Date()): string {
  if (timeZone === "Asia/Kolkata" || timeZone === "Asia/Calcutta") return "IST";
  if (timeZone === "UTC" || timeZone === "Etc/UTC") return "UTC";
  const label = new Intl.DateTimeFormat("en-US", {
    hour: "2-digit",
    timeZone,
    timeZoneName: "short",
  })
    .formatToParts(instant)
    .find((part) => part.type === "timeZoneName")?.value;
  return label ?? timeZone;
}

export function decisionLabel(value: DecisionOutcome): string {
  if (value === "allow") return "Allowed";
  if (value === "block") return "Blocked";
  if (value === "sanitize") return "Sanitized";
  return "Require approval";
}

export function severityLabel(value: Severity): string {
  return value.charAt(0).toUpperCase() + value.slice(1);
}

export function initials(name: string): string {
  return name
    .split(/\s+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((part) => part[0]?.toUpperCase() ?? "")
    .join("");
}

export function liveLatencyMetric(
  p95LatencyMs: number,
  sampleSize: number,
): { value: string; trend: string } {
  if (sampleSize === 0) {
    return { value: "—", trend: "Awaiting live actions" };
  }
  return {
    value: `${p95LatencyMs} ms`,
    trend: `${sampleSize} current-run ${sampleSize === 1 ? "sample" : "samples"}`,
  };
}

export function matchesLocalFilters(
  entry: ActionFeedEntry,
  search: string,
  decision: string,
  severity: string,
  origin: string,
): boolean {
  if (decision && entry.decision.outcome !== decision) return false;
  if (severity && entry.risk.severity !== severity) return false;
  if (origin && entry.trigger.origin !== origin) return false;
  const normalized = search.trim().toLowerCase();
  if (!normalized) return true;
  return [
    entry.intent,
    entry.identity.subject.displayName,
    entry.identity.subject.id,
    entry.trigger.origin,
    entry.target.system,
    entry.target.toolName,
    entry.target.resourceType,
    entry.decision.outcome,
  ]
    .join(" ")
    .toLowerCase()
    .includes(normalized);
}
