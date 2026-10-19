import { defineConfig } from "vitepress";

export default defineConfig({
  title: "TracePermit",
  description:
    "Request-bound approvals for coding agents: authenticated requests, independent review and explicit simulator execution.",
  cleanUrls: true,
  lastUpdated: true,

  vite: {
    resolve: {
      // VitePress 1.x accepts a URL in its search HMR hook; Vite 6 needs an ID.
      alias: { "/@localSearchIndex": "@localSearchIndex" },
    },
  },

  head: [
    ["link", { rel: "icon", type: "image/svg+xml", href: "/logo.svg" }],
    ["meta", { name: "theme-color", content: "#3b82f6" }],
    ["meta", { property: "og:type", content: "website" }],
    [
      "meta",
      {
        property: "og:title",
        content: "TracePermit | Request-bound approvals for coding agents",
      },
    ],
    [
      "meta",
      {
        property: "og:description",
        content:
          "Explore a simulator-only approval workflow with independent review, single-use authority and durable evidence.",
      },
    ],
  ],

  themeConfig: {
    logo: "/logo.svg",
    siteTitle: "TracePermit",

    nav: [
      {
        text: "Guide",
        items: [
          { text: "What is TracePermit?", link: "/guide/what-is-tracepermit" },
          { text: "What Works Today", link: "/guide/working-today" },
          { text: "Core Flows", link: "/guide/core-flows" },
          { text: "Architecture", link: "/guide/architecture" },
        ],
      },
      {
        text: "Evaluate locally",
        items: [
          {
            text: "Fresh MacBook Evaluation",
            link: "/guide/fresh-macbook-evaluation",
          },
          { text: "Local POC Quickstart", link: "/guide/getting-started" },
          { text: "Connector for Codex", link: "/guide/endpoint-connector" },
        ],
      },
      { text: "GitHub", link: "https://github.com/hemantkgupta/tracepermit" },
    ],

    sidebar: [
      {
        text: "Overview",
        items: [
          { text: "What Works Today", link: "/guide/working-today" },
          { text: "What is TracePermit?", link: "/guide/what-is-tracepermit" },
          {
            text: "Fresh MacBook Evaluation",
            link: "/guide/fresh-macbook-evaluation",
          },
          { text: "Local POC Quickstart", link: "/guide/getting-started" },
          { text: "Connector for Codex", link: "/guide/endpoint-connector" },
          {
            text: "The Security Invariant",
            link: "/guide/security-invariants",
          },
          { text: "Security Domain Map", link: "/guide/security-domains" },
        ],
      },
      {
        text: "Architecture & Engine",
        items: [
          { text: "End-to-End Architecture", link: "/guide/architecture" },
          { text: "Core Action Flows", link: "/guide/core-flows" },
          { text: "Model Context Protocol (MCP)", link: "/guide/mcp-gateway" },
          { text: "Cryptographic Permits", link: "/guide/permits" },
          {
            text: "Endpoint Source Attestation",
            link: "/guide/endpoint-attestation",
          },
        ],
      },
      {
        text: "Enforcement & Governance",
        items: [
          { text: "Policy Authoring", link: "/guide/policies" },
          { text: "Dual-Control Human Approvals", link: "/guide/approvals" },
          { text: "Action Sanitization", link: "/guide/sanitization" },
          { text: "Audit Evidence & Anchoring", link: "/guide/audit-evidence" },
        ],
      },
      {
        text: "Deployment & Operations",
        items: [
          { text: "Deployment & Operator Paths", link: "/guide/deployment" },
          {
            text: "Fresh MacBook Evaluation",
            link: "/guide/fresh-macbook-evaluation",
          },
        ],
      },
    ],

    socialLinks: [
      { icon: "github", link: "https://github.com/hemantkgupta/tracepermit" },
    ],

    footer: {
      message:
        "Java/React simulator POC. Source licensed under the Apache License 2.0.",
      copyright: "Copyright © 2026 TracePermit Contributors",
    },

    search: {
      provider: "local",
    },
  },
});
