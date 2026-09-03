# Headwind MDM — AndroidTAKTracker

Push TAK connection settings and a per-device callsign from [Headwind MDM](https://h-mdm.com/) without opening the tracker UI.

Headwind **Application Settings** are not Android Enterprise managed configuration. The app binds the Headwind agent (`com.hmdm.action.Connect`) and reads keys with `queryAppPreference`. Android Enterprise EMMs (Intune / Android Management API) still use `app_restrictions.xml` / `RestrictionsManager` with the same key names.

Precedence: **MDM > Portal > local/QR**.

Package ID: `com.copix.androidtaktracker` (debug builds: `com.copix.androidtaktracker.debug`).

## Device Owner (needed for silent install)

QR-enroll Headwind as Device Owner (factory-reset phone). Without Device Owner the user must tap through APK install and runtime permissions.

## Install the tracker

1. Headwind **Applications** → Add → upload `AndroidTAKTracker.apk`.
2. Assign to the configuration as **Install**.
3. Enable **Run after install** and **Run at boot**.
4. On the configuration, enable **Autostart apps in foreground**.

Headwind Device Owner grants ordinary runtime permissions (location, notifications) when it installs the APK.

## Application settings

Configuration → **Application settings** (or a per-device override). Application = `com.copix.androidtaktracker`.

Use obvious fakes in examples — never put a real TAK host, user, or password in this repo.

| Attribute | Example | Notes |
|---|---|---|
| `serverHost` | `tak.example.com` | TAK Server hostname |
| `serverPort` | `8089` | CoT stream port |
| `serverProtocol` | `ssl` | `ssl` or `tcp` |
| `enrollPort` | `8446` | Marti CSR port (optional; default 8446) |
| `username` | `USER` | TAK enroll user (HTTP Basic) |
| `password` | `TOKEN` | TAK enroll password. `token` is accepted as an alias. |
| `callsign` | `%NUMBER%` | Headwind device ID. Or `mdmDeviceId`, or a fixed string. |
| `team` | `Cyan` | Optional ATAK team color |
| `role` | `Team Member` | Optional ATAK role |
| `enrollUrl` | `opentaktracker://enroll?host=…` | Optional single-field enroll (avoid embedding secrets) |

Headwind substitutes `%NUMBER%` (device ID), `%DESCRIPTION%`, `%CUSTOM1%`–`%CUSTOM3%`, `%IMEI%`, `%PHONE%` on the server before the app sees the value.

If `callsign` is omitted, `mdmDeviceId`, or a leftover `%NUMBER%`, the app uses `HeadwindMDM` device ID from `queryConfig()`. Remote identity still appends `.att` so the map label does not collide with ATAK / WinTAKTracker.

`username` + `password` run the same Marti CSR enroll as Quick Connect. After a client cert is stored, later syncs do not re-enroll.

## First-run UI

When the Headwind agent is present and location / background location / notifications are already granted, the permission wizard and callsign prompt are skipped. Battery-optimization exemption is not required to skip.

## Updates

In-app GitHub Releases update is disabled when MDM is present. Ship a new APK through Headwind **Applications**.
