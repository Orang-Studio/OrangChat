import { desktop } from "../../lib/desktop";

/**
 * How this build identifies itself to the server, which decides whether it is
 * still supported (services::update_policy).
 *
 * Only the desktop shell reports anything. A browser is always running whatever
 * the server just served it, so there is no such thing as an out-of-date web
 * client to warn about - and claiming a version here would invite the server to
 * lock users out of a build it had itself handed them a moment earlier.
 */
export function clientVersionHeaders(): Record<string, string> {
  if (!desktop?.version) return {};
  return {
    "X-Client-Platform": "desktop",
    "X-Client-Version": desktop.version,
  };
}
