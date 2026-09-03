# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- Dynamic CoT is driven by a displacement-anchored motion classifier: stationary (~180 s keepalive) after 30 s without relocating 20 m (60 s after driving), walking ~30–45 s, driving 5 s; GPS jitter while parked no longer looks like motion
- Stationary → moving is the single displacement-driven ASAP trigger (immediate map refresh); identity / connect / altitude / speed-jump ASAP unchanged
- Fused GPS power follows the same motion state: low/balanced while still or walking, high accuracy while driving or the moment we relocate — even when the fix carries no speed (can be turned off under GPS)
- GNSS stops while tracking is paused or ATAK owns presence; resuming re-acquires on high accuracy
- Low battery stretches Dynamic intervals (1.5× ≤ 20 %, 2× ≤ 15 %), capped at 300 s so a keepalive gap never exceeds 5 minutes; charging restores the normal cadence
- Dynamic CoT stale is at least 90 s and always longer than the send interval so a slowed report cannot drop the icon; Constant strategy stale is unchanged (2 × interval + 15 s)
- Status is now the operational dashboard: TRACKING / PAUSED / ATAK DEFERRED / WAITING FOR LOCATION / TAK DISCONNECTED card with callsign and summary, Tracking Intelligence (motion, GPS power, cadence, next report, last location / last TAK report ages), explained connection state (no network, reconnecting, fail2ban guard tripped, waiting for first PLI), location quality and age, battery and optimization-exemption warning with a Fix action; raw coordinates moved to Diagnostics

### Added

- `ReportingSnapshot` / `DeviceState` runtime state and a pure `TrackingStatusMapper` (core) so the UI never reconstructs engine behaviour
- Headwind MDM Application Settings bind (`com.hmdm.action.Connect`): `password` aliases `token`, Marti enroll via `enrollManual`, callsign from `%NUMBER%` / `mdmDeviceId` / device ID; skip first-run wizard when MDM granted tracking permissions
- Portal Pref-*.zip preference packages (MANIFEST + certs/config.pref) via fileshare CoT / Marti sync download, matching ATAK onReceiveImport

### Fixed

- QR/Marti enroll no longer fails with `OperatorCreationException` — replace Android’s stub `BC` Security provider before CSR signing
- Marti CSR enrollment now re-pairs the local private key, persists client + CA trust PKCS12 under app `certs/` with password `atakatak` (ATAK / WinTAKTracker SoftCert model), and no longer reports success without a usable certificate
- SoftCert ZIP import requires a client `.p12`, defaults password to `atakatak`, persists trust password, and verifies KeyStore load before saving the profile
- Server cards show connection `lastError`; Diagnostics shows/shares recent redacted logs; Identity uses a single My callsign

### Added

- Initial AndroidTAKTracker public skeleton (Kotlin + Jetpack Compose)
- Foreground tracking service with boot-start and WorkManager watchdog
- TAK TLS/TCP CoT client with reconnect backoff and fail2ban guard
- Reporting engine (Dynamic/Constant) with ASAP triggers and per-server identity overrides
- Fused location + optional IP geolocation fallback (ipwho.is)
- Mesh SA UDP multicast
- Enrollment: `opentaktracker://`, `tak://` enroll/preference/import, iTAK CSV, SoftCert ZIP, QR scanner
- Headwind MDM / Android Enterprise managed configurations with managed-field badges
- TAK Portal device-profile sync with `.att` callsign suffix
- ATAK coexistence: defer PLI when ATAK is running or heard on mesh
- Settings UI parity sections (no Video — Companion links ICU VideoStreamer)
- Updates check with CHANGELOG.md section display
- Feature parity table shared with WinTAKTracker
