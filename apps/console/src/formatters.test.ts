import { describe, expect, it } from "vitest";
import {
  decisionLabel,
  formatTime,
  formatTimeWithZone,
  initials,
  liveLatencyMetric,
  matchesLocalFilters,
  timeZoneLabel,
} from "./formatters.js";
import type { ActionFeedEntry } from "./types.js";

const entry = {
  intent: "Terminate a simulated compute instance",
  identity: {
    subject: { displayName: "Alice Example", id: "user:alice@example.invalid" },
  },
  trigger: { origin: "chatgpt-web" },
  target: {
    system: "aws",
    toolName: "aws.ec2.terminate_instance",
    resourceType: "compute-instance",
  },
  risk: { severity: "critical" },
  decision: { outcome: "block" },
} as ActionFeedEntry;

describe("console formatters", () => {
  it("renders stable labels, initials, and viewer-local time", () => {
    expect(decisionLabel("require_approval")).toBe("Require approval");
    expect(decisionLabel("sanitize")).toBe("Sanitized");
    expect(initials("Alice Example")).toBe("AE");
    expect(formatTime("2026-10-08T10:42:17.000Z", "Asia/Kolkata")).toBe(
      "16:12:17",
    );
    expect(formatTimeWithZone("2026-10-08T10:42:17.000Z", "Asia/Kolkata")).toBe(
      "16:12:17 IST",
    );
    expect(timeZoneLabel("Asia/Calcutta")).toBe("IST");
    expect(formatTime("2026-10-08T10:42:17.000Z", "UTC")).toBe("10:42:17");
  });

  it("applies all local filters", () => {
    expect(
      matchesLocalFilters(
        entry,
        "terminate",
        "block",
        "critical",
        "chatgpt-web",
      ),
    ).toBe(true);
    expect(
      matchesLocalFilters(entry, "jira", "block", "critical", "chatgpt-web"),
    ).toBe(false);
    expect(
      matchesLocalFilters(entry, "", "allow", "critical", "chatgpt-web"),
    ).toBe(false);
  });

  it("labels latency as a current-run measurement only when samples exist", () => {
    expect(liveLatencyMetric(0, 0)).toEqual({
      value: "—",
      trend: "Awaiting live actions",
    });
    expect(liveLatencyMetric(7, 1)).toEqual({
      value: "7 ms",
      trend: "1 current-run sample",
    });
    expect(liveLatencyMetric(11, 4)).toEqual({
      value: "11 ms",
      trend: "4 current-run samples",
    });
  });
});
