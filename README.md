# Fuel & Mileage Log

An offline-first fuel and mileage log optimized for Android. Record odometer mileage, trip miles, gallons, and cost; timestamps are recorded on save. Browse and edit local history, and import/export your records.

## Privacy

This is a **private GitHub repository** containing **application code only**. Do not commit fuel purchase records, spreadsheet exports, or JSON/CSV backups. The application stores personal data locally in browser IndexedDB and does not synchronize with Google Sheets or GitHub. Back up data regularly.

**Important:** GitHub Pages normally publishes a **public website**, even when its source repository is private. Publishing Pages from a private personal repository requires GitHub Pro or another eligible plan. Private Pages access controls require an eligible GitHub Enterprise Cloud organization. A public static shell contains no user data, but anyone with the URL may load the app.

## Deploy

1. From this repository, open **Settings → Pages**.
2. If eligible, choose **Build and deployment → Deploy from a branch**.
3. Choose **master** and **/(root)**, then Save. The project's expected URL is `https://jkmacy.github.io/fuel-mileage-log/`, but it is not live until GitHub reports successful deployment.
4. Open the URL in Chrome on Android. From its menu choose **Install app** or **Add to Home screen**.
5. In the app select **Data → Import CSV or JSON** and import your history **directly from your phone**; never upload your CSV to GitHub.

If GitHub Pages is unavailable for this private repository because of your plan, keep the repository private and publish only the app files using a separate public repository or static host such as Neocities.

## App files

- `index.html` — interface and local data storage
- `manifest.json` and icons — installable app metadata
- `sw.js` — offline shell caching
- `.nojekyll` — serve the static files without Jekyll transformations
- `.gitignore` — excludes common personal exports, but is not a substitute for verifying commits

## Limitations

Local browser storage is tied to the website origin; clearing site data or uninstalling can remove records. Changing host/domain requires exporting and reimporting. Imported historical rows without original timestamps retain dates only. No automatic Google Sheets synchronization.
