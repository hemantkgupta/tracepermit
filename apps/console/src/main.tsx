import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { App } from "./App.js";
import { ApprovalInbox } from "./ApprovalInbox.js";
import "./styles.css";

const root = document.getElementById("root");

if (!root) {
  throw new Error("TracePermit console root element is missing.");
}

createRoot(root).render(
  <StrictMode>
    {window.location.pathname === "/approvals" ? <ApprovalInbox /> : <App />}
  </StrictMode>,
);
