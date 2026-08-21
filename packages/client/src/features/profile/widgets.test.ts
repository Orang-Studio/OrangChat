import { describe, expect, it } from "vitest";
import {
  BUILTIN_LAYOUT,
  defaultLayout,
  fallbackDefinition,
  listFrom,
  lookupPlaceholder,
  resolveLayout,
  substitute,
  type PlaceholderSource,
} from "./widgets";

const source: PlaceholderSource = {
  username: "orang",
  displayName: "Orang",
  pronouns: "they/them",
  createdAt: "2024-03-09T10:11:12Z",
  fields: { status: "shipping things" },
  config: {
    title: "About me",
    count: 3,
    flag: true,
    rows: [{ label: "Status", value: "{field.status}" }, null, "not an object"],
  },
};

describe("resolveLayout", () => {
  it("falls back to the default layout when nothing was ever customised", () => {
    expect(resolveLayout(undefined)).toEqual(defaultLayout());
    expect(resolveLayout(null)).toEqual(defaultLayout());
    expect(resolveLayout([])).toEqual(defaultLayout());
    expect(defaultLayout().map((w) => w.type)).toEqual([...BUILTIN_LAYOUT]);
  });

  it("keeps a customised layout as written", () => {
    const custom = [{ id: "a", type: "text" }];
    expect(resolveLayout(custom)).toEqual(custom);
  });

  it("drops entries that are not widgets", () => {
    expect(resolveLayout([{ id: "a", type: "text" }, null, 7, { id: "b" }])).toEqual([
      { id: "a", type: "text" },
    ]);
  });
});

describe("lookupPlaceholder", () => {
  it("resolves config, pushed fields and built-ins", () => {
    expect(lookupPlaceholder("config.title", source)).toBe("About me");
    expect(lookupPlaceholder("config.count", source)).toBe("3");
    expect(lookupPlaceholder("field.status", source)).toBe("shipping things");
    expect(lookupPlaceholder("username", source)).toBe("orang");
    expect(lookupPlaceholder("displayName", source)).toBe("Orang");
    expect(lookupPlaceholder("pronouns", source)).toBe("they/them");
    expect(lookupPlaceholder("joinedYear", source)).toBe("2024");
  });

  it("returns null for anything it does not know", () => {
    expect(lookupPlaceholder("nope", source)).toBeNull();
    expect(lookupPlaceholder("field.missing", source)).toBeNull();
    expect(lookupPlaceholder("config.missing", source)).toBeNull();
    expect(lookupPlaceholder("config.flag", source)).toBeNull();
    expect(lookupPlaceholder("joinedYear", { createdAt: "yesterday" })).toBeNull();
    expect(lookupPlaceholder("joinedYear", {})).toBeNull();
  });
});

describe("substitute", () => {
  it("leaves an unknown token visible instead of blanking it", () => {
    expect(substitute("hi {displayName}, {field.nope} since {joinedYear}", source)).toBe(
      "hi Orang, {field.nope} since 2024",
    );
  });

  it("never treats widget text as an i18n key", () => {
    expect(substitute("{userSettingsDialog.displayName}", source)).toBe(
      "{userSettingsDialog.displayName}",
    );
  });

  it("substitutes every occurrence", () => {
    expect(substitute("{username}/{username}", source)).toBe("orang/orang");
  });
});

describe("listFrom", () => {
  it("only reads arrays of objects out of config", () => {
    const items = listFrom(source, "config.rows");
    expect(items).toEqual([{ label: "Status", value: "{field.status}" }]);
    expect(substitute(String(items[0]?.value ?? ""), source)).toBe("shipping things");
    expect(listFrom(source, "config.title")).toEqual([]);
    expect(listFrom(source, "field.status")).toEqual([]);
    expect(listFrom(source, "config.missing")).toEqual([]);
  });
});

describe("fallbackDefinition", () => {
  it("renders every built-in widget before the catalogue arrives", () => {
    for (const type of BUILTIN_LAYOUT) {
      expect(fallbackDefinition(type)?.render).toBeTruthy();
    }
    expect(fallbackDefinition("text")).toBeNull();
  });
});
