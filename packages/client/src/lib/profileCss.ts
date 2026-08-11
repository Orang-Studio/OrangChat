

const MAX_LEN = 100_000;

const EXTERNAL_URL = /url\(\s*['"]?(?!data:)/i;
const DANGEROUS_TOKEN = /(expression\(|javascript:|-moz-binding|behavior\s*:|@import|@charset)/i;


const LAYER_NAME = /^[\w-]+(\.[\w-]+)*$/;

const STYLE_CLOSE = /<\/style/gi;


function stripStyleClose(css: string): string {
  let out = css;
  while (STYLE_CLOSE.test(out)) {
    STYLE_CLOSE.lastIndex = 0;
    out = out.replace(STYLE_CLOSE, "");
  }
  STYLE_CLOSE.lastIndex = 0;
  return out;
}


type GroupingRule = CSSRule & {
  cssRules: CSSRuleList;
  containerName?: string;
  containerQuery?: string;
  name?: string;
};


function isRule(name: string, rule: CSSRule): rule is GroupingRule {
  const ctor = (globalThis as Record<string, unknown>)[name];
  return typeof ctor === "function" && rule instanceof (ctor as new () => CSSRule);
}


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
