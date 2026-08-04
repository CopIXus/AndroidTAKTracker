# AndroidTAKTracker

Tracking-only TAK PLI client for Android, by **CopIX LLC**. Sibling of [WinTAKTracker](https://github.com/CopIXus/WinTAKTracker).

## What it is

- Reports ATAK-shaped self-SA (PLI) to TAK Server over TLS/mTLS or cleartext TCP
- Enrollment via QR / Portal deep links (`tak://`, `opentaktracker://`), Marti CSR, SoftCert ZIP
- Headwind MDM + Android Enterprise managed configuration (precedence: MDM > Portal > local/QR)
- Boot-start foreground service, Mesh SA multicast, Portal callsign push (`.att` suffix)
- Optional **Defer to ATAK** so phone + ATAK do not double-publish PLI
- In-app GitHub Releases updater with CHANGELOG notes (disabled by default when MDM is present)

## What it is not

- **No video.** For Android video push/play, use **[ICU VideoStreamer](https://github.com/jpat-12/TAK-PluginSuite-ICU_VideoStreamer/releases/tag/2.4.0)** (or equivalent). See `docs/feature-parity.md` (`FP-VIDEO`).

## License

Source-available under **AndroidTAKTracker Free Application License 1.0** (see `LICENSE`). Free to use; do not sell the app itself. Paid install/support services are fine.

## Samples

Fake hosts only — see `samples/enrollment.example.txt` and `samples/config.example.json`. Never commit real TAK hosts, tokens, or certs.

## Build

```bat
gradlew.bat :app:assembleDebug
gradlew.bat :core:testDebugUnitTest
```

Requires Android SDK (create `local.properties` with `sdk.dir=…`; that file is gitignored).

Continuous releases publish `build-0.1.<run>` tags with `AndroidTAKTracker.apk` + `.sha256`.

## Feature parity

See [`docs/feature-parity.md`](docs/feature-parity.md). A scheduled workflow diffs this table against WinTAKTracker and opens a parity-drift issue when IDs diverge.
