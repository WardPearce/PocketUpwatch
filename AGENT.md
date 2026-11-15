# Pocket Upwatch

An Android uptime monitor built with Kotlin, Compose, and Room. Monitor HTTP(S), TCP ports, ICMP ping, DNS answers, and TLS certificate expiry from your phone. All configuration and history stay on the device unless you explicitly export them.

## Monitoring

The add/edit form groups service details and monitoring essentials into cards. Expand request, response, authentication, alerts, or organization sections only when needed; closed sections summarize their current settings. Save and Cancel stay visible at the bottom, and existing settings are preserved when editing.

Enter or paste a full URL to select its protocol automatically: `https://example.com` selects HTTPS and leaves `example.com` in the address field. HTTP works the same way; ports, paths, and query strings are preserved.

Add a target, choose a check interval from 5 minutes to 24 hours with the interval slider, and save it. Save checks the target first; a failed or unknown check keeps the editor open, shows an error, and offers **Save paused**. This saves a valid configuration without another probe, history, or alerts so you can enable it when the target is ready. Invalid configurations cannot be saved. Existing intervals below 5 minutes are raised to 5 minutes when the database opens. Monitors begin checking after a successful save; each can be paused independently.

The overview shows tappable All, Down, Unknown, and Paused counts. Status filters combine with name/target/tag search and exact tag chips; Clear filters resets them together. Unknown includes skipped, stale, stopped, and pending checks. Maintenance stays separate. Sort the collection by status/name/tag, or use the actions menu to pause/resume **all** monitors, including those outside the current filters. Cards show relative check age and labeled monitoring switches.

Health badges follow the same confirmed failure/recovery thresholds as incidents and alerts. A separate latest-check message shows progress such as “1 of 3 failures” or “1 of 2 successes to recover.” Skipped, stale, paused, and stopped states remain explicit and can show the last confirmed health; they never imply recovery. Both home-screen widgets use the same health logic. An overview banner links to background diagnostics when monitoring stops, checks become stale, a new background error occurs, or notification/alarm access needs attention.

| Protocol | Example | What it checks |
| --- | --- | --- |
| HTTP(S) | `https://example.com/health` | Status and optional response-body assertion |
| TCP | `tcp://example.com:443` | Connection to a host and port |
| Ping | `ping://example.com` | ICMP echo; some networks block this |
| DNS | `dns://example.com` | System DNS resolution and optional expected IP answers |
| TLS | `tls://example.com:443` | Trusted certificate, hostname verification, and expiry warning |

HTTP checks support GET, HEAD, POST, PUT, PATCH, and DELETE; exact status codes or any 2xx; redirects; custom headers; basic/bearer authentication; and request bodies for POST/PUT/PATCH. Timeouts are configurable from 1 to 60 seconds. Headers and authentication are encrypted using Android Keystore-backed AES-GCM. URLs, request bodies, and expected bodies remain in the local database; avoid placing credentials in them. Target displays omit HTTP credentials and query strings. URL user information is converted to an encrypted Authorization header when saving.

Response assertions support exact text, structural JSON, and XML. JSON object key order is ignored, arrays remain ordered, scalar types remain distinct, and the entire document must be valid. An optional JSON path such as `$.status` or `$.items[0].ok` selects a value to compare with the expected JSON. For example, expected `"ok"` at `$.status` accepts `{"status":"ok","time":123}`. Wildcards and recursive JSON paths are not supported. XML does not allow doctypes or external entities. Response bodies used for matching are limited to 1 MiB. A maximum of four probes execute concurrently; displayed duration excludes time waiting for a probe slot and includes body validation when configured.

## Alerts, incidents, and history

Configure consecutive failure/recovery thresholds (default for new monitors: one failure and one recovery), initial-down alerts, repeat reminders, and maintenance windows. Unknown/skipped checks break pending streaks and never count as endpoint failures or recovery. Confirmed incidents record observed start/end times, failure reasons, and failed/unknown check counts. Notifications open the affected monitor. Grant notification permission to receive alerts.

The detail screen shows availability and mean response times for 1 hour, 24 hours, 7 days, or 30 days. Statistics query the selected period; graphs aggregate samples into time buckets. Recent raw checks are paginated independently, with aligned Previous/Next controls and an explicit page count. Older pages stay anchored while new checks arrive; returning to the first page shows the latest samples. Unknown and missing checks are excluded from success rates. A successful sample percentage does not establish continuous uptime. Open either graph in landscape fullscreen. Stale state refreshes while viewing a screen, even when no new checks arrive.

Raw-history retention is configurable in settings (30/90/180/365 days; default 90). Cleanup runs during monitoring and retains each monitor's latest result. Hourly summaries are retained for one year; incidents remain until their monitor is deleted. Deleting a monitor deletes its history, summaries, and incidents. Export checks or incidents as CSV from its detail screen.

Add **Service uptime** from your launcher's widget picker and choose a service. Each widget shows that service's current status, successful-check percentage over the last 24 hours, and an availability graph with 15-minute buckets. Green marks successful checks, red marks failures, and gray marks missing or skipped samples. Skipped checks are excluded from the percentage; no valid checks displays no percentage. Tap the widget to open its service. You can add multiple widgets for different services and reconfigure them through supported launchers. If a service is deleted, tap its widget to choose another. Widgets refresh after monitoring results and configuration changes, plus Android's periodic widget updates; they do not run extra probes. The original overview widget remains available.

## Background behavior

Monitoring uses a foreground service and ongoing notification. A single due-time scheduler coordinates active checks and wake-up alarms, using monotonic deadlines within a process. Enabled monitors resume when the app opens or after reboot; Android may restart the service after process reclamation. Pausing/deleting the last enabled monitor stops the service and alarms. A force-stop prevents automatic restart until the app is opened again. Settings now show background diagnostics: last alarm delivery, scheduler cycle, saved check, and restart/storage errors. Allow timely alarms for background wake-ups; without exact-alarm access, Android can delay alarms and reject a foreground-service restart. Wake locks cover scheduling and saving results, and are released between checks.

Unrestricted battery use may improve background reliability but increase power use. Allow alarms and reminders for more timely wake-ups; otherwise inexact alarms are used. Android can throttle or defer alarms, especially during Doze, so intervals are not guaranteed. The special-use foreground service requires the appropriate Google Play declaration and review before distribution.

No active network produces an unknown skipped result. **Require Internet** is enabled by default for new monitors to skip checks during captive portals or unvalidated connectivity; turn it off for LAN targets without internet access. Background, manual, and save-time checks share the same Internet requirement. Losing Internet validation during a check, switching default networks (such as Wi-Fi to mobile data), or a detected disconnect/reconnect makes a failed probe an unknown skip rather than a service failure. These skips do not count toward downtime or trigger down alerts. This cannot distinguish every local-network fault from a remote outage. Results reflect this phone and network, rather than independent public availability. Maintenance windows record unknown skips and suspend endpoint alerts. Save-time checks bypass scheduling and maintenance and do not record history or send alerts. Manual tests of paused monitors also leave history unchanged.

Add the Pocket Upwatch home-screen widget through your launcher, or add its Quick Settings tile to pause/resume all monitors. The widget displays a timestamped snapshot; opening the app refreshes live state. Process-local service status is considered stopped after process death until the service starts again.

## Settings

Settings is organized into Monitoring, Notifications, Appearance and dashboard, Data and storage, Backup and restore, and Help and about. Current-value summaries make common choices visible without opening each page. Diagnostics refresh while visible and after returning from Android settings; errors followed by a saved result are labeled resolved. Review and save a support report containing app/Android versions, permission and notification-channel states, scheduler timestamps, and error categories. Reports omit monitor addresses and credentials.

Set defaults for new monitors: check interval, timeout, Internet requirement, and failure/recovery thresholds. Existing monitors retain their settings. Choose a persistent default sort and chart period, comfortable or compact list spacing, and relative or date/time check timestamps.

Notifications shows the effective state of service alerts, monitoring status, and backup reminders, with direct links to their Android channel settings and a Test alert action. Snooze service alerts for 1, 4, or 24 hours, or configure daily quiet hours in your device's time zone. Checks and incident recording continue while alerts are muted. Deferred outages alert on their next failed check after alerts resume; an outage that starts and recovers entirely while muted does not generate a later recovery alert. Android permissions and channel settings still determine delivery.

Data and storage shows retained check, incident, and hourly-summary counts, oldest check, database file usage, and last cleanup. Preview and confirm manual cleanup even while all monitors are paused. Cleanup retains each monitor's latest result and all incidents, and removes hourly summaries older than a year. Deleted rows may not immediately reduce database file size.

Help and about includes permission setup, widget and Quick Settings instructions, release notes, project/issue links, and selectable version/build details.

## Backup and restore

Open **Settings → Backup and restore** and choose an explicit export mode:

- **Create encrypted backup** includes monitors and credentials, with an optional copy of portable app preferences. Confirm a password of at least 12 characters. Backups use PBKDF2-HMAC-SHA256 (210,000 iterations) and AES-256-GCM; passwords cannot be recovered.
- **Export redacted template** omits headers and request/expected bodies and redacts URL query values. Names, hosts and paths remain visible, so review the file before sharing.

Import chooses a file first and asks for a password only for encrypted files. Review monitor names and redacted addresses, select rows, and inspect similar-address warnings before importing. Similar addresses, including repeated entries within a file, start unselected; this is a heuristic that ignores query values, and you can select them explicitly. Optionally restore included app preferences. Imported monitors start paused, and templates remain blocked from starting until you review them in the editor. Bulk resume also respects that requirement. Existing monitors and history are kept. Imports are bounded, validated, and committed transactionally with repeat-submit protection.

Prepared files and import previews survive activity recreation in memory. If Android kills the process during export, the app explains the interruption and asks you to try again; passwords are never saved in instance state. Last-backup time updates only after an encrypted backup is successfully written. Optional reminders every 7, 30, or 90 days work independently of monitor activity, use inexact Android alarms, and respect muted periods and notification permissions. Reminders prompt you to create a backup; they do not retain passwords or automatically export files.

Encrypted backups with preferences use payload version 2; version 1 monitor-only files remain supported. History, widget IDs, Android permission grants, snooze state, and last-backup timestamps remain local. Device-specific Keystore ciphertext is never exported as a portable credential. Android automatic backup is disabled.

## Adaptive layouts

The main UI retains its phone presentation for windows narrower than 600 dp or shorter than 600 dp, including ordinary phone landscape. Tablets and unfolded inner displays use a navigation rail. At 672 dp wide, the monitor list and service details/editor share a workspace, and settings shows categories beside the selected page. Forms and detail cards use two columns when their pane has at least 720 dp of content width. Permission setup is constrained to a readable width on tablets.

`AdaptiveRoot` observes current Compose constraints and AndroidX WindowManager folding features. Window height comes from current window metrics so opening the keyboard does not change the tablet into a phone layout. Separating/occluding hinges are translated from window coordinates into each pane container; vertical hinges separate panes left/right, and horizontal tabletop folds separate them top/bottom. Folding into a narrow window restores the existing phone navigation. The activity handles smallest-window-size and density configuration changes during folding without recreation. Movable content preserves filters, selected chart periods and unsaved editor fields, including in-memory credentials, as panes move.

Native tests cover portrait/landscape tablets, narrow windows, folding/refolding with unsaved data, keyboard-sized viewports, selection, settings navigation and both hinge orientations. Test folding features are simulated; physical folding hardware, IME/inset combinations and OEM behavior still need device validation. Dark previews are committed under `previews/`.

## Development and tests

Open with Android Studio, SDK 35, and JDK 17. Alternatively set `ANDROID_HOME` to an installed SDK, then run:

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Unit tests cover JSON/XML matching, paths, alert thresholds, unknown state, scheduling, timing under concurrency, HTTP behavior against MockWebServer, backups, CSV safety, period queries, and migration chains from database versions 1 through 4. Robolectric tests exercise Room, the editor, settings navigation, backup/import state and file errors, defaults, quiet hours, cleanup, and idle-service shutdown. Gradle resolves API 28 and API 35 Robolectric runtimes through Google's Maven Central mirror into its dependency cache, then `prepareRobolectricSdk` copies both into `app/build/robolectric-sdk`. Tests use Robolectric's offline resolver to avoid runtime/checksum downloads and Maven Central rate-limit failures. Keep the two runtime versions in separate configurations so Gradle does not replace the older one through version conflict resolution. Native Robolectric rendering also produces light/dark editor, dashboard, and settings previews under `app/build/outputs/editor-preview`; CI includes them in the validation artifact. Exported Room schemas are committed under `app/schemas`.

Pull requests run tests, Android lint, and the debug build without signing secrets. The release workflow calls the same checks before building a signed APK. Physical-device validation of Doze, reboot, OEM battery restrictions, widgets, and Quick Settings is still recommended before distribution.

## Android releases

Version 0.1.10 uses version code 16. Before the next release, update `versionName` and increase `versionCode` in `app/build.gradle.kts`. Every push to `main` validates the project, then builds a signed APK. If `v<versionName>` does not exist, the workflow creates a tag and GitHub release. Existing version tags skip publication.

Configure repository Actions secrets:

- `ANDROID_KEYSTORE_BASE64`: base64-encoded release keystore.
- `ANDROID_KEYSTORE_PASSWORD`: keystore password.
- `ANDROID_KEY_ALIAS`: signing alias.
- `ANDROID_KEY_PASSWORD`: signing key password.

Keep the keystore and passwords safe, and reuse the same signing key for future app updates. Never commit them. To create a key, use `keytool -genkeypair -v -keystore release.keystore -alias pocketupwatch -keyalg RSA -keysize 2048 -validity 10000`.
