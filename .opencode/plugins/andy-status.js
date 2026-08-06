/**
 * Andy-managed OpenCode plugin — writes lifecycle status via andy-status-hook.sh.
 *
 * Installed into project `.opencode/plugins/andy-status.js` by Andy on session start.
 * Badge authority remains screen scrape; this only writes status.json artifacts.
 *
 * Marker string "andy-status-hook" lets Andy replace prior installs without
 * clobbering other user plugins.
 */
import { execFileSync } from "node:child_process";
import { homedir } from "node:os";
import { join } from "node:path";

const HOOK = join(homedir(), ".andy", "bin", "andy-status-hook.sh");

function runStatus(status) {
  try {
    execFileSync(HOOK, [status], {
      stdio: ["ignore", "ignore", "ignore"],
      env: process.env,
      timeout: 5_000,
    });
  } catch {
    // best-effort
  }
}

export default async function andyStatusPlugin() {
  return {
    event: async ({ event }) => {
      const type = String(event?.type || event?.name || "").toLowerCase();
      if (
        type.includes("session.created") ||
        type.includes("session.start") ||
        type.includes("message.user") ||
        type.includes("tool.execute")
      ) {
        runStatus("working");
        return;
      }
      if (type.includes("session.idle") || type.includes("session.deleted")) {
        runStatus("done");
        return;
      }
      if (type.includes("permission") || type.includes("ask")) {
        runStatus("blocked");
      }
    },
    "tool.execute.before": async () => {
      runStatus("working");
    },
  };
}
