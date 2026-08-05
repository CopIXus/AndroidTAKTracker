# Remote configuration (Portal Pref packages)

AndroidTAKTracker applies **callsign**, **team**, and **role** from TAK Portal the same way ATAK does — without installing full mission content.

## Receive paths

1. **Device profile on connect** — `GET /Marti/api/device/profile/connection?clientUid=…` (8443/8446, mTLS).
2. **Portal Pref mission package** — inbound fileshare CoT for `Pref-*.zip` (`MANIFEST` + `certs/config.pref`), downloaded via `/Marti/sync/content?hash=…`, auto-import when `onReceiveImport=true`.
3. **QR / enroll preference URLs** and **manual SoftCert / Pref ZIP** import under Servers.

## Keys

| Purpose | Keys |
|---------|------|
| Callsign | `locationCallsign`, `callsign` → stored with `.att` suffix |
| Team | `locationTeam`, `team`, `teamColor` |
| Role | `atakRoleType`, `locationRole`, `role` |

Operators can disable apply under **Identity → Apply callsign/team from Portal**.
