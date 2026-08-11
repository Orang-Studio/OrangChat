
export function initViewport() {
  const vv = window.visualViewport;
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
