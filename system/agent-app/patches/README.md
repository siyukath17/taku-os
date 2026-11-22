# Android Markdown rendering patch

The app pins `react-native-enriched-markdown` to **1.1.1**. Its GFM Android code
container skips all highlighting until a fence closes, and wide tables draw their
outer border at the end of the scrolling content rather than at the viewport.

This patch:

- Highlights the complete code-line prefix while the fence is pending. It caches
  that prefix and its language so additional tokens on the same line do not call
  the native highlighter again. When the fence closes, the whole code is colored.
  Only color spans change; source text, copying, font metrics and block height do
  not. The currently incomplete last line remains visible without coloring.
- Draws persistent edge fades and small chevrons only where horizontal content is
  hidden. The parent draws them in viewport coordinates, so they do not scroll
  away or intercept touches. Each indicator clears when its edge is reached.
- Clips rounded code panes and gives overflowing tables a fixed viewport outline.
  Short tables retain their existing content-width outline and alignment.

`npm ci` applies the patch through `postinstall`; both the Android prebuild script
and local build script apply it again safely. The installer checks the exact
package version and SHA-256 of every original/patched source, then uses the system
`patch` command with zero fuzz. It rejects mixed or drifted sources. For a package
upgrade, review and regenerate both the patch and its hash manifest; never just
relax the checks. `npm run patch:markdown` applies it manually, and
`node scripts/patch-enriched-markdown.cjs --check` verifies it without writing.

These changes are Android-specific. Keep screenshot and GPU-frame validation on
real pending fences, wide/narrow tables, and both ends of a horizontal swipe.

## Expo development menu and custom ReactHost

`expo-dev-menu` **58.0.13** reads `ReactHostImpl.reactHostDelegate` by reflection
even when the supplied host implements the `ReactHost` interface through a
wrapper. Taku's `TakuReactHost` therefore crashes the development menu on start.

`expo-dev-menu+58.0.13.patch` preserves the existing implementation for
`ReactHostImpl` and omits the optional JavaScript engine label for other hosts.
It retains their app metadata and source URL. It does not unwrap the host, alter
runtime ownership, or affect release code: the patched file is in `src/debug`.

`npm run patch:dev-menu` uses the same version and source-hash validation as the
Markdown patch installer. Postinstall and the Android build entry points apply
it idempotently; `node scripts/patch-dev-menu.cjs --check` only verifies it.
Revisit the patch when upgrading Expo. It addresses this crash, not all possible
development-launcher interactions with custom ReactHost implementations.
