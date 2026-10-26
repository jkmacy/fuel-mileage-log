# Fuel & Mileage Log

An offline-first phone-friendly Progressive Web App built **in Squint**, a lightweight
ClojureScript dialect. GitHub Pages serves compiled JavaScript; the application logic is
maintained in `.cljs` source files under `src/`.

## Develop

Requirements: Node.js 22+, npm.

```sh
npm install
npm run dev     # compile Squint, serve locally using Vite
npm test        # compile Squint and run regression tests
npm run build   # build dist/ for GitHub Pages
```

Source files:

- `src/fuel_log/model.cljs`: validation, strict dates, CSV and JSON import, export, deduplication
- `src/fuel_log/app.cljs`: forms, history, IndexedDB, and browser interaction
- `index.html`: markup and presentation (no application JavaScript)
- `sw.js`: small platform-required service worker
- `test/model.test.mjs`: Node test harness calling compiled Squint functions
- `.github/workflows/test.yml`: compile, tests and production build on pushes/PRs
- `.github/workflows/pages.yml`: builds and deploys `dist/` via GitHub Actions

## Deploy

The repository uses **master** as its default branch. In **Settings → Pages**, set
**Build and deployment → Source → GitHub Actions**. The `pages.yml` workflow will
build and deploy the app on each push to master once Pages is configured.

Expected website: <https://jkmacy.github.io/fuel-mileage-log/>.

**If GitHub Pages is currently configured to deploy from the `master` branch, change it to
GitHub Actions before using the migrated site.** The branch root contains Squint source,
not the production compiled application.

## Data compatibility

- **IndexedDB name**: `fuel-mileage-local-v1`
- **Store name**: `entries`, keyed by `id`, indexed by `date`
- **JSON backup marker**: `fuel-mileage-v1`
- **Date display**: `YYYY-MM-DD`, with `HH:mm` where a genuine time is known
- **Entry fields**: mileage, trip, gallons, cost. Submission time is automatic.

These values match the original JavaScript version, so returning to the **same website
origin and path** should retain existing device records. Still **export a JSON backup**
before upgrading if you have already entered or imported data. Clearing browser storage
or switching domains can erase or separate data.

## Privacy

No account, analytics, telemetry, cloud backend, or automatic Google Sheets sync.
Imported fuel-history CSV and JSON exports are **not** part of this repository and must
never be checked in. Run the import locally on the phone from the app's Data tab.

This public repository makes application *code* public, **not locally stored records**.
