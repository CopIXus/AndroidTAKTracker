# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
