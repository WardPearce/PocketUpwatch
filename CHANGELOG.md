# Changelog

## 0.1.12

- Move maintenance directly above the Manage monitor panel at the bottom of the service page. Keep both panels stacked together on phones and tablets.

## 0.1.11

- Edit request and expected bodies with Sora: JSON, YAML and XML highlighting, line numbers, validation, undo/redo, and an expanded editor. Preview formatting before applying it.
- Match YAML/YML responses by value. JSON and YAML comparisons ignore formatting and key order while preserving types, array order, and number precision.
- Choose structural XML comparison and optional indentation handling. Existing XML monitors keep their original comparison until changed.
- View syntax-highlighted expected responses on the service page.
- Start or end maintenance from the service page, with 1, 4 and 24-hour windows. Maintenance no longer appears in the add/edit form.
- Add, edit, reveal and delete custom headers as individual rows.
- Update portable backups to retain the new response options; older backups remain supported.
- Add open-source notices in Help and about.

## 0.1.10

- Add tablet and unfolded-foldable workspaces with a navigation rail, monitor list/detail panes, a settings sidebar, and multi-column forms and cards. Preserve phone layouts and in-memory editing/filter/chart state through resizing, and keep panes clear of separating hinges.
- Cache both Robolectric runtimes with Gradle via Google's Maven Central mirror and run tests with offline runtime resolution, avoiding the HTTP 429 failures in CI.

- Organize Settings into six pages with current-value summaries, live background diagnostics, resolved-error labels, and direct permission/channel links.
- Separate encrypted backups and redacted templates; add password confirmation/reveal, file-first import, selectable previews, similar-address warnings, guarded transactions, and activity-recreation recovery.
- Preserve imported template review requirements in database version 5; prevent individual and bulk starts until reviewed in the editor. Keep existing version 1 backups compatible and optionally include portable app preferences in version 2 backups.
- Add new-monitor defaults for intervals, timeouts, Internet requirements, and failure/recovery thresholds, preserving existing monitors.
- Add notification readiness, Test alert, alert snooze, and daily quiet hours while checks and incidents continue. Announce ongoing deferred outages after alerts resume.
- Add persistent sort/chart defaults, compact list spacing, and timestamp choices.
- Show storage counts, file usage, oldest retained check, and last cleanup; preview manual cleanup while monitoring is paused.
- Track successful encrypted backups and offer optional reminders independent of monitoring. Add reviewed support reports, permission setup, and help/project links.
- Match protocol/address field heights and alignment, including large text, while retaining automatic URL protocol selection.
- Shorten labels, explanations, errors, time units, and widget copy across the app; remove the instruction to enter a name to enable Save.
- Add regression coverage and native previews for settings, backup/import, data migration, cleanup, alert suppression, and editor alignment. Count only persisted checks in background diagnostics.

## 0.1.9

- Use bold white UP/DOWN text on opaque green/red badges, with contrasting borders in dark mode and measured text contrast of at least 7:1 in both themes.
- Automatically select the protocol and remove its prefix when entering or pasting HTTP(S) URLs, including mixed-case schemes and surrounding whitespace; retain the host, port, path, and query.
- Add overview counts and combined status, tag, and search filters, compact sorting, and explicit all-monitor actions.
- Align displayed health and widgets with confirmed alert thresholds; show latest-check progress and last confirmed health separately from skipped/stale/paused states.
- Offer Save paused after failed or unknown save-time checks, without another probe or recording history.
- Surface stopped/delayed monitoring, recent background errors, and permission issues in an overview banner linked to diagnostics.
- Improve monitor-switch accessibility, relative check timestamps, narrow-screen/large-font filter layouts, and healthy-badge text contrast in both themes.
- Add behavioral regressions and native-rendered screenshots of the overview, filtering, background health, threshold progress, and paused-save workflow.

## 0.1.8

- Apply Require Internet to background, manual, and save-time checks through one shared connectivity guard.
- Skip failures when Internet validation is lost or the default network changes/disconnects during a probe; preserve LAN checks with Require Internet off.

## 0.1.7

- Restore the five-minute minimum for monitoring intervals and upgrade existing shorter intervals.
- Keep the CPU awake through background result persistence and alarm rescheduling, releasing bounded wake locks while waiting between checks.
- Expose alarm delivery, scheduler, result, and blocked-restart diagnostics in settings.

## 0.1.6

- Add a configurable Service uptime home-screen widget with a service picker, current status, 24-hour uptime percentage, availability graph, and a link to the selected service. Each widget has its own selection and refreshes after monitoring and configuration changes.
- Apply the stronger button outlines only in light mode; restore the original filled button appearance in dark mode and honor the app's theme override.
- Increase the space between the back button and page title to 16dp, including service names.

## 0.1.5

- Give secondary, icon, and unselected time-range buttons a solid theme-colored outline so pale fills remain distinct from their cards across light and dark accent colors.
- Outline destructive buttons with the error color while preserving their filled backgrounds and the existing disabled states.

## 0.1.4

- Keep history navigation available when new checks arrive outside the page's saved snapshot.
- Continue refreshing the first page live and clamping older pages after retention cleanup.
- Add a deterministic regression for the CI failure caused by a background refresh disabling Next.

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
