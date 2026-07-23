/**
 * Master switch for the brand rename. The settings toggle that drove it has
 * been taken out of the UI, so nothing can turn `iHateAdas` on any more, but
 * the machinery below is deliberately kept whole for when it comes back: flip
 * this to `true` and restore the Toggle in AccessibilityTab.
 *
 * It gates the stored pref rather than the toggle alone, so anyone who switched
 * the rename on while the toggle existed is not left in a renamed UI with no
 * way back out of it.
 *
 * Typed `boolean` rather than inferred, so the checks below read as ordinary
 * conditions instead of narrowing to `false` and looking like dead branches.
 */
export const BRAND_REPLACEMENT_ENABLED: boolean = false;

const BRAND_PATTERN = /orangchat/gi;
const REPLACED_ATTRIBUTES = ["alt", "aria-label", "aria-description", "placeholder", "title"];

type TextState = { original: string; rendered: string };
type AttributeState = { original: string; rendered: string };

const textStates = new WeakMap<Text, TextState>();
const attributeStates = new WeakMap<Element, Map<string, AttributeState>>();
const trackedText = new Set<Text>();
const trackedElements = new Set<Element>();

let observer: MutationObserver | null = null;

function isDisplayText(node: Text): boolean {
  const parent = node.parentElement;
  if (!parent) return false;
  if (["SCRIPT", "STYLE", "NOSCRIPT", "TEMPLATE", "TEXTAREA"].includes(parent.tagName)) {
    return false;
  }
  return !parent.closest('[contenteditable="true"]');
}

/** Replace the product name while retaining the capitalization users see. */
export function replaceOrangChat(value: string): string {
  return value.replace(BRAND_PATTERN, (match) => {
    if (match === match.toUpperCase()) return "BONFIRE";
    if (match.charAt(0) === match.charAt(0).toUpperCase()) return "Bonfire";
    return "bonfire";
  });
}

function renderText(node: Text): void {
  if (!isDisplayText(node)) return;
  const current = node.data;
  const previous = textStates.get(node);
  if (previous && current === previous.rendered) return;

  const rendered = replaceOrangChat(current);
  if (rendered === current) {
    textStates.delete(node);
    trackedText.delete(node);
    return;
  }

  textStates.set(node, { original: current, rendered });
  trackedText.add(node);
  node.data = rendered;
}

function renderAttribute(element: Element, name: string): void {
  const current = element.getAttribute(name);
  if (current === null) return;

  const states = attributeStates.get(element);
  const previous = states?.get(name);
  if (previous && current === previous.rendered) return;

  const rendered = replaceOrangChat(current);
  if (rendered === current) {
    states?.delete(name);
    return;
  }

  const nextStates = states ?? new Map<string, AttributeState>();
  nextStates.set(name, { original: current, rendered });
  attributeStates.set(element, nextStates);
  trackedElements.add(element);
  element.setAttribute(name, rendered);
}

function renderNode(node: Node): void {
  if (node.nodeType === Node.TEXT_NODE) {
    renderText(node as Text);
    return;
  }
  if (node.nodeType !== Node.ELEMENT_NODE) return;

  const element = node as Element;
  for (const name of REPLACED_ATTRIBUTES) renderAttribute(element, name);

  const walker = document.createTreeWalker(element, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
  while (walker.nextNode()) {
    const child = walker.currentNode;
    if (child.nodeType === Node.TEXT_NODE) {
      renderText(child as Text);
    } else {
      for (const name of REPLACED_ATTRIBUTES) renderAttribute(child as Element, name);
    }
  }
}

function enable(): void {
  if (observer) return;
  renderNode(document.documentElement);
  observer = new MutationObserver((records) => {
    for (const record of records) {
      if (record.type === "characterData") {
        renderText(record.target as Text);
      } else if (record.type === "attributes" && record.attributeName) {
        renderAttribute(record.target as Element, record.attributeName);
      } else {
        record.addedNodes.forEach(renderNode);
      }
    }
  });
  observer.observe(document.documentElement, {
    attributes: true,
    attributeFilter: REPLACED_ATTRIBUTES,
    characterData: true,
    childList: true,
    subtree: true,
  });
}

function disable(): void {
  observer?.disconnect();
  observer = null;

  for (const node of trackedText) {
    const state = textStates.get(node);
    if (state && node.data === state.rendered) node.data = state.original;
    textStates.delete(node);
  }
  trackedText.clear();

  for (const element of trackedElements) {
    const states = attributeStates.get(element);
    if (states) {
      for (const [name, state] of states) {
        if (element.getAttribute(name) === state.rendered) {
          element.setAttribute(name, state.original);
        }
      }
    }
    attributeStates.delete(element);
  }
  trackedElements.clear();
}

/** Apply the accessibility rename without changing React state or message data. */
export function applyBrandReplacement(enabled: boolean): void {
  if (enabled && BRAND_REPLACEMENT_ENABLED) enable();
  else disable();
}
