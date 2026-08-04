// Theme toggle. The pre-paint script inlined in every page's <head> already
// set data-theme synchronously from localStorage-or-OS before first paint (to
// avoid a flash of the wrong theme) — this only wires the button and persists
// a manual override on top of that.

const KEY = "sos-theme";

export function wireThemeToggle(buttonId = "theme-toggle"): void {
  const button = document.getElementById(buttonId);
  if (!button) return;

  const label = (theme: string) => `Switch to ${theme === "light" ? "dark" : "light"} theme`;
  const current = () => (document.documentElement.dataset.theme === "light" ? "light" : "dark");

  button.setAttribute("aria-label", label(current()));
  button.addEventListener("click", () => {
    const next = current() === "light" ? "dark" : "light";
    document.documentElement.dataset.theme = next;
    // Kept in sync with the pre-paint inline style — see the comment on it in
    // <head> for why this isn't left to the stylesheet's `color-scheme` alone.
    document.documentElement.style.colorScheme = next;
    localStorage.setItem(KEY, next);
    button.setAttribute("aria-label", label(next));
  });
}
