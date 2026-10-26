# Codex development guidelines

- Lisp-first: write and maintain application behavior in Squint `.cljs` under `src/`.
- JavaScript is permitted for build tooling, tests invoking compiled modules, and the small service worker only.
- Preserve IndexedDB `fuel-mileage-local-v1`, `entries`, `id`, and legacy field names.
- Preserve `fuel-mileage-v1` JSON backup round trips and historical CSV format.
- Test every behavior change with `npm test` and `npm run build`.
- Never add private CSV/JSON historical data to the public repository or deployment.
- For UI changes, check mobile viewport, accessibility and offline behavior.
- Avoid unnecessary dependencies and server-side infrastructure.
- Use pull requests for substantial changes; include user-facing summaries and validation results.
