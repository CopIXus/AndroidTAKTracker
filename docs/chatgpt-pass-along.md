# AndroidTAKTracker — ChatGPT pass-along brief

**Purpose of this file:** paste the whole document into ChatGPT (or a ChatGPT Project / Custom GPT) and ask it to **draft the next Cursor/cloud-agent prompts** for this repo. ChatGPT should not invent a new product. It should propose **small, implementable work items** that respect the rules below.

**Repo:** https://github.com/CopIXus/AndroidTAKTracker  
**Sibling:** https://github.com/CopIXus/WinTAKTracker  
**Org / author:** CopIX LLC  
**As of:** 2026-09-03 (main at `f8bdb4a`, latest GitHub Release `build-0.1.11`)

If you (ChatGPT) are reading this: first output a short **current-state recap** in your own words, then a **prioritized backlog of next-step prompts**. Each prompt must be copy-paste ready for a Cursor agent. Follow the prompt recipe in §12.

---

## 1. What this product is

AndroidTAKTracker is a **tracking-only TAK PLI client for Android**. It reports ATAK-shaped self-SA (Cursor on Target / CoT) so map clients can show this phone on the COP.

It is **not** a map, chat client, or common operating picture. Operators view tracks in:

- CloudTAK
- ATAK / ATAK-CIV
- WinTAK
- TAK Aware / iTAK

**Companion video (not in this app):** ICU VideoStreamer — https://github.com/jpat-12/TAK-PluginSuite-ICU_VideoStreamer/releases/tag/2.4.0  
Feature ID `FP-VIDEO` is **N/A** on Android. Do **not** reintroduce in-app video without an explicit product decision.

**Sibling relationship:** WinTAKTracker is the Windows tray/service equivalent. Shared **Feature IDs** live in `docs/feature-parity.md` in **both** repos. CI workflow `.github/workflows/parity-drift.yml` diffs Feature IDs against WinTAKTracker and fails if they diverge.

**License:** AndroidTAKTracker Free Application License 1.0 (`LICENSE`) — source-available, free to use, do not sell the app itself; paid install/support is allowed. Not OSI Open Source.

**Package ID:** `com.copix.androidtaktracker`  
Debug builds: `com.copix.androidtaktracker.debug`

---

## 2. Hard rules (never violate in a generated prompt)

### 2.1 No TAK / operational secrets

The tree is public. Never put any of the following in git, PRs, issues, docs, screenshots, samples, or ChatGPT-generated prompt text:

- Real TAK server hostnames, FQDNs, or IPs
- Ports tied to real infrastructure when combined with a real host
- Usernames, passwords, API tokens, enrollment tokens
- Callsigns or team names that identify real users/units
- Client certificates / CA / trust stores (`.p12`, `.pfx`, `.pem`, `.key`, SoftCert ZIPs)
- Live Portal / OpenTAK enroll URLs (`opentaktracker://…`, `tak://…` with real query params)
- Private CloudTAK URLs or SSO cookies
- GPS dumps, debug logs, or config exports containing any of the above

**Fakes only:** `tak.example.com`, `USER`, `TOKEN`, `CALLSIGN`  
**Samples:** `samples/enrollment.example.txt`, `samples/config.example.json`, `samples/headwind-application-settings.example.txt`  
**Rule of thumb:** if it would let someone join a real TAK network, it does not belong here.

Runtime secrets live in EncryptedSharedPreferences / Keystore / app private storage. Developer scratch: `local/`, `*.local.json`, `.env*` (gitignored). SDK path: `local.properties` (gitignored).

### 2.2 Identity and presence (must keep working)

AndroidTAKTracker is tracking-only. Map clients show PLI from CoT. TAK Server and Portal connection lists identify a TLS session from the **first usable self-SA (PLI)**.

**Callsign sources (precedence of ownership, not of string merge):**

1. **Operator callsign** — Identity settings / enroll QR (`callsign`).
2. **MDM / managed config** — Headwind or Android Enterprise; respect settings lock and managed-field badges.
3. **Device label (optional remarks only)** — never put a **bare device hostname/model** in CoT `remarks`. Optional remarks must be prefixed `Device: {name}`. Default `includeDeviceNameInRemarks` is **off**.

**Remote identity suffix:** Portal / MDM / enroll-applied callsigns get **`.att`** so they do not collide with ATAK or WinTAKTracker (`.wtt`). See `RemoteIdentityApply`.

**Presence announce:**

- After TAK connect: delay briefly, then announce presence so identity bindings can win the race before the first PLI. (`ReportingEngine.start()` currently delays 1.5s then ASAP.)
- When callsign / team / role changes: re-announce (`noteIdentityChanged()`).
- Self-SA must include ATAK-shaped fields: `contact@callsign` + `endpoint=*:-1:stcp`, `uid@Droid` (= callsign), `__group`, `takv platform=AndroidTAKTracker`, `precisionlocation`.
- Reply to server `t-x-c-t` with `t-x-c-t-r`. Also send client `t-x-c-t` keepalive when outbound is idle (~55s) so NAT/firewalls do not drop the stream.
- **Device UID in code today:** `ANDROIDTAKTRACKER-*` (from `ANDROID_ID` or UUID). Cursor rule `.cursor/rules/identity-presence.mdc` says `ANDROID-AndroidTAKTracker-*`. This is a known inconsistency — do not spoof `WINDOWS-*`, bare `ANDROID-*` ATAK UIDs, or `ATAK-CIV`. Any UID change must be treated as a **deliberate migration** (existing devices already have a stored UID).

**Client certs:** load `.p12` via `KeyStore.getInstance("PKCS12")` for mTLS. Prefer hardware-backed keys when available. Never embed certs or passphrases in the repo. Marti/SoftCert persist uses ATAK convention password `atakatak` under app `certs/`.

**Portal vs map:** CloudTAK/ATAK maps use `contact@callsign` as source of truth for “am I tracking?”. Portal Connected Users may omit raw SSL clients or lag on first-PLI identity — often Portal-side.

**ATAK coexistence (`FP-ATAK-DEFER`):** when a full map client should own presence on the same device, pause/defer this app’s PLI. Modes: `Off` | `WhenRunning` (default) | `WhenHeardOnMesh`. Detects `com.atakmap.app.civ` and `com.atakmap.app`.

### 2.3 Feature parity

- Do not rename or remove a Feature ID without updating **both** AndroidTAKTracker and WinTAKTracker.
- New cross-product capabilities need a new Feature ID row in both matrices.
- Status vocabulary: **Yes / Partial / Planned / N/A** only.
- `FP-VIDEO` stays N/A on Android.

### 2.4 Out of scope (v1, do not prompt unless product owner asks)

- In-app map / COP / chat / drawing
- Displaying other Mesh SA contacts
- TAK Protocol protobuf mesh / QUIC (first ship is XML CoT + SSL/TCP + Mesh UDP)
- Simulated GPS
- Selling the app / changing the license without an explicit request

---

## 3. Architecture (how the code is laid out)

Kotlin + Jetpack Compose. Two Gradle modules:

| Module | Role |
|--------|------|
| `:core` | Pure-ish library: config, CoT builder, TAK stream client, enrollment parsers, reporting, portal Pref packages, MDM apply, mesh SA XML send, updates HTTP, redacted logger. `compileSdk 34`, minSdk 26. JUnit4 unit tests. |
| `:app` | Android UI, foreground service, fused GPS, Headwind AIDL bind, ATAK detection, EncryptedSharedPreferences, CameraX QR, WorkManager watchdog, boot receiver. `compileSdk 35`, minSdk 26, targetSdk 35. |

**Runtime hub:** `app/.../host/TrackingHost.kt` — singleton that owns `ConfigStore`, `TakConnectionManager`, `ReportingEngine`, `FusedGpsRepository`, `MeshSaBroadcaster`, `DeviceProfileSync`, `EnrollmentService`, `MdmConfigBridge`, `GitHubUpdateService`, settings lock, pause.

**UI:** `MainActivity` + Material3 navigation drawer. Sections in `ui/Nav.kt` → `ui/screens/SettingsScreens.kt` (large single file): Status, Servers, Identity, GPS, Reporting, Mesh SA, Companion apps, Startup, Diagnostics, Updates, About. First-run: `onboarding/OnboardingScreen.kt`. QR: `ui/QrScanScreen.kt`.

**Background:**

- `TrackingForegroundService` — location FGS, sticky, notification “Tracking · {callsign}”
- `BootCompletedReceiver` — `BOOT_COMPLETED` / `LOCKED_BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`
- `ServiceWatchdogWorker` — WorkManager
- `preventSleepWhileTracking` optional partial wake lock (applied live)

**Config:** JSON `AppConfig` via `ConfigStore` under app files `store/`. Secrets never in `AppConfig` — `EncryptedSecretStore` / `AndroidEncryptedSecretStore`. Config version `CURRENT_VERSION = 3`.

**Deep links:** `tak://` and `opentaktracker://` on `MainActivity`.

**Build:** Android Gradle Plugin 8.5.2, Kotlin 2.0.20, Gradle 8.11.1, JDK 17, Compose BOM 2024.10.01. BouncyCastle 1.78.1 for PKCS#10 CSR (Android’s `BC` provider is a stub — enrollment **must** replace it before CSR signing). Play Services Location, CameraX + ML Kit barcode, security-crypto 1.1.0-alpha06, OkHttp 4.12.

**CI:**

- `.github/workflows/android.yml` — unit tests + `assembleDebug` on PR/push to main
- `.github/workflows/release.yml` — signed `AndroidTAKTracker.apk` + `.sha256`, tag `build-0.1.<run_number>` (needs keystore secrets)
- `.github/workflows/parity-drift.yml` — weekly Feature ID compare vs WinTAKTracker

**Release note:** unsigned/debug APKs cannot be upgraded to signed release APKs — uninstall first. MDM fleets should ship APKs through Headwind, not the in-app updater (updater is disabled when MDM is present).

---

## 4. Feature matrix (shipping)

From `docs/feature-parity.md` — all Yes except VIDEO N/A:

| ID | What it means on Android |
|----|--------------------------|
| FP-TAK-TLS | TLS/mTLS CoT stream, reconnect backoff, fail2ban circuit breaker |
| FP-REPORTING-ASAP | Dynamic/Constant rates; ASAP on connect, identity change, altitude jump >50m, speed jump >7 mph; 5s floor |
| FP-GPS-FUSED | FusedLocationProvider; last-fix hold; course offset |
| FP-GPS-IP-FALLBACK | ipwho.is delayed fallback; default **off** for new configs |
| FP-MESH-SA | UDP 239.2.3.1:6969; Always or OnlyWhenDisconnected (default) |
| FP-ENROLL-QR | QR + `tak://` / `opentaktracker://` / iTAK CSV; Marti CSR :8446; SoftCert ZIP persist `atakatak` |
| FP-ENROLL-MANUAL | Typed host/port/username/password → same Marti CSR as Quick Connect |
| FP-TAK-KEEPALIVE | Client `t-x-c-t` when outbound idle |
| FP-MDM-HEADWIND | Headwind Application Settings bind (`com.hmdm.action.Connect`) + Android Enterprise `app_restrictions.xml` |
| FP-PORTAL-CALLSIGN | Device-profile GET + Pref-*.zip fileshare CoT / Marti sync; `.att` suffix |
| FP-ATAK-DEFER | Suppress PLI when ATAK running or heard on mesh |
| FP-BOOT-START | Start tracking after boot |
| FP-UPDATES-CHANGELOG | GitHub Releases check + CHANGELOG notes + manual Download & install (SHA256). Auto-download toggle **removed** until a background worker exists |
| FP-SETTINGS-LOCK | Password gate; stored as unsalted SHA-256 hex of the password (WinTAK uses salt — Android is weaker) |
| FP-PAUSE | Mute outbound CoT without quitting; MDM `pause` key can remote-pause |
| FP-DIAGNOSTICS | Log level, TLS soft-accept, status JSON export, in-app log viewer/share |
| FP-VIDEO | N/A — Companion apps section links ICU VideoStreamer |

**Remote config precedence:** **MDM > Portal > local/QR**. Documented in `docs/headwind-mdm.md` and `docs/remote-config.md`.

**Headwind keys (fakes only):** `serverHost`, `serverPort`, `serverProtocol`, `enrollPort`, `username`, `password` (alias `token`), `callsign` (`%NUMBER%` / `mdmDeviceId`), `team`, `role`, `enrollUrl`, plus `reportingStrategy`, `deferToAtak`, `pause`. Skip first-run wizard when Headwind is present **and** location / background location / notifications are already granted.

---

## 5. Key source map (for prompt authors)

Point agents at these files; do not dump the whole tree.

| Area | Path |
|------|------|
| Config schema | `core/.../config/AppConfig.kt` |
| CoT XML | `core/.../cot/CotEventBuilder.kt` |
| Reporting loop | `core/.../reporting/ReportingEngine.kt`, `ReportingRate.kt` |
| TAK sockets | `core/.../tak/CotStreamClient.kt`, `TakConnectionManager.kt` |
| Enrollment | `core/.../tak/EnrollmentUriParser.kt`, `EnrollmentService.kt`, `MartiCertMaterial.kt`, `SoftCertImporter.kt` |
| Portal prefs | `core/.../portal/DeviceProfileSync.kt`, `PreferencePackageParser.kt`, `FileShareCotParser.kt` |
| Identity | `core/.../identity/IdentityResolver.kt`, `RemoteIdentityApply.kt` |
| MDM apply | `core/.../mdm/MdmSettingsApply.kt` + `app/.../mdm/MdmConfigBridge.kt`, `HeadwindAgentClient.kt` |
| Host / service | `app/.../host/TrackingHost.kt`, `service/TrackingForegroundService.kt` |
| GPS | `app/.../gps/FusedGpsRepository.kt`, `core/.../gps/NetworkIpGeolocation.kt` |
| ATAK defer | `app/.../atak/AtakCoexistence.kt` |
| Mesh | `core/.../mesh/MeshSaBroadcaster.kt`, `app/.../mesh/MeshMulticastSupport.kt` |
| UI | `app/.../ui/screens/SettingsScreens.kt`, `MainActivity.kt`, `onboarding/` |
| Restrictions | `app/src/main/res/xml/app_restrictions.xml` |
| Tests | `core/src/test/...`, `app/src/test/...` (no instrumented UI tests yet) |
| Cursor rules | `.cursor/rules/no-tak-secrets.mdc`, `feature-parity.mdc`, `identity-presence.mdc` |

---

## 6. Recent history (what landed; do not re-implement)

Newest first on `main`:

1. **Bind Headwind MDM Application Settings** — `com.hmdm.action.Connect`, `password` aliases `token`, Marti via `enrollManual`, callsign from `%NUMBER%` / device ID, skip wizard when MDM granted tracking perms. PR #2 merged.
2. **Manual credential enrollment** + live wake-lock / notification refresh; dead auto-download-updates toggle removed.
3. **Harden Portal Pref packages** — download retry (Portal deletes soon after send), scoped fileshare parsing, ATAK role allow-list, `atakRoleType` on SoftCert.
4. **Stop TAK connection flapping** — 2s debounced network reload, 55s keepalive, reconnect-race guards, idempotent `TrackingHost.start()`.
5. **Portal Pref-*.zip like ATAK** — MANIFEST `onReceiveImport`, Marti sync download, `.att`.
6. **Fix Marti QR enroll `OperatorCreationException`** — replace Android stub BC provider before CSR signing.
7. **Persist Marti/SoftCert PKCS12** like ATAK; surface `lastError`; in-app logs.
8. **Callsign Save stuck UI** — deep-copy config before mutate so StateFlow emits; runtime permission dialogs.
9. **Sign continuous-release APKs.**
10. **Close plan gaps** — Dynamic rate, CSR, SoftCert UI, settings lock, updates, MDM badges, CI tests.

`CHANGELOG.md` `[Unreleased]` has two `### Added` sections (cosmetic cleanup candidate).

---

## 7. Open work already in GitHub

| Item | State | Notes |
|------|--------|--------|
| [PR #1](https://github.com/CopIXus/AndroidTAKTracker/pull/1) Motion-adaptive CoT and GPS to save battery | **OPEN** (branch `cursor/motion-adaptive-cot-battery-57e4`) | Stationary keepalive ~3 min; walking 30–45s; driving 5s; CoT `stale` ≥ 90s and longer than interval; fused GPS steps to balanced when still; GNSS off while paused / ATAK-deferred; low battery stretches Dynamic; “Adapt GPS to motion” setting. **Do not duplicate this work** — review/merge or continue that branch. |
| Issues | none listed | Use PRs / Cursor agents rather than assuming a GitHub issue tracker backlog. |

WinTAKTracker **planned (post-beta)** that may eventually need an Android Feature ID if implemented on both:

- Portal Connected Users parity without reconnect (often Portal-side)
- Richer inbound CoT (chat / data packages remain out of scope for v1)

---

## 8. Known gaps and prompt-worthy follow-ups

These are real, repo-evidence gaps — good seeds for next prompts. Rank by operator impact, then hygiene.

### High — behavior / fleet

1. **Finish or merge PR #1** (battery / motion-adaptive reporting) without dropping map icons. Verify `stale` vs send interval, ASAP on first move, Constant strategy unchanged.
2. **OEM battery kill / Doze** — Samsung, Xiaomi, etc. still stop FGS. Onboarding already asks unknown-apps + battery exemption, but reliability on kiosk phones is the product. Prompt: detect exemption, persistent Status warning, Headwind recipe note — no fake GPS.
3. **Device UID prefix inconsistency** (`ANDROIDTAKTRACKER-*` vs rule `ANDROID-AndroidTAKTracker-*`). Prompt must specify **migration**: keep existing stored UIDs; only new installs or an opt-in reset. Changing UID looks like a new client on TAK Server.
4. **Hardware-backed client keys** — rule says prefer when available; today PKCS12 files in `certs/` + EncryptedSharedPreferences for passphrases. Import into Android Keystore without breaking Marti re-enroll / SoftCert / `atakatak` ATAK interop.
5. **Auto-update worker** — check exists; Download & install is manual; auto-download toggle was removed on purpose. MDM must stay on Headwind APK shipping.

### Medium — parity / hardening

6. **Settings lock salt** — match WinTAK SHA-256 + salt; migrate unsalted hashes on unlock.
7. **Corrupt `config.json` quarantine** — WinTAK quarantines instead of overwriting; Android `ConfigStore` should not silent-clobber.
8. **Secret-scan CI** (Gitleaks) like WinTAK — block accidental hosts/tokens.
9. **`samples/config.example.json`** still has `"sourcePriority": "Windows"` and WinTAK-ish `computerIdentity` / `stationaryIntervalSeconds` keys that do not match `AppConfig` (`FusedOnly` / `deviceIdentity` / `reliableStationarySeconds`). Fix samples so they deserialize.
10. **Test server from UI** — `TakConnectionManager.testServer()` exists; confirm Servers cards expose Test + lastError (partially done).
11. **GNSS stop while paused** — claimed in PR #1; if that PR is not merged, main still requests high-accuracy fused on a 2s interval even when paused (ReportingEngine skips send, GPS may still run).
12. **Mesh NIC picker** — WinTAK has optional interface selection; Android `networkInterface` exists on config (`Auto`) but UX may be incomplete on multi-homed / VPN devices.
13. **Docs sibling gap** — WinTAK has `SECURITY.md`, `CONTRIBUTING.md`, `docs/fail2ban.md`, `docs/managed-devices.md`, `docs/beta-testing.md`. Android has README + three docs. Useful, but keep secrets out of examples.
14. **README architecture / screenshots** — WinTAK has mermaid + illustrative Status shot (Plymouth Rock fake coords). Android README is short.

### Lower — engineering hygiene

15. **No instrumented / Compose UI tests** — only JVM unit tests. High value: enrollment parser (already tested), `OnboardingPolicy`, `MdmSettingsApply`, `CotEventBuilder`, Pref ZIP parser. Next: ReportingEngine interval math; UID/remarks rules.
16. **`SettingsScreens.kt` is ~800 lines** — split by section without changing behavior.
17. **`:core` compileSdk 34 vs `:app` 35.**
18. **Release minify is off** (`isMinifyEnabled = false`).
19. **CHANGELOG duplicate Added headings**; keep Keep-a-Changelog format.
20. **ProGuard / 16 KB page size / Android 15+ edge-to-edge** polish.
21. **Accessibility / TalkBack** on Status + Servers (enrollment is operationally critical).
22. **Tablet / landscape drawer** — `MainActivity` already branches on width; Status tiles are still sparse vs WinTAK dashboard.

### Product decisions (do not implement in a prompt until the owner says so)

- In-app video / `__video` CoT (explicitly N/A)
- Play Store listing vs GitHub + MDM only
- Portal Connected Users workarounds that spoof ATAK UIDs or `ATAK-CIV` (forbidden)
- CloudTAK URL field (removed on WinTAK; `ServerProfile.cloudTakUrl` still exists on Android)

---

## 9. How config and CoT actually behave (so prompts do not “fix” working design)

**Identity resolution:** user callsign if set; else device identity; else device model / `ANDROID-TRACKER`. Phone is optional on `contact@phone`.

**Reporting Dynamic (main, today):**  
- speed &lt; 1 mph → stationary interval (reliable default 180s, unreliable 30s), floor 5s  
- 1–30 mph → interpolate maxMove → min  
- ≥ 30 mph → min (default 5s)  
`stale` = 2× interval + 15s. Constant uses `constantIntervalSeconds` (default 10, floor 5).

**GPS:** `FusedOnly` (default) | `FusedThenNetwork` | `NetworkOnly`. Hold last fix `lastFixHoldSeconds` (default 30). IP fallback off by default. CoT `how`/`precisionlocation` distinguish GPS vs estimated (held / IP).

**Mesh:** send Always, or only when no TAK server is connected. Foreign ATAK self-SA on mesh can trigger defer (`WhenHeardOnMesh`).

**Fail2ban:** CotStreamClient circuit-breaker stops reconnect well under infra-TAK’s ~20 TLS fails / 5 min. Operator must toggle Connect or Test after fixing certs. Do not “fix” this by reconnecting harder.

**BouncyCastle:** Android ships a stub `BC` provider. CSR enrollment must keep replacing it. Do not remove that workaround.

**StateFlow saves:** mutate a **deep copy** of `AppConfig` then assign; in-place mutation does not emit and stuck the callsign Save button.

---

## 10. Tests and how to verify

```bat
gradlew.bat :core:testDebugUnitTest
gradlew.bat :app:testDebugUnitTest
gradlew.bat :app:assembleDebug
```

Existing tests: `CotEventBuilderTest`, `EnrollmentUriParserTest`, `MartiCertMaterialTest`, `PreferencePackageParserTest`, `MdmSettingsApplyTest`, `ChangelogExtractTest`, `OnboardingPolicyTest`, `MeshMulticastSupportTest`.

There is **no emulator in typical Cursor cloud agents**. Prompts should prefer unit tests for CoT/enrollment/MDM/reporting math, and say so. Never require live TAK servers.

---

## 11. Prompt recipe (ChatGPT must follow this when writing next-step prompts)

Each generated prompt is a **single Cursor agent task**. Prefer one PR-sized change.

**Required sections in every prompt you output:**

1. **Title** — one line, outcome-oriented.
2. **Goal** — what shipping behavior changes, in operator language.
3. **Non-goals** — VIDEO, secrets, Feature ID renames, spoofing ATAK UIDs, live TAK hosts.
4. **Touch list** — files/modules likely involved (from §5).
5. **Invariants** — quote the relevant bits of §2 (presence CoT shape, `.att`, MDM precedence, fail2ban, BC stub).
6. **Parity** — if the change is cross-product, add/keep a Feature ID in **both** repos; if Android-only (MDM, ATAK-defer), say so.
7. **Tests** — which unit tests to add/extend; no live server; fake hosts only.
8. **Docs** — CHANGELOG `[Unreleased]`; README/parity/headwind only if user-facing.
9. **Acceptance** — 5–10 bullets an agent can check off.

**Style constraints to include in the prompt footer:**

- Match existing Kotlin style; no drive-by refactors.
- Do not commit `local.properties`, keystores, `.p12`, or real enroll URLs.
- Deep-copy `AppConfig` before mutate.
- Keep `takv platform=AndroidTAKTracker`.
- If adding a setting, persist it on `AppConfig` with a sensible default that does not surprise existing installs.

**Bad prompts to refuse to generate:**

- “Make it look like ATAK so Portal lists us” by cloning ATAK UID / platform strings
- “Add video streaming to the APK”
- “Here’s our real enroll QR, wire it as the default server”
- “Reconnect every second until TLS works”
- “Put the device hostname in remarks so Portal shows it”

---

## 12. Starter prompts (refine these; do not treat as already done)

ChatGPT should rewrite these to be tighter, then add new ones from §8. They are **starting points**, not a commitment that the code is missing all of this.

### Prompt A — Merge-ready review of motion-adaptive PR

> Review and finish GitHub PR #1 (`cursor/motion-adaptive-cot-battery-57e4`) on CopIXus/AndroidTAKTracker. Goal: save GNSS battery without letting CloudTAK/ATAK icons go stale. Do not duplicate the branch; continue it. Keep Constant strategy unchanged. Keep ASAP on identity/connect/altitude/speed-jump. CoT `stale` must stay longer than the send interval and at least ~90s on the slow stationary path. Add/extend unit tests for interval and stale math using fake speeds. No real TAK hosts. Update CHANGELOG and docs/feature-parity.md only if behavior needs a new Feature ID (likely it does not — this is FP-REPORTING-ASAP / FP-GPS-FUSED). If PR #1 is already merged when you start, stop and report that.

### Prompt B — Fix sample config to match AppConfig

> Make `samples/config.example.json` a valid fictional `AppConfig` for AndroidTAKTracker. Today it still uses WinTAK field names (`sourcePriority: Windows`, `computerIdentity`, `stationaryIntervalSeconds`). Use `tak.example.com` / `USER` / `CALLSIGN` only. Add a unit test or a documented round-trip if cheap. Do not change runtime defaults unless a sample key was lying about a real default.

### Prompt C — Docs: fail2ban + managed presence (Android)

> Add `docs/fail2ban.md` and `docs/managed-devices.md` modeled on WinTAKTracker’s docs but for this Android client: circuit breaker, Test vs Connect, first PLI identity, `.att`, ATAK-DEFER, device UID prefix as implemented in code. Fictional hosts only. Link from README. Do not change connection code unless you find a docs/code contradiction worth a small fix (call it out).

### Prompt D — Settings lock salt migration

> Upgrade settings-lock storage from unsalted SHA-256 hex to salted hash compatible with a documented scheme (document it). On successful unlock of a legacy unsalted value, re-hash with salt. Do not log the password. Keep EncryptedSharedPreferences. Tests for hash verify + migration using fake passwords.

### Prompt E — Config load quarantine

> If `config.json` fails to parse, quarantine the file (timestamped name) and start from defaults instead of overwriting the corrupt file in place. Surface a Diagnostics / Status message. Unit test with truncated JSON. No secrets in fixtures.

### Prompt F — Gitleaks / secret-scan CI

> Add a GitHub Actions secret-scan job similar in spirit to WinTAKTracker, configured so `samples/*.example.*` fake hosts are allowed and `*.p12` / keystores / `.env` are not. Do not add real credentials to test the scanner.

---

## 13. What ChatGPT should output when given this file

When the user pastes this brief and says “help me develop next-step prompts”, produce:

1. **Five to twelve ranked prompts** in the §11 recipe format.
2. A **do-not-do list** for the next month (VIDEO, UID spoof, secrets, fail2ban hammering).
3. A **suggested sequence** (e.g. land PR #1 → sample config → lock/quarantine → OEM battery UX → Keystore).
4. Optional **clarifying questions** only if a prompt would be unsafe without a product decision (UID migration, Play Store, video). Prefer choosing the conservative default (do not migrate UIDs; do not add video).

If the user instead asks for a **single** prompt, pick the highest-impact item that is not already an open PR, unless they name a theme (MDM, battery, docs, enrollment).

---

## 14. One-paragraph elevator (for ChatGPT system context)

AndroidTAKTracker is CopIX LLC’s public Android sibling of WinTAKTracker: a Kotlin/Compose tracking-only TAK client that publishes ATAK-shaped self-SA over TLS/mTLS or TCP, with Marti CSR / SoftCert / QR enrollment, Headwind MDM, Portal `.att` identity, Mesh SA, boot-start FGS, and ATAK-defer so phones do not double-track. Video is a companion app. The repo must never contain real TAK infrastructure. Next work is hardening, battery, MDM fleet reliability, and docs/tests — not a COP and not in-app video.
