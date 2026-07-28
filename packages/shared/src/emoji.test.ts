import { describe, expect, it } from "vitest";
import {
  customEmojiIds,
  emojiToken,
  resolveShortcodes,
  tokenizeEmoji,
  type EmojiToken,
} from "./emoji.js";

const emojis: Record<string, EmojiToken> = {
  orange: { id: "abc123", name: "orange" },
  dance: { id: "def456", name: "dance", animated: true },
  "7771": { id: "cmrymix1c000tjl1d07zfuyzu", name: "7771" },
};
const byName = (name: string) => emojis[name];

describe("emojiToken", () => {
  it("marks animated emoji with the `a` prefix", () => {
    expect(emojiToken(emojis.orange!)).toBe("<:orange:abc123>");
    expect(emojiToken(emojis.dance!)).toBe("<a:dance:def456>");
  });
});

describe("tokenizeEmoji", () => {
  it("splits text and tokens, keeping tokens whole", () => {
    expect(tokenizeEmoji("hi <:orange:abc123>!")).toEqual([
      { kind: "text", text: "hi " },
      { kind: "emoji", animated: false, name: "orange", id: "abc123", raw: "<:orange:abc123>" },
      { kind: "text", text: "!" },
    ]);
  });

  it("reads the animated flag case-insensitively", () => {
    expect(tokenizeEmoji("<A:dance:def456>")).toEqual([
      { kind: "emoji", animated: true, name: "dance", id: "def456", raw: "<A:dance:def456>" },
    ]);
  });
});

describe("resolveShortcodes", () => {
  it("turns a typed shortcode into a token", () => {
    expect(resolveShortcodes("say :orange: now", byName)).toBe("say <:orange:abc123> now");
  });

  it("leaves an already-resolved token untouched (the double-wrap bug)", () => {
    const token = "<:7771:cmrymix1c000tjl1d07zfuyzu>";
    expect(resolveShortcodes(token, byName)).toBe(token);
  });

  it("resolves a shortcode sitting next to an existing token", () => {
    expect(resolveShortcodes("<:orange:abc123>:dance:", byName)).toBe(
      "<:orange:abc123><a:dance:def456>",
    );
  });

  it("leaves unknown shortcodes as literal text", () => {
    expect(resolveShortcodes("nope :unknown: here", byName)).toBe("nope :unknown: here");
  });
});

describe("customEmojiIds", () => {
  it("collects distinct ids in order and ignores shortcodes", () => {
    expect(
      customEmojiIds("<:orange:abc123> :dance: <a:dance:def456> <:orange:abc123>"),
    ).toEqual(["abc123", "def456"]);
  });
});
