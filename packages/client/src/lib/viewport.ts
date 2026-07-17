/**
 * Keeps `--oc-vvh` in sync with the height actually visible to the user.
 *
 * The app shell is a full-height, non-scrolling column with the composer pinned
 * at the bottom, which relies on that height excluding the on-screen keyboard.
 * `100dvh` will not do it: the dynamic viewport tracks retracting browser chrome
 * but is specified to ignore virtual keyboards, so the composer keeps its place
 * *underneath* the keyboard and the text being typed is invisible.
 *
 * `interactive-widget=resizes-content` (index.html) fixes this properly on
 * Chrome by shrinking the layout viewport. Safari ignores that hint, so we also
 * track visualViewport - the one signal every mobile browser gives us - and hand
 * it to CSS. Where both apply they agree, so the var is simply always right.
 */
export function initViewport() {
  const vv = window.visualViewport;
  // No visualViewport (older desktop browsers): the `100dvh` fallback in the
  // CSS var stands, which is correct for a device with no virtual keyboard.
  if (!vv) return;

  const apply = () => {
    document.documentElement.style.setProperty("--oc-vvh", `${vv.height}px`);
  };

  apply();
  vv.addEventListener("resize", apply);
  // Safari shifts the visual viewport around while the keyboard animates; the
  // height lands at its final value only after those scroll events.
  vv.addEventListener("scroll", apply);
}
