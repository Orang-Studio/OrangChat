/**
 * Sanitize user-authored profile CSS so it can be shown in *other* people's
 * browsers without becoming a phishing / exfiltration vector (see the MySpace
 * Samy worm for why raw user CSS is dangerous).
 *
 * Strategy:
 *  1. Parse through the browser's CSSOM (a detached document) and re-serialize.
 *     Malformed break-outs like `} body {` cannot survive a real parser.
 *  2. Keep only style rules and the grouping at-rules that can't reach outside
 *     the sheet - @media, @supports, @container, @starting-style, @layer and
 *     @keyframes. Drop @import, @font-face, @page, etc. (no external stylesheet
 *     or font loads).
 *  3. Prefix every selector with a unique per-card scope class, so rules can
 *     only ever match that card's own (public) DOM - never the surrounding app.
 *  4. Strip declarations that enable escape / exfiltration / legacy scripting:
 *     external url(), position:fixed|sticky, expression(), -moz-binding,
 *     behavior:, javascript:, @import inside values.
 *  5. Strip `</style`, which survives the CSSOM inside a string value and would
 *     otherwise end the <style> element the result is injected into.
 *
 * The card container additionally sets `contain: layout paint style`,
 * `isolation: isolate` and `overflow: hidden`, so even an allowed
 * position:absolute can't paint outside the card box.
 */

const MAX_LEN = 100_000;

// url(...) where the argument is not a data: URI.
const EXTERNAL_URL = /url\(\s*['"]?(?!data:)/i;
const DANGEROUS_TOKEN = /(expression\(|javascript:|-moz-binding|behavior\s*:|@import|@charset)/i;

/** Plain dotted ident, e.g. `card` or `theme.dark`; anything else is refused. */
const LAYER_NAME = /^[\w-]+(\.[\w-]+)*$/;

const STYLE_CLOSE = /<\/style/gi;

/**
 * The result is injected as the raw-text content of a <style>, which ends at the
 * first literal `</style` - and the CSSOM happily round-trips that sequence
 * inside a string value or attribute selector, e.g. `content: "</style><img
 * src=x onerror=…>"`, where no other filter here matches it.
 *
 * Deleting a match joins the text on either side, and those halves can spell out
 * a fresh `</style` that a single pass would never revisit (`</s</styletyle`
 * collapses to exactly that), so repeat to a fixed point. Each pass drops seven
 * characters, so this terminates. Nothing legitimate is lost: an inline SVG
 * data: URI contains `</svg`, not `</style`.
 */
function stripStyleClose(css: string): string {
  let out = css;
  while (STYLE_CLOSE.test(out)) {
    STYLE_CLOSE.lastIndex = 0;
    out = out.replace(STYLE_CLOSE, "");
  }
  STYLE_CLOSE.lastIndex = 0;
  return out;
}

/** Grouping rules that only exist in newer engines, so they can't be named directly. */
type GroupingRule = CSSRule & {
  cssRules: CSSRuleList;
  containerName?: string;
  containerQuery?: string;
  name?: string;
};

/** `rule instanceof <name>` for a constructor that may not exist in this browser. */
function isRule(name: string, rule: CSSRule): rule is GroupingRule {
  const ctor = (globalThis as Record<string, unknown>)[name];
  return typeof ctor === "function" && rule instanceof (ctor as new () => CSSRule);
}

/** Split a selector list on top-level commas (ignoring commas inside :is()/:not()). */
function splitSelectorList(selector: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let current = "";
  for (const ch of selector) {
    if (ch === "(") depth++;
    else if (ch === ")") depth = Math.max(0, depth - 1);
    if (ch === "," && depth === 0) {
      parts.push(current);
      current = "";
    } else {
      current += ch;
    }
  }
  if (current.trim()) parts.push(current);
  return parts;
}

/** Confine each selector to descendants of the scope class. */
function scopeSelector(selector: string, scope: string): string {
  return splitSelectorList(selector)
    .map((s) => s.trim())
    .filter(Boolean)
    .map((s) => `${scope} ${s}`)
    .join(", ");
}

/** Rebuild a declaration block, dropping anything unsafe. */
function filterDeclarations(style: CSSStyleDeclaration): string {
  const out: string[] = [];
  for (let i = 0; i < style.length; i++) {
    const prop = style.item(i);
    const value = style.getPropertyValue(prop);
    if (!value) continue;
    if (DANGEROUS_TOKEN.test(value) || EXTERNAL_URL.test(value)) continue;
    if (prop === "position" && /\b(fixed|sticky)\b/i.test(value)) continue;
    const priority = style.getPropertyPriority(prop) ? " !important" : "";
    out.push(`${prop}: ${value}${priority}`);
  }
  return out.join("; ");
}

function processRules(rules: CSSRuleList, scope: string): string {
  let css = "";
  for (const rule of Array.from(rules)) {
    if (rule instanceof CSSStyleRule) {
      const selector = scopeSelector(rule.selectorText, scope);
      const decls = filterDeclarations(rule.style);
      if (selector && decls) css += `${selector} { ${decls} }\n`;
    } else if (rule instanceof CSSMediaRule) {
      const inner = processRules(rule.cssRules, scope);
      if (inner) css += `@media ${rule.media.mediaText} { ${inner} }\n`;
    } else if (rule instanceof CSSSupportsRule) {
      const inner = processRules(rule.cssRules, scope);
      if (inner) css += `@supports ${rule.conditionText} { ${inner} }\n`;
    } else if (isRule("CSSContainerRule", rule)) {
      // Container queries let a card react to its own width - the profile popup
      // and the settings preview render it at different sizes.
      const inner = processRules(rule.cssRules, scope);
      const name = LAYER_NAME.test(rule.containerName ?? "") ? `${rule.containerName} ` : "";
      if (inner) css += `@container ${name}${rule.containerQuery} { ${inner} }\n`;
    } else if (isRule("CSSStartingStyleRule", rule)) {
      // Entry animations without keyframes. Carries no prelude, so nothing to sanitize.
      const inner = processRules(rule.cssRules, scope);
      if (inner) css += `@starting-style { ${inner} }\n`;
    } else if (isRule("CSSLayerBlockRule", rule)) {
      // Cascade layers are purely local ordering. The name is an ident that can
      // encode braces via \XX escapes, so an unrecognised one becomes anonymous
      // rather than being spliced back into the sheet.
      const inner = processRules(rule.cssRules, scope);
      const name = LAYER_NAME.test(rule.name ?? "") ? ` ${rule.name}` : "";
      if (inner) css += `@layer${name} { ${inner} }\n`;
    } else if (rule instanceof CSSKeyframesRule) {
      // Keyframes are keyed by name and can only affect elements that reference
      // them (which are all scoped). Rebuild frames through the declaration
      // filter so a keyframe can't smuggle an external url().
      let frames = "";
      for (const kf of Array.from(rule.cssRules)) {
        if (kf instanceof CSSKeyframeRule) {
          const decls = filterDeclarations(kf.style);
          if (decls) frames += `${kf.keyText} { ${decls} }\n`;
        }
      }
      // `rule.name` is the *decoded* ident, and idents can encode `{`/`}` via
      // \XX escapes - re-escape it or the name splices raw rules past every
      // filter above (unscoped selectors, external url(), position:fixed).
      if (frames) css += `@keyframes ${CSS.escape(rule.name)} { ${frames} }\n`;
    }
    // Everything else (@import, @font-face, @page, …) is intentionally dropped.
  }
  return css;
}

/**
 * Returns scoped, safe CSS ready to drop into a <style> tag, or "" if the input
 * is empty or unparseable. `scopeClass` must be the class on the card container.
 */
export function sanitizeProfileCss(css: string | null | undefined, scopeClass: string): string {
  if (!css || !css.trim()) return "";
  try {
    const doc = document.implementation.createHTMLDocument("");
    const style = doc.createElement("style");
    style.textContent = css.slice(0, MAX_LEN);
    doc.head.appendChild(style);
    const sheet = style.sheet;
    if (!sheet) return "";
    return stripStyleClose(processRules(sheet.cssRules, `.${scopeClass}`));
  } catch {
    return "";
  }
}
