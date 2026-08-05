# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed

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
