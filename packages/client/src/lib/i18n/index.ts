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


export type LanguagePref = "system" | Language;


export type Language = "en" | "lt" | "zh" | "hi" | "es" | "ar" | "fr" | "bn" | "pt" | "ru" | "ur" | "pirate" | "lolcat";

const CATALOGS: Record<Language, Catalog> = { en, lt, zh, hi, es, ar, fr, bn, pt, ru, ur, pirate, lolcat };


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


export function endonymOf(code: Language): string {
  return LANGUAGES.find((l) => l.code === code)?.endonym ?? code;
}

const STORAGE_KEY = "oc-language";

function isLanguage(value: string): value is Language {
  return value in CATALOGS;
}


function systemLanguage(): Language {
  for (const tag of navigator.languages ?? [navigator.language]) {
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
  }
  return "system";
}

interface LanguageState {

  pref: LanguagePref;

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
  }
  document.documentElement.lang = language;
}


export function initLanguage(): void {
  document.documentElement.lang = useLanguage.getState().language;
}


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


export function t(key: MessageKey, vars?: Record<string, string | number>): string {
  return interpolate(lookup(key) ?? key, vars);
}


export function tNodes(
  key: MessageKey,
  slots: Record<string, ReactNode>,
  vars?: Record<string, string | number>,
): ReactNode[] {
  return t(key, vars)
    .split(/(\{\w+\})/)
    .map((piece, index) => {
      const name = /^\{(\w+)\}$/.exec(piece)?.[1];
      if (name && name in slots) return createElement(Fragment, { key: index }, slots[name]);
      return piece;
    });
}


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
