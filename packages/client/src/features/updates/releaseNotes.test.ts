import { describe, expect, it } from "vitest";
import { shouldShowReleaseNotes } from "./releaseNotes";

describe("shouldShowReleaseNotes", () => {
  it("shows an unseen release once for the signed-in user", () => {
    expect(shouldShowReleaseNotes("0.4.8", null)).toBe(true);
  });

  it("does not show a release the signed-in user already acknowledged", () => {
    expect(shouldShowReleaseNotes("0.4.8", "0.4.8")).toBe(false);
  });

  it("shows a newer release after an older one was acknowledged", () => {
    expect(shouldShowReleaseNotes("0.4.8", "0.4.7")).toBe(true);
  });
});
