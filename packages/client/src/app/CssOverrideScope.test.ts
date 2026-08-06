import { describe, expect, it } from "vitest";
import { allowsCssOverrides } from "./CssOverrideScope";

describe("allowsCssOverrides", () => {
  it.each(["/", "/terms", "/privacy", "/cookies", "/guidelines", "/legal-notice"])(
    "keeps %s on the shipped design",
    (pathname) => expect(allowsCssOverrides(pathname)).toBe(false),
  );

  it("handles trailing slashes on fixed-design pages", () => {
    expect(allowsCssOverrides("/privacy/")).toBe(false);
  });

  it.each(["/app", "/login", "/friends", "/servers/example"])(
    "allows account overrides on %s",
    (pathname) => expect(allowsCssOverrides(pathname)).toBe(true),
  );
});
