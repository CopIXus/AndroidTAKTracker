# Releasing AndroidTAKTracker

Signed, installable builds come only from GitHub Actions ([`.github/workflows/release.yml`](../.github/workflows/release.yml)).
Nothing in this repo signs anything by itself: the keystore and its passwords live in GitHub
**Secrets**, never in git (`*.jks`, `*.keystore`, `ci/` are gitignored).

## What the pipeline produces

| Trigger | Release tag | `versionName` | `versionCode` | Assets |
|---|---|---|---|---|
| push to `main` | `build-0.1.<run>` | `0.1.<run>` | GitHub run number | `AndroidTAKTracker.apk` + `.sha256`, `AndroidTAKTracker.aab` + `.sha256` |
| push tag `vX.Y.Z` | `vX.Y.Z` ("AndroidTAKTracker X.Y.Z") | `X.Y.Z` | GitHub run number | same |

Every run: unit tests → `assembleRelease` + `bundleRelease` (R8 shrink, names kept) → `apksigner verify`
(v2 **and** v3 must be `true`) → `jarsigner -verify` on the AAB → refuse anything named `*unsigned*` →
GitHub Release. Tagged releases take their notes from the matching `## [X.Y.Z]` section of
`CHANGELOG.md` (falling back to `[Unreleased]`).

`versionCode` is the run number for both triggers, so it is monotonic across continuous and
tagged builds — Android and Play both require that for updates to install.

## One-time: signing key

Create the key **once**, off-repo, and keep it forever. Losing it means every existing install
must be uninstalled before the next update (sideload) or a Play key-reset request.

```bash
keytool -genkeypair -v \
  -keystore androidtaktracker-release.jks \
  -alias androidtaktracker \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=YourOrg AndroidTAKTracker, O=YourOrg, C=US"
```

Add four repository secrets (Settings → Secrets and variables → Actions):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | `base64 -w0 androidtaktracker-release.jks` |
| `ANDROID_KEYSTORE_PASSWORD` | keystore password |
| `ANDROID_KEY_ALIAS` | `androidtaktracker` (or whatever you chose) |
| `ANDROID_KEY_PASSWORD` | key password |

Store the `.jks` and passwords in your organisation's secret vault. Do not attach them to
issues, PRs or chat.

### Local release builds

`assembleRelease` / `bundleRelease` **fail** unless the four values are present as environment
variables or `-P` Gradle properties. For a throwaway smoke build:

```bash
./gradlew :app:assembleRelease -PallowUnsignedRelease=true   # produces app-release-unsigned.apk
./gradlew :app:assembleRelease -PdisableMinify=true          # bypass R8 while diagnosing a keep-rule
```

Never point `ANDROID_KEYSTORE_FILE` at a file inside the repository tree.

## Cutting a versioned release

1. Move the relevant `[Unreleased]` bullets in `CHANGELOG.md` under a new `## [X.Y.Z] - YYYY-MM-DD` heading (Keep a Changelog).
2. Commit to `main` (this also publishes a `build-0.1.<run>` continuous release — that is fine).
3. Tag and push:

   ```bash
   git tag vX.Y.Z
   git push origin vX.Y.Z
   ```

4. The Release workflow publishes `vX.Y.Z` with the APK, AAB, checksums and the CHANGELOG section.
   Download `mapping.txt` from the run's artifacts if you want de-obfuscated crash reports in Play
   (names are kept, so it is mostly line-number mapping).

The in-app updater reads `releases/latest`, so both continuous and tagged releases are published as
regular (non-pre-) releases.

## Sideload installs

- Android always shows the "install unknown apps" prompt for a non-Play install; that is the
  platform, not a defect in the APK. Play Protect may additionally scan an APK from an unknown
  developer. Only Play distribution removes those prompts.
- "App not installed" almost always means a build signed with a **different key** is already on
  the device (an old debug build uses the `.debug` package id and does not conflict). Uninstall the
  old app first.
- Verify a download before installing: `sha256sum -c AndroidTAKTracker.apk.sha256`.

## Google Play

Upload `AndroidTAKTracker.aab` (not the APK) to Play Console.

### Play App Signing

Play requires Play App Signing for new apps. Two workable setups:

- **Recommended:** let Play generate and hold the *app signing key*; enrol your existing release key
  as the **upload key**. Sideload APKs (signed with your key) and Play installs (signed with
  Google's key) will then be *different signatures* — a phone cannot update from one to the other
  without uninstalling. Decide up front which channel a given fleet uses.
- **Same key everywhere:** export your release key with the Play Encryption Public Key
  (`java -jar pepk.jar …`) and upload it as the app signing key. Sideload and Play builds then
  share a signature and can update each other. Keep the key extremely safe — it cannot be rotated
  without a Play support request.

### Declarations you will be asked for

| Manifest permission | Play Console item |
|---|---|
| `ACCESS_FINE_LOCATION`, `ACCESS_BACKGROUND_LOCATION`, `FOREGROUND_SERVICE_LOCATION` | Location permissions declaration: background location is the core feature (TAK PLI while the screen is off); prominent in-app disclosure exists (onboarding); privacy policy URL required; Google usually asks for a short screen recording of the disclosure and the Status screen |
| `FOREGROUND_SERVICE_LOCATION` | Foreground service type declaration — "location" |
| `PACKAGE_USAGE_STATS` | Sensitive permission: used only to detect a running ATAK so the tracker can defer presence (`FP-ATAK-DEFER`); the operator enables it manually via Usage access; describe this in the declaration |
| `REQUEST_INSTALL_PACKAGES` | **Policy risk.** Play prohibits apps that download and install executable code outside Play. The in-app GitHub updater does exactly that and is kept in this single build by product decision. Expect review pushback; mitigations are (a) leave *Automatically download and install* off by default (it is), (b) note that the updater is disabled under MDM, or (c) drop the permission and updater in a Play-specific build if review rejects it |
| `CAMERA` | QR enrollment only; not required (`uses-feature required=false`) |
| `RECEIVE_BOOT_COMPLETED`, `WAKE_LOCK` | Standard; no declaration |

Also prepare: Data safety form (precise location shared with the operator's own TAK server, not
with the developer; no ads; no analytics), content rating questionnaire, target audience (not
for children), and a privacy policy URL.

### Target SDK

Play requires new apps and updates to target a recent API level (currently `targetSdk = 35`, set in
[`app/build.gradle.kts`](../app/build.gradle.kts)). Bump it before each yearly Play deadline.

## Secrets hygiene

Follow [`.cursor/rules/no-tak-secrets.mdc`](../.cursor/rules/no-tak-secrets.mdc): no keystores,
passwords, TAK hosts, tokens or enrollment URLs in the repo, in workflow logs, or in release notes.
The workflow prints only the keystore file size, signer DN and package badging.
