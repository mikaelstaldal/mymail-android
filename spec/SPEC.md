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
    inputSpec.set(providers.gradleProperty("openApiSpecPath")
        .getOrElse("$rootDir/../mymail/openapi.yaml")) // override via gradle.properties
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

- Retrofit is managed by a Hilt `@Singleton` class `RetrofitHolder` (in `di/NetworkModule.kt`)
  that wraps an `AtomicReference<Retrofit>`. All repository classes inject `RetrofitHolder`
  and call `holder.get().create(FooApi::class.java)` to obtain API instances. When the user
  changes the server URL in Setup, `RetrofitHolder.rebuild()` atomically replaces the inner
  `Retrofit` instance.
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
- When navigated to via the **Change server** button in Settings, the Server URL and credential fields are pre-filled with the currently saved values.


---

## Navigation

Compose Navigation with a single `NavHost`. Route names:

| Route                                  | Screen                       |
|----------------------------------------|------------------------------|
| `setup`                                | Setup / login                |
| `folders`                              | Folder list (drawer/home)    |
| `messages/{folderId}`                  | Message list for a folder    |
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
- Unread counts are shown in a coloured chip. The launcher icon badge count is derived
  automatically from active notifications posted to the `mymail_new_mail` channel
  (Android 8+). OEM launchers that do not support adaptive badging (e.g. Samsung,
  Huawei) may not display the badge; no extra work is needed for v1.
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
  - `send_failed` badge (OpenAPI Message model field): shown only in Scheduled (yellow)
    and Drafts (red); the flag does not appear in other folders
- Row height adapts to the **Message list density** preference: Compact — 56 dp,
  Normal — 72 dp (default), Relaxed — 88 dp.
- Infinite scroll: loads the next page when the user reaches the end of the list
  (`offset += 50`). Stop paginating when the returned item count is less than 50.
  A page returning 0 items satisfies this condition and the empty result is discarded.
  When the total count is an exact multiple of 50 one extra empty request will be issued
  before pagination stops; this is intentional.
- **Empty state:** When the initial load returns 0 items, show a centred "No messages"
  text. **Error state on initial load:** Show a centred error message with a Retry
  button (the generic Snackbar still fires; the inline Retry is additional).
- Pull-to-refresh reloads from offset 0.
- **Mark all as read** action in the overflow menu calls
  `POST /api/v1/folders/{folder_id}/mark-all-read`.
- **Empty folder** action (Trash and Junk only) calls
  `DELETE /api/v1/folders/{folder_id}/messages` after a confirmation dialog.
- Long-press a message to enter multi-select mode. Toolbar actions in multi-select:
  - **Mark read / unread** — `PATCH /api/v1/messages` with `{"ids": […], "read": true/false}`.
    This bulk endpoint is distinct from the single-message `PATCH /api/v1/messages/{id}`
    used in Message Detail; both must be defined in the OpenAPI spec.
  - **Move to folder** (folder picker dialog) — `POST /api/v1/messages/move`. The **Move**
    action is disabled entirely when the current folder is Drafts, Scheduled, or
    Snoozed (the server rejects such sources with 400).
  - **Delete** — `DELETE /api/v1/messages` with `{"ids": […]}`


---

## Message Detail Screen

- Fetches `GET /api/v1/messages/{id}`. If the initial fetch fails, show a centred
  error message with a Retry button.
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
  that message's detail screen; pressing Back from there returns directly to the Message
  List (not to the originating detail screen). Implement using:
  ```kotlin
  navController.navigate("message/$threadMsgId") {
      popUpTo("message/$currentMsgId") { inclusive = true }
  }
  ```
  This replaces the current detail entry in the back-stack so that pressing Back lands
  on the Message List. When `truncated` is true, show a "Thread too long" notice.

### Action bar (varies by folder)

The action bar uses the `folder_id` field from the fetched message object to determine
which row of the table below applies. This is true regardless of how the user navigated
to the screen (from a Message List, from Search, or from a thread).

| Folder           | Actions shown                                                              |
|------------------|----------------------------------------------------------------------------|
| Inbox / user folders (id ≥ 100) | Reply, Reply All, Forward, Move, Mark as junk, Delete    |
| Sent             | Forward, Move, Delete                                                      |
| Drafts           | Edit (→ Compose), Discard (with confirmation)                              |
| Scheduled        | Delete (permanent)                                                         |
| Snoozed          | Reply, Reply All, Forward, Move, Mark as junk                              |
| Junk             | Not junk, Move, Delete (permanent)                                         |
| Trash            | Move, Delete (permanent)                                                   |

**Move** opens a folder picker bottom sheet (lists all folders except Scheduled,
Snoozed, Drafts).

**Mark as junk** calls `POST /api/v1/messages/{id}/mark-junk`. On success navigate back
to the message list; the app stays in the current folder (does not navigate to Junk).

**Not junk** calls `POST /api/v1/messages/{id}/mark-not-junk`. On success call
`navController.popBackStack()` to return to the previous screen. The message is moved
to Inbox server-side; if the previous screen was the Junk message list, it will refresh
and no longer show the message.


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
Fetch the source message via `GET /api/v1/messages/{id}` and pre-fill fields as follows:

| Field   | Reply                                              | Reply All                                                                 | Forward          |
|---------|----------------------------------------------------|---------------------------------------------------------------------------|------------------|
| To      | Source Reply-To if present, else source From       | Same as Reply; also add source To/Cc recipients, excluding own identities | (empty)          |
| Cc      | (empty)                                            | Remaining source To/Cc recipients, excluding own identities               | (empty)          |
| Bcc     | (empty)                                            | (empty)                                                                   | (empty)          |
| Subject | `Re: ` + source subject (suppress duplicate `Re:` prefixes) | Same as Reply                                                  | `Fwd: ` + source subject |
| Body    | Attribution line + quoted source body (see below)  | Same as Reply                                                              | Original headers + quoted source body (see below) |

**Quoted body format — Reply / Reply All:**
```
On <date>, <from address> wrote:
> <source body, each line prefixed with "> ">
```

**Quoted body format — Forward:**
```
---------- Forwarded message ----------
From: <source From>
Date: <source Date>
Subject: <source Subject>
To: <source To>

<source body>
```

For Forward, pass `source_message_id` on draft creation to copy attachments server-side.
`source_message_id` is only used for Forward; Reply and Reply-All omit it — thread
continuity for replies is handled by the server via standard `In-Reply-To`/`References`
email headers.

**Auto-save:**
When the screen opens via `compose?draftId={id}`, initialise the ViewModel's `draftId`
from the navigation argument. The auto-save loop then uses `PUT /api/v1/drafts/{id}`
from the very first save and never calls `POST /api/v1/drafts`.

Start an auto-save coroutine on a 30-second `delay` loop. On first save (new compose),
call `POST /api/v1/drafts` (without attachments, even if files are already queued
locally) and store the returned `id`. On subsequent saves call `PUT /api/v1/drafts/{id}`.
Track a dirty flag; mark dirty on any field edit, clear it after each successful save.

Use a `Mutex` (`saveMutex`) to serialise save operations. Both the auto-save loop body
and the `onCleared()` final-save coroutine must acquire `saveMutex` before checking the
dirty flag and performing a save. This prevents a double-save when the loop is cancelled
mid-flight and `onCleared()` runs immediately after.

If the user navigates away before saving, cancel any in-flight auto-save and, in
`ComposeViewModel.onCleared()`, launch a coroutine with `NonCancellable` context to
perform a final save if the dirty flag is set (ViewModel is cleared when the screen
leaves the back-stack).

The auto-save request body contains text fields only (From, To, Cc, Bcc, Reply-To,
Subject, Body). It never uploads, creates, or deletes attachments; attachments are
handled exclusively at send time or via immediate-delete for existing draft attachments.

Locally queued attachments are never uploaded during auto-save — they are uploaded only
at send time.

**Send:**
Before initiating Send, cancel the running auto-save job. The auto-save loop is not
restarted after Send completes (the draft is consumed by the send operation).
Disable the Send button while in flight. If no `draftId` exists yet (the user tapped
Send before the first 30-second auto-save fired), perform a synchronous
`POST /api/v1/drafts` first to obtain one, then proceed. If locally queued attachments
exist, upload them via `PUT /api/v1/drafts-with-attachments/{id}` before calling
`/send`. Always send via `POST /api/v1/drafts/{id}/send`.
On 201 navigate back.
On 400/500 show the server error message inline above the Send button.
If the attachment upload (`PUT /api/v1/drafts-with-attachments/{id}`) fails: show the
error message inline above the Send button; retain the draft ID and locally queued
attachments so the user can retry. Do not proceed to `/send`.

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
- Optional folder filter: `DropdownMenu` listing all folders; default is "All mail" — this matches the API behaviour when `folder_id` is
  omitted and should be stated in the UI label or tooltip to avoid user confusion.
- Optional date range: two `DatePicker` dialogs (From date, To date).
- Calls `GET /api/v1/messages/search?q=…&folder_id=…&date_from=…&date_to=…&limit=50&offset=0`.
- Results rendered as a `LazyColumn` of message summaries, each showing the FTS
  `snippet` below the subject.
- Tapping a result navigates to Message Detail.
- Infinite scroll for pagination: same page size and offset increment as the Message
  List (`limit=50`, `offset += 50`). Stop paginating when the returned item count is
  less than 50. A page returning 0 items satisfies this condition and the empty result
  is discarded.
- **Empty state:** When 0 results are returned for a submitted query, show a centred
  "No results" text. **Error state on initial load:** Show a centred error message with
  a Retry button.


---

## Settings Screen

A `TabRow` with six tabs, mirroring the web UI:

| Tab          | Content                                            |
|--------------|----------------------------------------------------|
| Identities   | CRUD list of identities; set default               |
| Folders      | CRUD list of user folders                          |
| Filters      | Read-only list of filters (editing is out of scope for v1) |
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
Read-only in v1 — no add, edit, delete, or reorder. Filter editing is deferred to v1+.

**Spam tab:** Toggle plus two text fields for score header name and threshold.
On enter, call `GET /api/v1/spam-filter` to load the current settings into the form
fields. Show a loading indicator while fetching; show an inline error with a Retry
button if the fetch fails. `PUT /api/v1/spam-filter` on save.

**Contacts tab:** Paginated list via `GET /api/v1/contacts?q=…&limit=50&offset=0`.
Search field filters via `q=`. Infinite scroll with `offset += 50`; stop paginating when
the returned count is less than 50. Add / edit / delete.

**Preferences tab:** These are stored in regular `SharedPreferences` (not encrypted):

| Preference          | Default    | Notes                                           |
|---------------------|------------|-------------------------------------------------|
| Dark mode           | System     | System / Light / Dark                           |
| Message list density| Normal     | Compact / Normal / Relaxed (row height)         |
| New-mail notifications | Off    | Enables Android notification channel; requests POST_NOTIFICATIONS permission at the moment the user toggles the preference on (if not already granted) |

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
interval of 15 minutes (Android WorkManager lower bound). The request must specify
`Constraints(requiredNetworkType = NetworkType.CONNECTED)` to avoid running and failing
silently in airplane mode or on no network. The worker calls `GET /api/v1/folders`,
compares the Inbox `unread_count` against the last-known count persisted in
`SharedPreferences`, and posts an Android notification if it increased. On the first run
(no persisted value exists), the worker stores the current `unread_count` as the
baseline and does not post a notification — consistent with the foreground poller's
first-poll behaviour.

The notification uses a dedicated `NotificationChannel` (`mymail_new_mail`).
Tapping the notification launches the app with a back-stack constructed via
`TaskStackBuilder` so the user can press Back from Inbox to reach the Folder List.

The background worker checks whether the app is currently in the foreground (via
`ProcessLifecycleOwner` or an equivalent foreground flag) and skips its poll and
notification if so, deferring to the foreground poller.

The background worker is enqueued immediately after a successful Connect on the Setup
screen, and again on app cold-start if credentials are already stored, using
`ExistingPeriodicWorkPolicy.KEEP` so duplicate enqueues are no-ops and it survives
app restarts.

If the worker receives HTTP 401: post a "Session expired — tap to sign in" notification
on the `mymail_new_mail` channel that deep-links to the Setup screen via
`TaskStackBuilder`; then cancel the periodic work request so polling stops until the
user re-authenticates (the worker is re-enqueued after a successful Setup).

Clear all posted new-mail notifications and reset the persisted unread-count baseline
when the user navigates to the Inbox message list screen.


---

## Authentication

- Credentials (server URL, username, password) are stored in `EncryptedSharedPreferences`
  (Jetpack Security library).
- `BasicAuthInterceptor` intercepts every `OkHttp` request and adds
  `Authorization: Basic <base64(username:password)>`.
- No `Origin` header is sent (native HTTP clients do not send it); the server's CSRF
  check is therefore skipped automatically.
- On HTTP 401: `BasicAuthInterceptor` runs inside OkHttp and cannot access the Compose
  navigation stack directly. Instead, inject an `AuthEventBus` — a `@Singleton` Hilt
  class wrapping a `MutableSharedFlow<Unit>` (replay = 0). The interceptor emits on
  this flow when it receives a 401 response. `MainActivity` collects the flow in
  `lifecycleScope` and executes:
  ```kotlin
  navController.navigate("setup") { popUpTo(0) { inclusive = true } }
  ```
  This clears the entire back-stack and presents the Setup screen. After a successful
  reconnect, `RetrofitHolder.rebuild()` refreshes the HTTP client with the new
  credentials.


---

## Error Handling

| Condition                  | Behaviour                                                         |
|----------------------------|-------------------------------------------------------------------|
| Network error / timeout    | `Snackbar` with "Retry" action shown immediately on the first failure. The request is then automatically retried once after 2 seconds; if the retry also fails, the `Snackbar` persists until the user taps Retry. This behaviour applies to all `IOException` and timeout errors across all screens. |
| 400 Bad Request            | Show server `error` message inline near the triggering UI element |
| 401 Unauthorized           | Navigate to Setup screen                                          |
| 404 Not Found (detail screen) | Show "Not found" inline in the detail pane                     |
| 404 Not Found (list screen)   | Navigate back to the Folder List with a Snackbar error          |
| 409 Conflict               | Show server `error` message inline                                |
| 500 Internal Server Error  | `Snackbar` with server `error` message                            |


---

## Date / Time Display

Timestamps are displayed in the device's local timezone using the same adaptive rules
as the web UI. Rules are evaluated top-to-bottom; the first matching rule wins.

| Age                | Format                     | Example           |
|--------------------|----------------------------|-------------------|
| < 1 minute         | "just now"                 | "just now"        |
| 1 min – < 1 hour   | Relative ("42 min ago")    | "42 min ago"      | ¹
| Same day, ≥ 1 hour | Time only (HH:mm, 24-hour) | "14:32"           | ³
| Yesterday          | "Yesterday HH:mm"          | "Yesterday 09:15" | ²
| 2–6 days ago       | Weekday + time             | "Mon 14:32"       |
| 7 days – same year | Short date + time          | "Apr 3, 14:32"    |
| Previous years     | Short date with year       | "Apr 3, 2023"     |

¹ "min" is an intentional abbreviation for the Android client; `REQUIREMENTS.md` uses
"minutes ago" (full word) for the web UI.

² "Yesterday" means the calendar day before today in the device's local timezone (any
time on that calendar date, regardless of elapsed hours). Because rules are evaluated
top-to-bottom, a message sent yesterday but within the last hour still matches the
"1 min – < 1 hour" rule first.

³ "Same day" means the same calendar day as today in the device's local timezone
(midnight boundary), consistent with the Yesterday definition in footnote ².

Message detail always shows the full form with timezone abbreviation:
`EEE, d MMM yyyy, HH:mm z` (e.g. "Mon, 3 Apr 2023, 14:32 CEST"). Use
`java.time.format.DateTimeFormatter` with the device's locale for day/month names and
a fixed 24-hour time pattern.
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
- Snooze — no snooze or edit-snooze actions; the Snoozed folder is accessible for
  reading, replying, forwarding, and moving messages, but deleting a snoozed message is
  not permitted (see action bar table)
- Filter editing — the Filters settings tab is read-only; add/edit/delete/reorder of
  filters is deferred to v1+
- Scheduled send — no "Send later" in Compose; Scheduled folder is shown with Delete only
- Folder reordering (entirely out of scope for v1)
- Drag-to-reorder for filters and identities (tap-to-reorder with up/down arrows as
  a simpler alternative is acceptable for v1)
- PGP / S-MIME
- Multi-account support
- Tablet adaptive layout (single-column phone layout is sufficient for v1)
- Download raw `.eml` file (`GET /messages/{id}/raw`)
- Flagged / starred messages — the `flagged` field is present in the API but no flag/star
  UI is exposed; a future version may add it
