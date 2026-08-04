import type { Plugin } from "vite";

// The header brand, byte-identical to the single line in send/index.html and
// receive/index.html — the inline SVG is what lets the standalone pages keep
// the logo with no external reference. A drift here fails the build (below).
const BRAND_INNER =
  '<svg class="brand-logo" viewBox="0 0 32 32" fill="none" aria-hidden="true">' +
  '<circle cx="8" cy="24" r="2.6" fill="currentColor" />' +
  '<path d="M9.04 19.11 A5 5 0 0 1 12.89 22.96" stroke="currentColor" stroke-width="3.1" stroke-linecap="round" />' +
  '<path d="M9.87 15.20 A9 9 0 0 1 16.80 22.13" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-opacity="0.72" />' +
  '<path d="M10.70 11.28 A13 13 0 0 1 20.72 21.30" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-opacity="0.46" />' +
  "</svg>SOS";

// The pill nav, byte-identical to the single line each page renders it on —
// same drift protection as BRAND_INNER, and same reason it has to go: a
// standalone file has no sibling page for "Send" or "Receive" to point at.
const NAV = {
  send:
    '<nav class="pill-links" aria-label="Primary"><a class="pill-link" href="../send/" aria-current="page">Send</a>' +
    '<a class="pill-link" href="../receive/">Receive</a></nav>',
  receive:
    '<nav class="pill-links" aria-label="Primary"><a class="pill-link" href="../send/">Send</a>' +
    '<a class="pill-link" href="../receive/" aria-current="page">Receive</a></nav>',
};

/**
 * A standalone file has no siblings, so links to the other pages are dead ends.
 * Rewrites are exact-match and `required` ones throw when they miss, so editing
 * the markup breaks the build rather than silently shipping broken links.
 */
export function rewriteStandaloneLinks(page: "send" | "receive"): Plugin {
  const rules: { from: string; to: string; required: boolean }[] = [
    {
      from: `<a class="brand" href="../">${BRAND_INNER}</a>`,
      to: `<span class="brand">${BRAND_INNER}</span>`,
      required: true,
    },
    {
      from: NAV[page],
      to: "",
      required: true,
    },
    {
      from: 'Open <a href="../receive/">Receive</a> on the other device.',
      to: "Open the standalone receiver on the other device.",
      required: false,
    },
    {
      // A single file has no siblings to load a favicon from, and leaving the
      // link in would be the one external reference in a page whose whole point
      // is having none.
      from: '<link rel="icon" href="../sos_logo.svg" type="image/svg+xml" />',
      to: "",
      required: true,
    },
  ];
  return {
    name: "rewrite-standalone-links",
    transformIndexHtml(html) {
      for (const { from, to, required } of rules) {
        if (!html.includes(from)) {
          if (required) throw new Error(`standalone link rewrite missed its target: ${from}`);
          continue;
        }
        html = html.replaceAll(from, to);
      }
      return html;
    },
  };
}
