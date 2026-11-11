# Changelog

## 0.1.0

- Add incident history, consecutive failure/recovery thresholds, optional initial-down alerts, repeat reminders, and maintenance windows.
- Add manual checks and tests before saving; HTTP methods, encrypted headers, basic/bearer authentication, request bodies, redirects, timeouts, and JSON-path assertions.
- Add DNS resolution/expected-answer checks and TLS certificate expiry checks with configurable warning periods.
- Add versioned configuration import/export, password-encrypted full backups, redacted public backups, and CSV exports for checks and incidents. Imports create paused copies.
- Add tags, search, status/name/tag sorting, bulk pause/resume, a home-screen widget, and a Quick Settings tile.
- Fix structural JSON matching and strict document validation; exclude concurrency queue time from response measurements.
- Query selected history periods and aggregate graph data independently of recent-check pagination. Keep hourly summaries for a year and apply configurable raw-history retention.
- Distinguish skipped/unknown checks from failures, support LAN targets without validated internet, refresh stale detail state, and expose any-2xx matching.
- Consolidate due-time scheduling, stop idle foreground services, and open the affected monitor from alert notifications.
- Export Room schemas, test migrations and monitoring behavior, and require unit tests, Android lint, and a debug build before releases.
