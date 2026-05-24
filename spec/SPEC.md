# mymail — Android Client Specification

A native Android email client for mymail. It connects to a self-hosted mymail server
over its REST API. No offline support in v1 (may be added later).

---

## Technology Stack

| Concern              | Technology                                           |
|----------------------|------------------------------------------------------|
| Language             | Kotlin                                               |
| Build                | Gradle with Kotlin DSL (`build.gradle.kts`)          |
| Minimum SDK          | API 26 (Android 8.0)                                 |
| Target SDK           | Latest stable                                        |
| UI                   | Jetpack Compose + Material 3                         |
| Navigation           | Jetpack Navigation Compose                           |
| API client           | Retrofit 2 — generated from `../mymail/openapi.yaml` |
| Code generation      | OpenAPI Generator Gradle Plugin                      |
| JSON                 | kotlinx.serialization                                |
| HTTP                 | OkHttp 4                                             |
| Async                | Kotlin Coroutines + Flow                             |
| State / lifecycle    | Jetpack ViewModel + `StateFlow`                      |
| Dependency injection | Hilt                                                 |
| Credentials storage  | `EncryptedSharedPreferences`                         |
| Background polling   | WorkManager (periodic work request)                  |


---

## Project Layout

The Android project lives in separate repository `mymail-android` with the following structure:

```
├── build.gradle.kts               # Root build file
├── settings.gradle.kts
├── app/
│   ├── build.gradle.kts
│   └── src/
│       └── main/
│           ├── AndroidManifest.xml
│           └── java/nu/staldal/mymail/
│               ├── MyMailApplication.kt   # @HiltAndroidApp
│               ├── MainActivity.kt        # Single activity
│               ├── di/
│               │   ├── NetworkModule.kt   # Hilt: Retrofit, OkHttp
│               │   └── PrefsModule.kt     # Hilt: EncryptedSharedPreferences
│               ├── auth/
│               │   └── CredentialStore.kt # Read/write server URL + credentials
│               ├── repository/
│               │   ├── FolderRepository.kt
│               │   ├── MessageRepository.kt
│               │   ├── DraftRepository.kt
│               │   ├── ContactRepository.kt
│               │   ├── IdentityRepository.kt
│               │   ├── FilterRepository.kt
│               │   └── SpamFilterRepository.kt
│               ├── ui/
│               │   ├── navigation/
│               │   │   └── NavGraph.kt
│               │   ├── theme/
│               │   │   ├── Theme.kt
│               │   │   ├── Color.kt
│               │   │   └── Type.kt
│               │   └── screen/
│               │       ├── setup/
│               │       │   ├── SetupScreen.kt
│               │       │   └── SetupViewModel.kt
│               │       ├── folder/
│               │       │   ├── FolderListScreen.kt
│               │       │   ├── FolderListViewModel.kt
│               │       │   ├── MessageListScreen.kt
│               │       │   └── MessageListViewModel.kt
│               │       ├── message/
│               │       │   ├── MessageDetailScreen.kt
│               │       │   └── MessageDetailViewModel.kt
│               │       ├── compose/
│               │       │   ├── ComposeScreen.kt
│               │       │   └── ComposeViewModel.kt
│               │       ├── search/
│               │       │   ├── SearchScreen.kt
│               │       │   └── SearchViewModel.kt
│               │       └── settings/
│               │           ├── SettingsScreen.kt     # Tabbed container
│               │           ├── IdentitiesTab.kt
│               │           ├── FoldersTab.kt
│               │           ├── FiltersTab.kt
│               │           ├── SpamTab.kt
│               │           ├── ContactsTab.kt
│               │           └── PreferencesTab.kt
│               └── worker/
│                   └── MailPollingWorker.kt
```


---

## API Client Generation

### Gradle plugin

Apply `org.openapi.generator` in `app/build.gradle.kts`:

```kotlin
plugins {
    id("org.openapi.generator") version "<latest-stable>"
}
```

The OpenAPI generator configuration lives entirely inside `app/build.gradle.kts`; there is no separate `openapi-generate.gradle.kts`.

### Generator configuration

```kotlin
openApiGenerate {
    generatorName.set("kotlin")
    inputSpec.set("$rootDir/../mymail/openapi.yaml")   // relative to the repository root
    outputDir.set(layout.buildDirectory.dir("generated/openapi").get().asFile.absolutePath)
    apiPackage.set("nu.staldal.mymail.api")
    modelPackage.set("nu.staldal.mymail.model")
    configOptions.set(mapOf(
        "library"               to "jvm-retrofit2",
        "serializationLibrary"  to "kotlinx_serialization",
        "useCoroutines"         to "true",
        "dateLibrary"           to "java8",
    ))
}
```

Wire the generated sources into the compilation:

```kotlin
kotlin.sourceSets["main"].kotlin.srcDir(
    layout.buildDirectory.dir("generated/openapi/src/main/kotlin")
)
tasks.named("compileDebugKotlin") { dependsOn("openApiGenerate") }
tasks.named("compileReleaseKotlin") { dependsOn("openApiGenerate") }
```

The generated code produces one Retrofit `interface` per API tag (e.g. `FoldersApi`,
`MessagesApi`, `DraftsApi`, …) and data classes for all schemas. Do not edit the
generated files; they are regenerated on every clean build.


---

## Networking

### Retrofit / OkHttp setup (`NetworkModule.kt`)

- Retrofit is held in a `AtomicReference<Retrofit>` singleton. The reference is rebuilt
  and atomically swapped whenever the user changes the server URL in Setup.
- A single `OkHttpClient` is shared. It has:
  - `BasicAuthInterceptor` — adds `Authorization: Basic …` from `CredentialStore`.
  - `ConnectTimeout` / `ReadTimeout` / `WriteTimeout`: 30 seconds each.
  - Logging interceptor (debug builds only).
- The `Authorization` header is added on every request. The server bypasses CSRF checks
  for requests without an `Origin` header, which is the standard native-client behaviour
  (see REQUIREMENTS.md → CSRF Protection).

### Error handling

All network calls are wrapped in a `Result<T>` (Kotlin stdlib). Repositories catch
`HttpException` and `IOException` and return `Result.failure(...)`. ViewModels expose
a `UiState` sealed class with `Loading`, `Success`, and `Error` variants.

HTTP 401 responses cause the app to navigate to the Setup screen so the user can
re-enter credentials.


---

## Server Setup Screen

Shown on first launch (no credentials stored) or after a 401 response.

**Fields:**
- Server URL (e.g. `https://mail.example.com`) — validated as a well-formed URL.
  HTTPS is required in release builds; plain HTTP is permitted in debug builds only
  (`BuildConfig.DEBUG`).
- Username
- Password (obscured, toggle-to-reveal)

**Behaviour:**
- Tapping **Connect** calls `GET /api/v1/health`. On 200, stores credentials in
  `EncryptedSharedPreferences` and navigates to the Folder List. On failure, shows an
  inline error.
- If credentials already exist, this screen is skipped at startup.
- A **Clear saved server** option in Settings allows re-opening this screen.


---

## Navigation

Compose Navigation with a single `NavHost`. Route names:

| Route                                  | Screen                       |
|----------------------------------------|------------------------------|
| `setup`                                | Setup / login                |
| `folders`                              | Folder list (drawer/home)    |
| `messages/{folder_id}`                 | Message list for a folder    |
| `message/{messageId}`                  | Message detail               |
| `compose`                              | New compose                  |
| `compose?replyTo={id}`                 | Reply                        |
| `compose?replyAllTo={id}`              | Reply All                    |
| `compose?forwardOf={id}`               | Forward                      |
| `compose?draftId={id}`                 | Edit draft                   |
| `search`                               | Search                       |
| `settings`                             | Settings (tabbed)            |

On phones (and v1 in general), folder list and message list are separate full-screen
destinations. Tablet adaptive layout is deferred to v1+.


---

## Folder List Screen

- Fetches `GET /api/v1/folders` on enter and on pull-to-refresh.
- Renders a `LazyColumn` of folder rows. Each row shows name and `unread_count` badge.
- Unread counts are shown in a coloured chip. Inbox badge is also reflected in the
  launcher icon shortcut badge via `NotificationManager` (badge count derives from the
  posted notification automatically).
- Tapping a folder navigates to the Message List for that folder.
- A **Compose** FAB navigates to the Compose screen.
- A **Search** icon in the top app bar navigates to Search.
- A **Settings** icon navigates to Settings.


---

## Message List Screen

- Title: folder name.
- Fetches `GET /api/v1/folders/{folder_id}/messages?limit=50&offset=0`.
- Renders a `LazyColumn`. Each item shows:
  - From address (or display name if available)
  - Subject (bold when unread)
  - Date (adaptive format matching the web UI rules)
  - Attachment indicator icon
  - `send_failed` badge: shown only in Scheduled (yellow) and Drafts (red); the flag
    does not appear in other folders
- Infinite scroll: loads the next page when the user reaches the end of the list
  (`offset += 50`). Stop paginating when the returned item count is less than 50.
- Pull-to-refresh reloads from offset 0.
- **Mark all as read** action in the overflow menu calls
  `POST /api/v1/folders/{folder_id}/mark-all-read`.
- **Empty folder** action (Trash and Junk only) calls
  `DELETE /api/v1/folders/{folder_id}/messages` after a confirmation dialog.
- Long-press a message to enter multi-select mode. Toolbar actions in multi-select:
  - **Mark read / unread** — `PATCH /api/v1/messages` with `{"ids": […], "read": true/false}`
  - **Move to folder** (folder picker dialog) — `POST /api/v1/messages/move`. The **Move**
    action is disabled entirely when any selected message is in Drafts, Scheduled, or
    Snoozed (the server rejects such sources with 400).
  - **Delete** — `DELETE /api/v1/messages` with `{"ids": […]}`


---

## Message Detail Screen

- Fetches `GET /api/v1/messages/{id}`.
- After a successful fetch, if the message has `read: false`, issues
  `PATCH /api/v1/messages/{id}` with `{"read": true}` to mark as read.
- **Header section** (collapsed by default, expandable): From, To, Cc, Bcc, Reply-To,
  Date, Subject.
- **Body section:** displays `body_text` in a `SelectionContainer` with a monospace
  font (plain text only in v1).
- **Attachments section:** lists all attachments by name and size. Tapping an
  attachment downloads it via `GET /api/v1/attachments/{id}` and opens it with
  `ACTION_VIEW` (using `FileProvider`).
- **Thread section:** fetches `GET /api/v1/messages/{id}/thread`. Shown as a
  collapsible list of message summaries below the body. Tapping a summary navigates to
  that message's detail screen. When `truncated` is true, show a "Thread too long"
  notice.

### Action bar (varies by folder)

| Folder           | Actions shown                                                              |
|------------------|----------------------------------------------------------------------------|
| Inbox / user folders (id ≥ 100) | Reply, Reply All, Forward, Move, Mark as junk, Delete    |
| Sent             | Forward, Move, Delete                                                      |
| Drafts           | Edit (→ Compose), Discard (with confirmation)                              |
| Scheduled        | Delete (permanent)                                                         |
| Snoozed          | Reply, Reply All, Forward, Move, Mark as junk, Delete                      |
| Junk             | Not junk, Move, Delete (permanent)                                         |
| Trash            | Move, Delete (permanent)                                                   |

**Move** opens a folder picker bottom sheet (lists all folders except Scheduled,
Snoozed, Drafts).

**Mark as junk** calls `POST /api/v1/messages/{id}/mark-junk`. On success navigate back
to the message list; the app stays in the current folder (does not navigate to Junk).

**Not junk** calls `POST /api/v1/messages/{id}/mark-not-junk`. On success navigate back
to the Junk message list (the message is moved to Inbox).


---

## Compose Screen

Supports new mail, reply, reply-all, forward, and draft editing.

**Fields:**

| Field      | Notes                                                                          |
|------------|--------------------------------------------------------------------------------|
| From       | `DropdownMenu` populated from `GET /api/v1/identities`                         |
| To         | Chip text field with autocomplete from `GET /api/v1/contacts?q=…&limit=10`     |
| Cc         | Same as To (collapsed by default, expand via button)                           |
| Bcc        | Same as To (collapsed by default)                                              |
| Reply-To   | Single plain text field (optional, collapsed by default)                       |
| Subject    | Single-line text field                                                         |
| Body       | Multi-line plain text field (`OutlinedTextField`, v1 is plain text only)       |
| Attachments| List of attached files; **Add attachment** button opens the system file picker |

**Pre-population for Reply / Reply All / Forward:**
Fetch the source message via `GET /api/v1/messages/{id}` and apply the same
pre-population rules as the web UI (see REQUIREMENTS.md → Compose). For Forward,
pass `source_message_id` on draft creation to copy attachments server-side.

**Auto-save:**
Start an auto-save coroutine on a 30-second `delay` loop. On first save, call
`POST /api/v1/drafts` (without attachments, even if files are already queued locally)
and store the returned `id`. On subsequent saves call `PUT /api/v1/drafts/{id}`. Track a
dirty flag; mark dirty on any field edit, clear it after each successful save. If the
user navigates away before saving, cancel any in-flight auto-save and, in `onStop`,
perform a synchronous save only when the dirty flag is set. Locally queued attachments
are never uploaded during auto-save — they are uploaded only at send time.

**Send:**
Disable the Send button while in flight. If no `draftId` exists yet (the user tapped
Send before the first 30-second auto-save fired), perform a synchronous
`POST /api/v1/drafts` first to obtain one, then proceed. If locally queued attachments
exist, upload them via `PUT /api/v1/drafts-with-attachments/{id}` before calling
`/send`. Always send via `POST /api/v1/drafts/{id}/send`.
On 201 navigate back.
On 400/500 show the server error message inline above the Send button.

**Attachments:**
Attach files using `ActivityResultContracts.GetMultipleContents`. Store selected file
URIs locally; upload them when sending (as `multipart/form-data` parts). Remove an
attachment by tapping the × chip.

For draft edits, existing server-side attachments are shown as removable chips; tapping
× calls `DELETE /api/v1/drafts/{id}/attachments/{attachment_id}` immediately. Newly
added files are queued locally and uploaded at send time via
`PUT /api/v1/drafts-with-attachments/{id}`.


---

## Search Screen

- Search bar at the top (triggered by clicking the search icon in the Folder List).
- Optional folder filter: `DropdownMenu` listing all folders; default is "All folders
  (except Junk, Drafts, Scheduled)" — this matches the API behaviour when `folder_id` is
  omitted and should be stated in the UI label or tooltip to avoid user confusion.
- Optional date range: two `DatePicker` dialogs (From date, To date).
- Calls `GET /api/v1/messages/search?q=…&folder_id=…&date_from=…&date_to=…&limit=50&offset=0`.
- Results rendered as a `LazyColumn` of message summaries, each showing the FTS
  `snippet` below the subject.
- Tapping a result navigates to Message Detail.
- Infinite scroll for pagination: same page size and offset increment as the Message
  List (`limit=50`, `offset += 50`). Stop paginating when the returned item count is
  less than 50.


---

## Settings Screen

A `TabRow` with six tabs, mirroring the web UI:

| Tab          | Content                                            |
|--------------|----------------------------------------------------|
| Identities   | CRUD list of identities; set default               |
| Folders      | CRUD list of user folders; reorder via drag-handle |
| Filters      | CRUD list of filters; reorder via drag-handle      |
| Spam         | Enable/disable toggle, score header, threshold     |
| Contacts     | Paginated contact list; add / edit / delete        |
| Preferences  | App-level preferences (see below)                  |

**Identities tab:** Each row shows name, address, and a default-star icon.
Edit opens a bottom sheet with name, address, is\_default toggle, and signature text
field. Delete requires confirmation; disabled when only one identity exists.

**Folders tab:** Only user-created folders (id ≥ 100) can be renamed or deleted.
Built-in folders are shown but edit/delete controls are hidden. Deleting a folder
prompts "Messages will be moved to Trash".

**Filters tab:** Each filter row shows name and a summary of match criteria + action.
Edit opens a full-screen dialog with all fields. Reordering is done with up/down arrow
buttons on each list item (drag-to-reorder is deferred to v1+).
The `match_to` label is "To / Cc".

**Spam tab:** Toggle plus two text fields for score header name and threshold.
`PUT /api/v1/spam-filter` on save.

**Contacts tab:** Paginated list. Search field filters via `q=`. Add / edit / delete.

**Preferences tab:** These are stored in regular `SharedPreferences` (not encrypted):

| Preference          | Default    | Notes                                           |
|---------------------|------------|-------------------------------------------------|
| Dark mode           | System     | System / Light / Dark                           |
| Message list density| Normal     | Compact / Normal / Relaxed (row height)         |
| New-mail notifications | Off    | Enables Android notification channel; requests POST_NOTIFICATIONS permission |

There is also a **Server** entry (not a tab) at the bottom of the Settings screen that
shows the current server URL and a **Change server** button which navigates to Setup.


---

## New Mail Notifications

### Foreground polling

While the app is in the foreground, a coroutine on `LifecycleScope` polls
`GET /api/v1/folders` every 30 seconds. The result of the first poll is used as the
baseline; no notification fires on that first result. On every subsequent poll, when the
Inbox `unread_count` is higher than the previous value, fire an Android notification (if
permission granted and preference enabled) and update the unread badge.

### Background polling

A `PeriodicWorkRequest` using `WorkManager` runs `MailPollingWorker` at a minimum
interval of 15 minutes (Android WorkManager lower bound). The worker calls
`GET /api/v1/folders`, compares the Inbox `unread_count` against the last-known count
persisted in `SharedPreferences`, and posts an Android notification if it increased.

The notification uses a dedicated `NotificationChannel` (`mymail_new_mail`).
Tapping the notification launches the app with a back-stack constructed via
`TaskStackBuilder` so the user can press Back from Inbox to reach the Folder List.

The background worker checks whether the app is currently in the foreground (via
`ProcessLifecycleOwner` or an equivalent foreground flag) and skips its poll and
notification if so, deferring to the foreground poller.

The background worker is enqueued on first launch (after setup) with
`ExistingPeriodicWorkPolicy.KEEP` so it survives app restarts.


---

## Authentication

- Credentials (server URL, username, password) are stored in `EncryptedSharedPreferences`
  (Jetpack Security library).
- `BasicAuthInterceptor` intercepts every `OkHttp` request and adds
  `Authorization: Basic <base64(username:password)>`.
- No `Origin` header is sent (native HTTP clients do not send it); the server's CSRF
  check is therefore skipped automatically.
- On HTTP 401: clear in-memory Retrofit instance, navigate to Setup screen.


---

## Error Handling

| Condition                  | Behaviour                                                         |
|----------------------------|-------------------------------------------------------------------|
| Network error / timeout    | `Snackbar` with "Retry" action; retry once after 2 seconds        |
| 400 Bad Request            | Show server `error` message inline near the triggering UI element |
| 401 Unauthorized           | Navigate to Setup screen                                          |
| 404 Not Found (detail screen) | Show "Not found" inline in the detail pane                     |
| 404 Not Found (list screen)   | Navigate back to the Folder List with a Snackbar error          |
| 409 Conflict               | Show server `error` message inline                                |
| 500 Internal Server Error  | `Snackbar` with server `error` message                            |


---

## Date / Time Display

Timestamps are displayed in the device's local timezone using the same adaptive rules
as the web UI:

| Age                | Format                     | Example           |
|--------------------|----------------------------|-------------------|
| < 1 minute         | "just now"                 | "just now"        |
| 1 min – < 1 hour   | Relative ("42 min ago")    | "42 min ago"      | ¹
| 1 hour – 23:59     | Time only (HH:mm, 24-hour) | "14:32"           |
| Yesterday          | "Yesterday HH:mm"          | "Yesterday 09:15" | ²
| 2–6 days ago       | Weekday + time             | "Mon 14:32"       |
| 7 days – same year | Short date + time          | "Apr 3, 14:32"    |
| Previous years     | Short date with year       | "Apr 3, 2023"     |

¹ "min" is an intentional abbreviation for the Android client; `REQUIREMENTS.md` uses
"minutes ago" (full word) for the web UI.

² "Yesterday" means the calendar day before today in the device's local timezone (any
time on that calendar date, regardless of elapsed hours).

Message detail always shows the full form with timezone abbreviation.
Long-press a date/time chip to copy the full ISO 8601 string.


---

## Build & Development

```bash
# Generate API client and build a debug APK
./gradlew :app:assembleDebug

# Run unit tests
./gradlew :app:testDebugUnitTest

# Regenerate API client only (after editing ../mymail/openapi.yaml in the parent directory)
./gradlew :app:openApiGenerate
```

The `openApiGenerate` task reads `../mymail/openapi.yaml` relative to the repository root
directory. After regeneration, rebuild the project; generated sources are not committed
to version control.


---

## Out of Scope (v1)

- Offline support / local message cache
- Push notifications (FCM) — background polling via WorkManager is sufficient
- HTML message body rendering (WebView) — plain text display only
- Rich text / HTML compose (plain text only)
- Inline attachment preview (download + external viewer only)
- Snooze — Snoozed folder is shown read-only; no snooze or edit-snooze actions
- Scheduled send — no "Send later" in Compose; Scheduled folder is shown with Delete only
- Drag-to-reorder for filters and identities (tap-to-reorder with up/down arrows as
  a simpler alternative is acceptable for v1)
- PGP / S-MIME
- Multi-account support
- Tablet adaptive layout (single-column phone layout is sufficient for v1)
- Download raw `.eml` file (`GET /messages/{id}/raw`)
- Flagged / starred messages — the `flagged` field is present in the API but no flag/star
  UI is exposed; a future version may add it
