import { defineConfig, type HeadConfig } from "vitepress";

const description =
  "Request-bound approvals for coding agents: authenticated requests, independent review and explicit simulator execution.";

export default defineConfig({
  title: "TracePermit",
  description,
  cleanUrls: true,
  lastUpdated: true,
  sitemap: {
    hostname: "https://tracepermit.org",
    transformItems: (items) => items.filter((item) => item.url !== "404"),
  },

  // Serialized page data keeps metadata correct during client-side navigation.
  // transformHead alone only affects the initial server-rendered document.
  transformPageData(pageData) {
    if (pageData.relativePath === "404.md") return;
    const path = pageData.relativePath
      .replace(/(^|\/)index\.md$/, "$1")
      .replace(/\.md$/, "");
    const canonical = new URL(path, "https://tracepermit.org/").href;
    const title =
      pageData.relativePath === "index.md"
        ? "TracePermit | Request-bound approvals for coding agents"
        : `${pageData.title} | TracePermit`;
    const head: HeadConfig[] = [
      ["link", { rel: "canonical", href: canonical }],
      ["meta", { property: "og:url", content: canonical }],
      ["meta", { property: "og:title", content: title }],
      [
        "meta",
        {
          property: "og:description",
          content: pageData.description || description,
        },
      ],
    ];
    pageData.frontmatter.head = [...(pageData.frontmatter.head || []), ...head];
  },

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
        property: "og:image",
        content: "https://tracepermit.org/images/tracepermit-social.png",
      },
    ],
    [
      "meta",
      {
        property: "og:image:alt",
        content:
          "TracePermit: request, independent review and explicit resume. Simulator-only evaluation preview.",
      },
    ],
    ["meta", { property: "og:image:width", content: "1200" }],
    ["meta", { property: "og:image:height", content: "630" }],
    ["meta", { name: "twitter:card", content: "summary_large_image" }],
  ],

  themeConfig: {
    logo: "/logo.svg",
    siteTitle: "TracePermit",

    nav: [
      { text: "Learn", link: "/learn/securing-mcp-tool-execution" },
      {
        text: "Guide",
        items: [
          { text: "What is TracePermit?", link: "/guide/what-is-tracepermit" },
          { text: "What Works Today", link: "/guide/working-today" },
          { text: "Verification & Compatibility", link: "/guide/verification" },
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
          {
            text: "Request a simulator demo",
            link: "mailto:hemant@tracepermit.org?subject=TracePermit%20simulator%20demo",
          },
        ],
      },
      { text: "GitHub", link: "https://github.com/hemantkgupta/tracepermit" },
    ],

    sidebar: [
      {
        text: "Agent Security Foundations",
        items: [
          {
            text: "Securing MCP Tool Execution",
            link: "/learn/securing-mcp-tool-execution",
          },
        ],
      },
      {
        text: "Overview",
        items: [
          { text: "What Works Today", link: "/guide/working-today" },
          { text: "Verification & Compatibility", link: "/guide/verification" },
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
        'Java/React simulator preview. Apache License 2.0. <a href="mailto:hemant@tracepermit.org">Contact: hemant@tracepermit.org</a>.',
      copyright: "Copyright © 2026 TracePermit Contributors",
    },

    search: {
      provider: "local",
    },
  },
});
