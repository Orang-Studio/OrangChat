import { Fragment, createElement, type ReactNode } from "react";
import { create } from "zustand";
import { en, type Catalog, type MessageKey } from "./en";
import { lt } from "./lt";
import { zh } from "./zh";
import { hi } from "./hi";
import { es } from "./es";
import { ar } from "./ar";
import { fr } from "./fr";
import { bn } from "./bn";
import { pt } from "./pt";
import { ru } from "./ru";
import { ur } from "./ur";
import { pirate } from "./pirate";
import { lolcat } from "./lolcat";

/**
 * Which language the interface speaks.
 *
 * Device-local, like the rest of `prefs.ts`: it describes this browser, not the
 * account, so signing in on a Lithuanian laptop and an English phone gives each
 * the language its owner set there. `"system"` follows the browser's own
 * preference and keeps following it, which is why it is a stored value in its
 * own right rather than a resolved language code.
 */
export type LanguagePref = "system" | Language;

/** A language the app actually ships strings for. */
export type Language = "en" | "lt" | "zh" | "hi" | "es" | "ar" | "fr" | "bn" | "pt" | "ru" | "ur" | "pirate" | "lolcat";

const CATALOGS: Record<Language, Catalog> = { en, lt, zh, hi, es, ar, fr, bn, pt, ru, ur, pirate, lolcat };

/** In the picker's order. The endonym, because that is what a speaker looks for. */
export const LANGUAGES: { code: Language; endonym: string; nameKey: MessageKey }[] = [
  { code: "en", endonym: "English", nameKey: "language.en" },
  { code: "lt", endonym: "Lietuvių", nameKey: "language.lt" },
  { code: "zh", endonym: "中文", nameKey: "language.zh" },
  { code: "hi", endonym: "हिन्दी", nameKey: "language.hi" },
  { code: "es", endonym: "Español", nameKey: "language.es" },
  { code: "ar", endonym: "العربية", nameKey: "language.ar" },
  { code: "fr", endonym: "Français", nameKey: "language.fr" },
  { code: "bn", endonym: "বাংলা", nameKey: "language.bn" },
  { code: "pt", endonym: "Português", nameKey: "language.pt" },
  { code: "ru", endonym: "Русский", nameKey: "language.ru" },
  { code: "ur", endonym: "اردو", nameKey: "language.ur" },
  { code: "pirate", endonym: "Pirate", nameKey: "language.pirate" },
  { code: "lolcat", endonym: "LOLCAT", nameKey: "language.lolcat" },
];

/** What speakers of `code` call their own language. */
export function endonymOf(code: Language): string {
  return LANGUAGES.find((l) => l.code === code)?.endonym ?? code;
}

const STORAGE_KEY = "oc-language";

function isLanguage(value: string): value is Language {
  return value in CATALOGS;
}

/** The shipped language closest to what the browser asks for, else English. */
function systemLanguage(): Language {
  for (const tag of navigator.languages ?? [navigator.language]) {
    // "lt-LT" and "lt" both mean Lithuanian; only the subtag is ours to match.
    const base = tag.toLowerCase().split("-")[0];
    if (base && isLanguage(base)) return base;
  }
  return "en";
}

function read(): LanguagePref {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === "system" || (raw && isLanguage(raw))) return raw;
  } catch {
    // Storage unavailable (private mode) - follow the system this session.
  }
  return "system";
}

interface LanguageState {
  /** What the user chose, which may be "follow the system". */
  pref: LanguagePref;
  /** What that resolves to right now - the one the catalogue is read from. */
  language: Language;
}

const initialPref = read();

export const useLanguage = create<LanguageState>(() => ({
  pref: initialPref,
  language: initialPref === "system" ? systemLanguage() : initialPref,
}));

export function setLanguage(pref: LanguagePref): void {
  const language = pref === "system" ? systemLanguage() : pref;
  useLanguage.setState({ pref, language });
  try {
    localStorage.setItem(STORAGE_KEY, pref);
  } catch {
    // Not persisting is survivable; the choice still holds for this session.
  }
  document.documentElement.lang = language;
}

/** Reflect the initial choice into the document. Called once at startup. */
export function initLanguage(): void {
  document.documentElement.lang = useLanguage.getState().language;
}

/** `{name}` → the value under `name`. Anything unmatched is left alone. */
function interpolate(text: string, vars?: Record<string, string | number>): string {
  if (!vars) return text;
  return text.replace(/\{(\w+)\}/g, (whole, name: string) =>
    name in vars ? String(vars[name]) : whole,
  );
}

function lookup(key: string): string | undefined {
  const { language } = useLanguage.getState();
  return (
    (CATALOGS[language] as Record<string, string | undefined>)[key] ??
    (en as Record<string, string | undefined>)[key]
  );
}

/**
 * The string for `key` in the current language.
 *
 * Not a hook, so the modules that are not components - error mappers, the
 * outbox, socket handlers - can speak the same language as the interface. The
 * cost is that a plain `t()` call does not re-render when the language changes;
 * components should read it through `useT()`, which does.
 */
export function t(key: MessageKey, vars?: Record<string, string | number>): string {
  // The key itself is the last resort: visible, greppable, and never blank.
  return interpolate(lookup(key) ?? key, vars);
}

/**
 * As `t()`, for a sentence with something rendered inside it - a link, a bold
 * name, a button.
 *
 * The alternative is cutting the sentence into a text node, a `<Link>`, and
 * another text node, and translating the pieces. That works in English and
 * nowhere else: the pieces are stuck in English word order, and no translator
 * can move a link that lives in the markup. Here the whole sentence is one
 * catalogue entry with `{slot}` placeholders, and the translation decides where
 * the slots land.
 */
export function tNodes(
  key: MessageKey,
  slots: Record<string, ReactNode>,
  vars?: Record<string, string | number>,
): ReactNode[] {
  // Plain `{name}` values are already filled in; what is left are the slots.
  return t(key, vars)
    .split(/(\{\w+\})/)
    .map((piece, index) => {
      const name = /^\{(\w+)\}$/.exec(piece)?.[1];
      if (name && name in slots) return createElement(Fragment, { key: index }, slots[name]);
      return piece;
    });
}

/** Strips the variant suffix, so the stems are exactly the plural entries. */
type StemOf<K> = K extends `${infer S}_other` ? S : never;

/** A key with `_one`/`_other`/… variants in the catalogue, named without one. */
export type PluralKey = StemOf<MessageKey>;

/**
 * As `t()`, for a string whose wording depends on `count`.
 *
 * `key` is a stem: the catalogue holds `key_one`, `key_other` and whatever else
 * the language needs. `{count}` is filled in automatically.
 */
export function tCount(
  key: PluralKey,
  count: number,
  vars?: Record<string, string | number>,
): string {
  const { language } = useLanguage.getState();
  const category = new Intl.PluralRules(language).select(count);
  const text = lookup(`${key}_${category}`) ?? lookup(`${key}_other`) ?? key;
  return interpolate(text, { count, ...vars });
}

/**
 * Remounts everything under it when the language changes.
 *
 * This is why `t()` can be an ordinary function rather than a hook. A hook
 * would mean every one of the several hundred places that says a word has to
 * be inside a component - and plenty of them are not: error mappers, the
 * outbox, plain helpers that happen to return markup. Rebuilding the tree on
 * what is, in practice, a once-ever choice costs a frame and buys the rest of
 * the codebase the freedom to just call `t()`.
 */
export function useLanguageKey(): string {
  return useLanguage((s) => s.language);
}
