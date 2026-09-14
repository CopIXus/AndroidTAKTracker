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
| `serversJson` | see below | One JSON value for multiple TAK servers, identity, and the settings lock |
| `settingsLock` | `LOCKCODE` | Grays out every local setting, including pause. Screens stay readable. Re-applied on every sync. Also accepted inside `serversJson`. |
| `settingsLockClear` | `true` | Clears the lock only when `settingsLock` is empty. A non-blank lock wins if both are set. |
| `allowInsecureTlsSoftAccept` | `true` | Lab CAs. Also a per-server flag inside `serversJson`. |
| `allowTrackingPause` | `false` | MDM-managed devices cannot pause or resume unless this is `true`. Unlocking the settings lock does not override it. |
| `requestBatteryExemption` | `true` | Prompt once in the foreground. Does **not** grant the exemption. |
| `preventSleepWhileTracking` | `true` | Default when MDM applies. Partial wake lock while the foreground service runs. |

Headwind substitutes `%NUMBER%` (device ID), `%DESCRIPTION%`, `%CUSTOM1%`–`%CUSTOM3%`, `%IMEI%`, `%PHONE%` on the server before the app sees the value. That includes strings inside `serversJson`.

If `callsign` is omitted, `mdmDeviceId`, or a leftover `%NUMBER%`, the app uses `HeadwindMDM` device ID from `queryConfig()`. Remote identity still appends `.att` so the map label does not collide with ATAK / WinTAKTracker.

`username` + `password` run the same Marti CSR enroll as Quick Connect. After a client cert is stored, later syncs do not re-enroll.

## Multiple servers (`serversJson`)

When `serversJson` is present and valid it is the managed server set. Flat `serverHost` is ignored. Profiles already enrolled for the same host, port, and protocol are updated, not duplicated. A repeated host:port:protocol in the JSON is kept once. The connection layer also refuses a second socket to the same stream if a duplicate profile exists. Local/QR servers that are not in the JSON stay. Invalid JSON falls back to the flat `serverHost` row.

A JSON array is servers only. An object can also set identity and the lock (those fields win over flat attributes when both are set). Callsign / team / role stay device-wide — one PLI identity, many TAK streams. The map label still gets `.att`.

Compact paste (fake values):

```json
{"settingsLock":"LOCKCODE","callsign":"%NUMBER%","team":"Cyan","role":"Team Member","allowTrackingPause":false,"servers":[{"host":"tak.example.com","port":8089,"protocol":"ssl","enrollPort":8446,"username":"USER","password":"TOKEN","name":"Example"}]}
```

Server fields: `host`, `port` (8089), `protocol` (`ssl` or `tcp`), `enrollPort` (8446), `username`, `password` or `token`, `name`, optional `allowInsecureTlsSoftAccept`.

## Settings lock

`settingsLock` is hashed with the same Diagnostics lock. Each MDM sync re-applies it, so a user who unlocked locally is locked again. Omitting the field does not clear an existing lock. `settingsLockClear=true` clears it only when no lock code is also set.

While locked, Status, Servers, Identity, GPS, Reporting, Mesh SA, Startup, and Diagnostics stay visible, but every control is grayed out — including pause and resume. Logs, status, and companion links stay usable. Unlock under Diagnostics with the lock code. A lock pushed by MDM cannot be cleared on the device.

If the lock is off, a value MDM actually set is labeled **Set by MDM** and cannot be changed. That includes the server list when `serversJson` or `serverHost` is set. Settings MDM did not send stay editable.

On an MDM-managed device, pause is off unless `allowTrackingPause` is `true`. A leftover operator pause is cleared on the next sync. `pause=true` is a remote pause the operator cannot clear.

## Battery / keep-alive

Headwind cannot set Android “App battery usage → Unrestricted” ([Q&A](https://qa.h-mdm.com/18523/app-battery-usage)). Grant-all-permissions does not cover it. There is no Headwind MDM Settings toggle for this.

The tracker prompts **once**, the first time it is in the foreground and not already exempt. It does not prompt on every reboot — a hidden app cannot start that dialog from `BOOT_COMPLETED` on Android 10+, and a repeating dialog would steal a kiosk screen.

Reporting does not depend on the Allow tap. Use:

- Applications: **Install**, **Run after install**, **Run at boot** (skip Run at boot if this app is already the kiosk content app)
- Configuration: **Autostart apps in foreground** (otherwise Headwind hides the app after boot and the one-time prompt cannot appear)
- Keep-alive / restart if the process dies

Status then shows “Not exempt — MDM keep-alive still running” instead of the Fix-battery prompt. Optional: leave the tracker visible for one enroll so an admin can tap Allow, then hide it. If Headwind blocks Android Settings behind an admin password, the one-tap system dialog may still appear; kiosk “block other activities” can swallow it.

## First-run UI

When the Headwind agent is present and location / background location / notifications are already granted, the permission wizard and callsign prompt are skipped. Battery-optimization exemption is not required to skip.

## Updates

In-app GitHub Releases update is disabled when MDM is present. Ship a new APK through Headwind **Applications**.
