# Changelog

## 0.1.3

- Use filled buttons with 8dp corners and consistent touch targets throughout the app, including the add/edit form.
- Move check/edit actions near service status, align bulk and onboarding controls, label the add-monitor action, and group exports separately from deletion.
- Repair history navigation with true page counts, stable snapshots, loading protection, scroll resets, and aligned equal-width Previous/Next buttons.
- Allow 1-minute check intervals and show a battery-drain warning below 5 minutes.

## 0.1.2

- Redesign the add/edit form with section cards, icons, setting summaries, collapsible advanced options, and a persistent Save/Cancel bar.
- Rename Require validated internet to Require Internet and enable it by default for new monitors; preserve existing settings.
- Disable form controls during save-time checks and keep validation errors next to the Save action.

## 0.1.1

- Default new monitors to DOWN after one failed check; preserve existing configured thresholds.
- Replace the separate test action with a save-time check that shows failures and keeps unsaved edits available.
- Display status labels in tinted badges with matching borders.
- Organize service details into separate status, target, schedule, and action cards.

- Separate monitor status labels from the pause/resume switch.
- Show a loading spinner and disable Check now while a check is queued or running, including checks performed by the foreground service.
- Restore the interval slider with readable labels such as 5min and 2hours; preserve custom intervals when editing.
- Place the protocol selector alongside the host input.

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
