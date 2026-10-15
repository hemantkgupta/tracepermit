import { defineConfig } from "vitepress";

export default defineConfig({
  title: "TracePermit",
  description:
    "Agentic Visibility & Control POC for attributed, pre-execution policy on simulated AI-agent actions.",
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
        content: "TracePermit | Agentic Visibility & Control POC",
      },
    ],
    [
      "meta",
      {
        property: "og:description",
        content:
          "Attribute, classify, and govern AI-agent actions before safe simulated execution.",
      },
    ],
  ],

  themeConfig: {
    logo: "/logo.svg",
    siteTitle: "TracePermit",

    nav: [
      { text: "Guide", link: "/guide/what-is-tracepermit" },
      { text: "Core Flows", link: "/guide/core-flows" },
      { text: "Local Demo", link: "/guide/getting-started" },
      { text: "Architecture", link: "/guide/architecture" },
      { text: "GitHub", link: "https://github.com/hemantkgupta/tracepermit" },
    ],

    sidebar: [
      {
        text: "Overview",
        items: [
          { text: "What is TracePermit?", link: "/guide/what-is-tracepermit" },
          { text: "Local POC Quickstart", link: "/guide/getting-started" },
          {
            text: "The Security Invariant",
            link: "/guide/security-invariants",
          },
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
        items: [{ text: "Deployment Status", link: "/guide/deployment" }],
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
