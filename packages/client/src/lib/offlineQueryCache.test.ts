import type { DehydratedState } from "@tanstack/react-query";
import { describe, expect, it } from "vitest";
import { compactMessageHistory, shouldPersistQuery } from "./offlineQueryCache";

describe("offline query cache", () => {
  it("persists shell and conversation data but excludes account security data", () => {
    expect(shouldPersistQuery(["servers"])).toBe(true);
    expect(shouldPersistQuery(["server", "one"])).toBe(true);
    expect(shouldPersistQuery(["dms"])).toBe(true);
    expect(shouldPersistQuery(["messages", "channel"])).toBe(true);
    expect(shouldPersistQuery(["friends", "requests"])).toBe(true);
    expect(shouldPersistQuery(["sessions"])).toBe(false);
    expect(shouldPersistQuery(["security", "devices"])).toBe(false);
  });

  it("bounds persisted history to the five newest pages", () => {
    const state = {
      mutations: [],
      queries: [
        {
          queryKey: ["messages", "channel"],
          queryHash: "messages-channel",
          state: {
            data: {
              pages: [0, 1, 2, 3, 4, 5, 6],
              pageParams: [undefined, "1", "2", "3", "4", "5", "6"],
            },
          },
        },
      ],
    } as unknown as DehydratedState;

    const compacted = compactMessageHistory(state);
    const data = compacted.queries[0]!.state.data as {
      pages: number[];
      pageParams: Array<string | undefined>;
    };
    expect(data.pages).toEqual([0, 1, 2, 3, 4]);
    expect(data.pageParams).toEqual([undefined, "1", "2", "3", "4"]);
  });
});
