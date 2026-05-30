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
| RFC 5322 parsing     | Apache MIME4J (`org.apache.james:apache-mime4j-core`) |


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
tasks.named("compileDebugUnitTestKotlin") { dependsOn("openApiGenerate") }
```

The generated code produces one Retrofit `interface` per API tag (e.g. `FoldersApi`,
`MessagesApi`, `DraftsApi`, …) and data classes for all schemas. Do not edit the
generated files; they are regenerated on every clean build.


---

## Networking

### Retrofit / OkHttp setup (`NetworkModule.kt`)

- Retrofit is managed by a Hilt `@Singleton` class `RetrofitHolder` (in `di/NetworkModule.kt`)
  that wraps an `AtomicReference<Retrofit>`. All repository classes inject `RetrofitHolder`
  and call `holder.get().create(FooApi::class.java)` to obtain API instances **on every
  request**; API interface instances must not be cached at field-initialisation time, since
  `holder.get()` may return a different `Retrofit` after a `rebuild()`. When the user
  changes the server URL in Setup, `RetrofitHolder.rebuild()` atomically replaces the inner
  `Retrofit` instance. Requests in-flight at the moment of `rebuild()` continue to
  completion using the prior instance and are handled normally; no special cancellation
  is required.
- A single `OkHttpClient` is shared. It has:
  - `BasicAuthInterceptor` — adds `Authorization: Basic …` from `CredentialStore`.
  - `ConnectTimeout` / `ReadTimeout` / `WriteTimeout`: 30 seconds each.
  - Logging interceptor (debug builds only). The logging interceptor must **never** log
    request headers (which contain the `Authorization` header with base64-encoded
    credentials). Only URL, method, response code, and response time may be logged.
    Credentials from `CredentialStore` must never appear in any `Log.*` call, crash
    report, or analytics anywhere in the app.
- The TLS configuration must never disable hostname verification or use a trust-all
  `TrustManager` / custom `SSLSocketFactory`. The default OkHttp TLS settings must be
  preserved; adding code that bypasses certificate validation (e.g., for "testing") is
  prohibited in any build variant.
- Declare a `res/xml/network_security_config.xml` and reference it in `AndroidManifest.xml`
  via `android:networkSecurityConfig`. In the release configuration,
  `cleartextTrafficPermitted` must be `false`. The debug override may permit cleartext to
  allow plain HTTP in debug builds — this complements the UI-level URL validation and
  enforces the restriction at the OS level so it cannot be bypassed by code paths that
  bypass the Setup screen.
- The `Authorization` header is added on every request. The server bypasses CSRF checks
  for requests without an `Origin` header, which is the standard native-client behaviour
  (see REQUIREMENTS.md → CSRF Protection). The server must only allow this bypass for
  requests authenticated via the `Authorization` header (not for unauthenticated requests
  or cookie-based sessions), so that the absence of an `Origin` header alone is not
  sufficient to bypass CSRF.

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
- Tapping **Connect** calls `GET /api/v1/folders` (an authenticated endpoint). On 200,
  stores credentials in `EncryptedSharedPreferences` and navigates to the Folder List.
  (`GET /api/v1/health` is not used here — it does not require authentication and cannot
  validate credentials.) On failure, shows an inline error message:
  - Network error / no connectivity: "Could not connect — check the server URL and your
    network connection"
  - HTTP 401: "Invalid username or password"
  - HTTP 404 or 503: "Server not reachable — check the server URL"
  - Any other non-200 response: "Connection failed (HTTP {status_code})"
- If credentials already exist, this screen is skipped at startup.
- The Server URL and Username fields are pre-filled with the currently saved values whenever credentials are already stored — both when navigated to via the **Change server** button in Settings and when redirected after a 401 response. The Password field is always left blank to avoid exposing the plaintext password in UI state. Track whether the Password field has been edited by the user (using a `passwordTouched` flag set on the first keystroke). If the user taps **Connect** and the Password field is empty and `passwordTouched` is false, the previously stored password is reused unchanged. If `passwordTouched` is true but the field is empty (the user deliberately cleared it), show an inline validation error: "Password is required". If the user types a new password, that value replaces the stored one.


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

Compose routes share the base route `compose` with optional arguments. Each optional
argument must be declared in the NavGraph with:
```kotlin
navArgument("argName") { type = NavType.StringType; nullable = true; defaultValue = null }
```
Omitting these declarations causes a `NavGraph` inflation failure at runtime. Affected
routes: `compose?replyTo={id}`, `compose?replyAllTo={id}`, `compose?forwardOf={id}`,
`compose?draftId={id}`.

### Deep-link URI patterns

Declare deep links on two destinations in `NavGraph.kt` so that `NavDeepLinkBuilder`
can synthesise the correct back-stack for notification `PendingIntent`s:

```kotlin
composable(
    route = "messages/{folderId}",
    deepLinks = listOf(navDeepLink { uriPattern = "mymail://messages/{folderId}" })
) { … }

composable(
    route = "setup",
    deepLinks = listOf(navDeepLink { uriPattern = "mymail://setup" })
) { … }
```

Register the `mymail://` scheme in `AndroidManifest.xml` with an `<intent-filter>` on
`MainActivity` so the OS can resolve these URIs:
```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW"/>
    <category android:name="android.intent.category.DEFAULT"/>
    <category android:name="android.intent.category.BROWSABLE"/>
    <data android:scheme="mymail"/>
</intent-filter>
```

### Navigation results for list refresh

When Message Detail performs an action that removes the current message from the Message
List (Move, Delete, Mark as junk, Not junk, Cancel scheduled send, Cancel snooze), it
signals the previous screen before calling `popBackStack()` by setting keys on the
previous back-stack entry's `SavedStateHandle`:

```kotlin
navController.previousBackStackEntry?.savedStateHandle?.set("needsRefresh", true)
navController.previousBackStackEntry?.savedStateHandle?.set("removedMessageId", messageId)
navController.popBackStack()
```

The Message List Screen observes `needsRefresh` and triggers a reload from offset 0:

```kotlin
val needsRefresh by navController.currentBackStackEntry
    ?.savedStateHandle
    ?.getStateFlow("needsRefresh", false)
    ?.collectAsState(initial = false)
    ?: remember { mutableStateOf(false) }

LaunchedEffect(needsRefresh) {
    if (needsRefresh == true) {
        viewModel.refresh()
        navController.currentBackStackEntry?.savedStateHandle?.set("needsRefresh", false)
    }
}
```

The Search Screen observes `removedMessageId` and removes the affected message locally:

```kotlin
val removedMessageId by navController.currentBackStackEntry
    ?.savedStateHandle
    ?.getStateFlow<Long?>("removedMessageId", null)
    ?.collectAsState(initial = null)
    ?: remember { mutableStateOf(null) }

LaunchedEffect(removedMessageId) {
    removedMessageId?.let { id ->
        viewModel.removeMessageById(id)
        navController.currentBackStackEntry?.savedStateHandle?.set("removedMessageId", null)
    }
}
```

Both keys are always set before `popBackStack()`, regardless of which screen is below
in the back-stack. The Message List only observes `needsRefresh`; Search only observes
`removedMessageId`. Each screen ignores the key it does not use.

The two strategies differ intentionally: Message List always shows a single folder, so a
full reload from offset 0 is cheap and keeps the list consistent with the server. Search
results span multiple folders with a user-specified query; re-running the search after
every back-navigation would be jarring, so the single removed message is struck from the
in-memory list instead.


---

## Built-in Folder IDs

Built-in folder IDs are hardcoded constants in the Android client; they must match the
values assigned by the server.

| Constant        | ID | Folder    |
|-----------------|----|-----------|
| `INBOX_ID`      | 1  | Inbox     |
| `SENT_ID`       | 2  | Sent      |
| `DRAFTS_ID`     | 3  | Drafts    |
| `TRASH_ID`      | 4  | Trash     |
| `SCHEDULED_ID`  | 5  | Scheduled |
| `SNOOZED_ID`    | 6  | Snoozed   |
| `JUNK_ID`       | 7  | Junk      |

User-created folders always have `id ≥ 100`.


---

## Folder List Screen

- Fetches `GET /api/v1/folders` on enter, whenever the screen becomes the current
  destination again (e.g. after back-navigation from a sub-screen), and on pull-to-refresh.
  Implement by observing the `NavBackStackEntry` lifecycle state: re-fetch when the entry
  transitions to `RESUMED` (i.e. `Lifecycle.State.RESUMED`). This keeps unread counts
  current after reading messages or mark-all-read operations.
- **Error state:** If the fetch fails, show a centred error message with a Retry button.
  While the error state is shown, the list is empty. The network-error Snackbar (see
  Error Handling table) also fires.
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
  - `send_failed` badge (boolean field, present in both the message-list and detail
    response models): shown only in Scheduled (yellow) and Drafts (red); the badge
    is not shown in other folders
- Row height adapts to the **Message list density** preference: Compact — 56 dp,
  Normal — 72 dp (default), Relaxed — 88 dp. The `send_failed` badge and attachment
  indicator icon are always vertically centred within the row regardless of the row
  height.
- Infinite scroll: loads the next page when the user reaches the end of the list
  (`offset += 50`). Stop paginating when the returned item count is less than 50.
  A page returning 0 items satisfies this condition and the empty result is discarded.
  When the total count is an exact multiple of 50 one extra empty request will be issued
  before pagination stops; this is intentional.
- **Empty state:** When the initial load returns 0 items, show a centred "No messages"
  text. **Error state on initial load:** Show a centred error message with a Retry
  button (the network-error Snackbar still fires; the inline Retry is additional).
  Exception: a 404 response (folder no longer exists) navigates back to the Folder
  List with a Snackbar error, per the Error Handling table — no inline Retry is shown.
- Pull-to-refresh reloads from offset 0. Refresh replaces the entire in-memory list
  with only the first page result; previously loaded pages beyond page 1 are discarded.
- **Mark all as read** action in the overflow menu (visible in normal mode only; hidden
  while multi-select is active; shown for all folders **except Drafts and Scheduled** where
  the read state is not meaningful) calls `POST /api/v1/folders/{folder_id}/mark-all-read`.
  On success, mark all currently loaded message rows as read locally (update their visual
  style without reloading from the server) and update the folder's unread-count badge to 0.
  New pages loaded via infinite scroll after a mark-all-read will have `read: true` from
  the server (the operation marks all messages atomically), so no visual inconsistency
  occurs for pages not yet in memory. On failure, show a Snackbar with the error message;
  the message list is not modified.
- **Empty folder** action (Trash and Junk only) appears in the overflow menu, hidden
  while multi-select is active. Calls `DELETE /api/v1/folders/{folder_id}/messages` after
  a confirmation dialog with the message "Permanently delete all messages in this folder?".
  On 200 success, reload the message list from offset 0 (the folder is now empty). On
  failure, show a Snackbar with the error message.
- Multi-select mode is not available in Drafts, Scheduled, or Snoozed folders — long-press
  has no effect in those folders. This prevents issuing bulk operations that the server
  rejects with 400 for messages in those folders.
- Long-press a message (in any other folder) to enter multi-select mode. During
  multi-select, the Compose FAB is hidden and the top app bar is replaced by a
  contextual action bar displaying the selected-item count and the three action buttons
  below. Toolbar actions in multi-select:
  - **Mark read / unread** — `PATCH /api/v1/messages` with `{"ids": […], "read": true/false}`.
    This bulk endpoint is distinct from the single-message `PATCH /api/v1/messages/{id}`
    used in Message Detail; both must be defined in the OpenAPI spec.
    **Toggle logic:** if at least one selected message is unread, send `"read": true` (mark
    all as read); only when every selected message is already read, send `"read": false`
    (mark all as unread).
    On success, update the read state of the affected rows locally. On 400 or 404, show a
    Snackbar with the server error message; no local changes are made.
  - **Move to folder** (folder picker dialog) — `POST /api/v1/messages/move` with body
    `{"ids": […], "folder_id": targetFolderId}`. The folder picker lists all folders
    except Scheduled, Snoozed, Drafts, and the current folder (consistent with the
    Message Detail picker). On success, remove the affected rows from the list and exit
    multi-select. On 400 or 404, show a Snackbar with the server error message; no local
    changes are made.
  - **Delete** — `DELETE /api/v1/messages` with `{"ids": […]}`. When the current folder is
    Trash or Junk, deletion is permanent — show a confirmation dialog before proceeding
    (consistent with single-message Delete in those folders). For all other folders,
    messages are moved to Trash and no confirmation is required. On 200 success, remove the
    affected rows from the list and exit multi-select. On 400 or 404, show a Snackbar
    with the server error message; no local changes are made.

Pressing the system Back button while in multi-select mode exits multi-select and
restores the normal top app bar and Compose FAB (same as the standard contextual-action-bar
Back behaviour on Android).


---

## Message Detail Screen

- Fetches `GET /api/v1/messages/{id}`. If the initial fetch fails, show a centred
  error message with a Retry button.
- After a successful fetch, if the message has `read: false`, issues
  `PATCH /api/v1/messages/{id}` with `{"read": true}` to mark as read. On 200, update
  the `read` flag in the locally held `MessageDetail` — the endpoint returns a
  `MessageSummary` (no body text), so no re-fetch is needed and the already-loaded
  detail continues to be displayed. Errors from this PATCH (network failure, 4xx, 5xx)
  are silently ignored — the message detail remains displayed unchanged.
- **Header section** (collapsed by default, expandable by tapping anywhere on the
  collapsed header row; a trailing chevron icon indicates the expand/collapse state):
  From, To, Cc, Bcc, Reply-To, Date, Subject. For messages in the Snoozed folder
  (`folder_id == SNOOZED_ID`), additionally show a **Snoozed until** row displaying the
  `snoozed_until` timestamp in the full date format (`EEE, d MMM yyyy, HH:mm z`). If
  `snoozed_until` is null (should not occur for correctly-stored Snoozed messages, but
  handled defensively), omit the row entirely. For messages in the Scheduled folder
  (`folder_id == SCHEDULED_ID`), additionally show a **Scheduled for** row displaying the
  `send_at` timestamp in the full date format (`EEE, d MMM yyyy, HH:mm z`). If `send_at`
  is null (should not occur for correctly-stored Scheduled messages, but handled
  defensively), omit the row entirely.
- **`send_failed` banner:** Show a banner immediately below the header only when
  `send_failed: true` AND `folder_id` is either `SCHEDULED_ID` (yellow) or `DRAFTS_ID`
  (red). Hidden in all other folders, including Trash where `send_failed` may be `true`
  but the message is no longer actionable. The `send_error` field (sendmail stderr from
  the last failed attempt) is not displayed in the UI; if non-null, log it at `Log.w` level
  for debugging.
- **Body section:** displays `body_text` in a `SelectionContainer` with a monospace
  font (plain text only in v1).
- **Attachments section:** lists all attachments by name and size. Tapping an
  attachment downloads it via `GET /api/v1/attachments/{id}`, writes it to a dedicated
  subdirectory inside the app's cache directory (`getCacheDir()/attachments/`), and
  opens it with `ACTION_VIEW` using a `FileProvider` URI (authority:
  `nu.staldal.mymail.fileprovider`). The FileProvider `<paths>` configuration must
  restrict shared paths to only the `attachments/` subdirectory (not all of
  `getCacheDir()` or `getFilesDir()`), so that other cached application data cannot be
  shared via FileProvider URIs. Before opening, check the attachment's `content_type`
  from `AttachmentMeta` (not the download response `Content-Type` header, which is always
  `application/octet-stream`) against a blocklist of dangerous MIME types. Strip MIME
  parameters before comparing (e.g. `text/html; charset=utf-8` → `text/html`). If the
  type is in the blocklist, show an inline error ("This file type cannot be opened for
  security reasons") and do not call `startActivity` — the file remains cached. Blocked
  types: `text/html`, `application/xhtml+xml`, `application/x-sh`,
  `application/x-shellscript`, `application/x-executable`,
  `application/vnd.android.package-archive`.
  For permitted types, the `ACTION_VIEW` `Intent` must include
  `FLAG_GRANT_READ_URI_PERMISSION` (not write) and no additional URI permission flags.
  Reject downloads whose `Content-Length` exceeds 100 MB; show an inline error message
  ("Attachment too large to download") and do not write any bytes to disk. Cached
  attachment files are deleted when the `MessageDetailScreen` leaves the composition
  (via `DisposableEffect`). No `WRITE_EXTERNAL_STORAGE` permission is needed on
  API 29+.
- **Thread section:** fetches `GET /api/v1/messages/{id}/thread`. The thread fetch is
  issued in parallel with the main message fetch (no dependency between the two). If the
  thread fetch fails while the main message fetch succeeded, show an inline error message
  and a Retry button within the thread section; the main message body continues to be
  displayed normally. Shown as a collapsible list of message summaries below the body,
  expanded by default.
  Tapping a summary navigates to that message's detail screen; pressing Back from there
  returns to whichever screen was below the originating detail entry in the back-stack (the
  Message List when navigated from there, or Search when navigated from a search result).
  Implement using:
  ```kotlin
  navController.navigate("message/$threadMsgId") {
      popUpTo("message/$currentMsgId") { inclusive = true }
  }
  ```
  This replaces the current detail entry in the back-stack so that pressing Back lands
  on whichever screen was below the current detail entry: the Message List when navigating
  from there, or Search when navigating from a search result. When `truncated` is true,
  show a "Thread too long" notice.

### Action bar (varies by folder)

The action bar uses the `folder_id` field from the fetched message object to determine
which row of the table below applies. This is true regardless of how the user navigated
to the screen (from a Message List, from Search, or from a thread). Match `folder_id`
against the built-in constants from the [Built-in Folder IDs](#built-in-folder-ids)
section; any `folder_id ≥ 100` is a user folder and falls in the same row as Inbox.

| Folder           | Actions shown                                                              |
|------------------|----------------------------------------------------------------------------|
| Inbox (`folder_id == INBOX_ID`) or user folders (`folder_id ≥ 100`) | Reply, Reply All, Forward, Move, Mark as junk, Delete    |
| Sent             | Forward, Move, Delete                                                      |
| Drafts           | Edit (→ Compose), Discard (with confirmation)                              |
| Scheduled        | Cancel scheduled send (→ Drafts)                                           |
| Snoozed          | Reply, Reply All, Forward, Cancel snooze                                   |
| Junk             | Not junk, Move, Delete (permanent)                                         |
| Trash            | Move, Delete (permanent)                                                   |

**Move** opens a folder picker bottom sheet (lists all folders except Scheduled,
Snoozed, Drafts, and the current folder, where "current folder" is the message's
`folder_id` field — not the folder the user navigated from).

**Mark as junk** calls `POST /api/v1/messages/{id}/mark-junk`. On success call
`navController.popBackStack()` to return to the Message List and trigger a full list
refresh; the app stays in the current folder (does not navigate to Junk).
On 400 (message is already in Junk, or is in Drafts, Scheduled, or Snoozed — race condition),
show a Snackbar with the server error message.

**Not junk** calls `POST /api/v1/messages/{id}/mark-not-junk`. On success call
`navController.popBackStack()` to return to the previous screen and trigger a full list
refresh. The message is moved to Inbox server-side; the refresh removes it from the
Junk message list. On 400 (message is no longer in Junk — race condition),
show a Snackbar with the server error message.

**Discard** calls `DELETE /api/v1/drafts/{id}` after a confirmation dialog. On 204
success, call `navController.popBackStack()` to return to the Message List and trigger a
full list refresh so the discarded draft no longer appears. On 404 (draft was already
discarded from another client — race condition), show a Snackbar ("Draft already
discarded") and call `navController.popBackStack()`. In both cases (204 and 404), set the
navigation result keys (`needsRefresh = true`) on the previous back-stack entry before
calling `popBackStack()` — the draft is gone in either case and the Message List must refresh.

The **Delete** action for Inbox, user folders, and Sent calls `DELETE /api/v1/messages/{id}`,
which moves the message to Trash (non-permanent). No confirmation dialog is required — the
action is reversible by visiting Trash.

The **Delete** action in Junk and Trash folders is permanent: it calls `DELETE /api/v1/messages/{id}`,
which permanently removes the message immediately. Show a confirmation dialog before proceeding
(same pattern as Discard in Drafts).

**Cancel scheduled send** calls `DELETE /api/v1/scheduled/{id}`, which moves the message to
Drafts (not permanent deletion — `DELETE /api/v1/messages/{id}` rejects Scheduled messages with
400). Show a confirmation dialog before proceeding. On success, call
`navController.popBackStack()` to return to the Message List and trigger a full list
refresh. On 404 (the scheduler already sent or moved the message before the user
confirmed — race condition), show a Snackbar ("Message was already processed") and call
`navController.popBackStack()`.

**Cancel snooze** calls `DELETE /api/v1/messages/{id}/snooze`, which returns the message
to the folder it was in before snoozed (or Inbox if that folder was deleted). No
confirmation dialog. On success, call `navController.popBackStack()` to return to the
Snoozed Message List and trigger a full list refresh so the message no longer appears.
On 400 (message is no longer in the Snoozed folder — race condition), show a Snackbar
with the server error message and call `navController.popBackStack()`. On 404 (message
no longer exists — race condition), show a Snackbar with the server error message and
call `navController.popBackStack()`.

The **Move** action (single message) calls `POST /api/v1/messages/move` with body
`{"ids": [id], "folder_id": targetFolderId}` — the same bulk endpoint used in
multi-select, with a single-element array.

All the destructive actions above (**Move**, **Delete**, **Discard**, **Mark as junk**,
**Not junk**, **Cancel scheduled send**, **Cancel snooze**) call `navController.popBackStack()`
as documented in each paragraph above — do not add a second `popBackStack()` call here.
Each of these calls must be preceded by setting the navigation result keys on the
previous back-stack entry as described in [Navigation results for list refresh](#navigation-results-for-list-refresh), so that the Message List reloads
from offset 0 when the user returns to it.


---

## Compose Screen

Supports new mail, reply, reply-all, forward, and draft editing.

**Fields:**

| Field      | Notes                                                                          |
|------------|--------------------------------------------------------------------------------|
| From       | `DropdownMenu` populated from `GET /api/v1/identities`. For new compose, pre-select the identity with `is_default: true`. For Reply/Reply-All, pre-select the identity whose address matches a To or Cc address of the source message; fall back to the default identity if no match is found. If the identities fetch returns an empty list, show a centred error: "No sending identities configured — go to Settings → Identities to add one." Disable all form fields and the Send button; the user can navigate back. The auto-save loop must not start in this state. |
| To         | Chip text field with autocomplete from `GET /api/v1/contacts?q=…&limit=10`. Autocomplete fires after the user types at least 1 character, debounced at 300 ms. When the text input is cleared, close the dropdown and show no suggestions. When the query returns no contacts, close the dropdown (no "no results" item). When the response `total` exceeds 10, show a non-selectable hint item "Type more to narrow…" at the bottom of the dropdown instead of paginating. Tapping a suggestion adds a chip; the chip displays the contact's `name` if non-empty, otherwise the bare `address`; the chip encodes the address in RFC 5322 format (`"Name" <address>` when name is non-empty, bare `address` otherwise) for use in the `to_addr` / `cc_addr` / `bcc_addr` save fields. The user may also type an address manually and commit it as a chip by pressing Enter, comma, or Tab; no client-side RFC 5322 validation is performed — invalid addresses are accepted and reported by the server as a 400 on Send. Maximum 8192 characters (RFC 5322 comma-separated addresses) — enforce as a hard cap (stop accepting input at the limit). |
| Cc         | Same as To (collapsed by default, expand via button)                           |
| Bcc        | Same as To (collapsed by default)                                              |
| Reply-To   | Single plain text field (optional, collapsed by default). Maximum 8192 characters — hard cap. |
| Subject    | Single-line text field. Maximum 998 characters — hard cap. Strip all CR (`\r`) and LF (`\n`) characters from the subject before saving or sending, to prevent MIME header injection. |
| Body       | Multi-line plain text field (`OutlinedTextField`, v1 is plain text only)       |
| Attachments| List of attached files; **Add attachment** button opens the system file picker |

**Pre-population for Reply / Reply All / Forward:**
Fetch the source message (`GET /api/v1/messages/{id}`) and identities
(`GET /api/v1/identities`) in parallel. While the fetches are in-flight, show a circular
progress indicator over disabled/blank fields. If either fetch fails, show a centred error
with a Retry button and do not populate any fields until both succeed. The auto-save loop
must not start until both fetches succeed — starting before pre-population would save empty
or partially filled fields as the first draft version. Pre-fill fields as follows:

| Field   | Reply                                              | Reply All                                                                 | Forward          |
|---------|----------------------------------------------------|---------------------------------------------------------------------------|------------------|
| To      | Source Reply-To if present, else source From, excluding own identities | Source Reply-To if present, else source From (same as Reply), excluding own identities | (empty)          |
| Cc      | (empty)                                            | All source To/Cc recipients, excluding own identities                     | (empty)          |
| Bcc     | (empty)                                            | (empty)                                                                   | (empty)          |
| Subject | `Re: ` + source subject (suppress duplicate `Re:` prefixes — strip all leading `Re:` prefixes case-insensitively using `^(?i)(re:\s*)+` before prepending `Re: `) | Same as Reply | `Fwd: ` + source subject (suppress duplicate `Fwd:` prefixes — strip all leading `Fwd:` prefixes case-insensitively using `^(?i)(fwd:\s*)+` before prepending `Fwd: `) |
| Body    | Attribution line + quoted source body (see below)  | Same as Reply                                                              | Original headers + quoted source body (see below) |

`reply_to_addr` is treated as a full RFC 5322 comma-separated address list; all addresses
it contains (after excluding own identities) become `To` chips, not just the first one.

"Excluding own identities" means: omit any address whose email address matches one of
the user's identity addresses (comparison is case-insensitive on both local part and
domain, per RFC 5321 convention). Plus-addressed variants of an identity address are also treated as own — i.e. an address `local+tag@domain` matches identity `local@domain`.
This applies even if every candidate in the `reply_to_addr` list matches an own identity —
in that case `To` is left empty and the user must fill it in manually.

**Quoted body format — Reply / Reply All:**
```
On <date>, <from address> wrote:
> <source body, each line prefixed with "> ">
```
`<date>` is formatted using RFC 1123 (`DateTimeFormatter.RFC_1123_DATE_TIME`, e.g. `Mon, 02 Jan 2006 15:04:05 GMT`).

**Quoted body format — Forward:**
```
---------- Forwarded message ----------
From: <source From>
Date: <source Date formatted as RFC 1123, same as reply attribution>
Subject: <source Subject>
To: <source To>

<source body>
```

**Signature pre-population:**
The selected identity's `signature` field is appended to the initial body in all compose
modes (new, reply, reply-all, forward). When the signature is non-empty, append the
standard email signature delimiter followed by the signature text:

```
\n\n-- \n<signature>
```

For new compose the body starts as just the signature block (the user types above it):
`\n\n-- \n<signature>`. The leading `\n\n` provides two blank lines at the top.

For reply/reply-all and forward, the signature is appended after the quoted/forwarded block:

```
\n\n<attribution or forwarded headers block>\n\n-- \n<signature>
```

The leading `\n\n` provides two blank lines at the top where the user types their response;
the cursor is positioned at offset 0. When the identity has an empty signature, the initial
body for reply/reply-all/forward is just `\n\n` + the attribution/forwarded block (no
delimiter appended).

When the selected identity has an empty signature, no delimiter or signature text is
appended. When the user changes the From identity after the screen opens, the signature
block at the bottom of the body is replaced with the new identity's signature (or removed
if the new identity has no signature). Only the trailing signature block is replaced; any
text the user has typed above it is preserved. Track the current signature string so the
replacement can be found precisely by searching for `\n\n-- \n<currentSignature>` at the
end of the body string. If this exact match fails (the user may have edited the signature
text directly in the body), fall back to searching for the last occurrence of `\n\n-- \n`
in the body and treat everything from that point to the end as the signature block to
replace. When the initial identity had an empty signature (so no delimiter was ever
appended) and the user switches to an identity with a non-empty signature — and no
`\n\n-- \n` delimiter is found anywhere in the body — simply append `\n\n-- \n<newSignature>`
at the end of the body.

For Forward, pass `source_message_id` in the initial `POST /api/v1/drafts` body so the
server copies attachments at draft-creation time. `source_message_id` is included only
in this first `POST`; subsequent `PUT /api/v1/drafts/{id}` calls never resend it.
`source_message_id` is only used for Forward; Reply and Reply-All omit it.

For Reply and Reply-All, set the threading fields in the initial `POST /api/v1/drafts`
and repeat them in every subsequent `PUT /api/v1/drafts/{id}`:
- `in_reply_to`: the source message's `message_id` field value (omit the field entirely
  if `message_id` is null).
- `references`: the source message's `references` list with `<{source.message_id}>`
  appended (note: `MessageDetail.message_id` has no angle brackets, so wrap it when
  appending). No deduplication — append unconditionally. Omit the field if both the
  source `references` list is empty and `message_id` is null.

**Auto-save:**
When the screen opens via `compose?draftId={id}`, initialise the ViewModel's `draftId`
from the navigation argument, then fetch the existing draft via `GET /api/v1/messages/{id}`
to pre-populate all form fields (From, To, Cc, Bcc, Reply-To, Subject, Body, and any
existing server-side attachments shown as removable chips). There is no dedicated
`GET /api/v1/drafts/{id}` endpoint — drafts are regular messages accessible via the
messages endpoint. To pre-populate the From dropdown, parse the draft's `from_addr` as an RFC 5322
address string to extract the bare addr-spec (e.g. `"Alice Smith" <alice@example.com>`
→ `alice@example.com`), then match that addr-spec against each identity's `address` field
using case-insensitive comparison; if no identity matches, pre-select the default identity. To pre-populate the address chip fields (To,
Cc, Bcc), parse the `to_addr`, `cc_addr`, and `bcc_addr` strings from the `MessageDetail`
response as RFC 5322 comma-separated address lists into individual chips; each chip
displays the display name if present, or the bare email address otherwise. Use
`org.apache.james:apache-mime4j-core` (`MimeUtility` / `AddressList.parse()`) for all
RFC 5322 address parsing throughout the app — it correctly handles quoted display names
that contain commas (e.g. `"Smith, Alice" <a@b.com>`) and other edge cases. If this fetch fails, show a
centred error message with a Retry button and do not start the auto-save loop until the
fetch succeeds — this prevents the loop from overwriting the server draft with blank
fields. Pre-populating fields from the fetched draft does not set the dirty flag; only
subsequent user edits mark the draft dirty. The auto-save loop then uses
`PUT /api/v1/drafts/{id}` from the very first save and never calls `POST /api/v1/drafts`.

Start an auto-save coroutine on a 30-second `delay` loop. On first save (new compose),
call `POST /api/v1/drafts` and store the returned `id` from the 201 response. On subsequent saves call
`PUT /api/v1/drafts/{id}`. Track a dirty flag; mark dirty on any field edit, clear it
after each successful save. If the first `POST /api/v1/drafts` fails with a network or server error (non-400), show a
non-blocking informational Snackbar ("Draft could not be saved — will retry") with no Retry
action button — the auto-save loop retries automatically at the next tick. Keep the dirty
flag set and let the next loop tick retry the POST. For Forward mode, `source_message_id`
must be included in every retry attempt of the initial `POST` — it is only omitted from
subsequent `PUT` calls once the `POST` has succeeded.
If the first `POST /api/v1/drafts` fails with a **400** response (e.g. the forwarded
source message no longer exists), stop the auto-save loop permanently and show a persistent
non-dismissible inline error above the compose fields with the server error message. The
user should navigate back; the draft cannot be saved in this state. The `onCleared()`
final-save path is also skipped if a permanent 400 failure has occurred.
The draft is lost only if the user navigates away before any POST succeeds (in the
non-400-failure case).

Use a `Mutex` (`saveMutex`) to serialise save operations. Both the auto-save loop body
and the `onCleared()` final-save coroutine must acquire `saveMutex` before checking the
dirty flag and performing a save. This prevents a double-save when the loop is cancelled
mid-flight and `onCleared()` runs immediately after.

If the user navigates away before saving, cancel any in-flight auto-save and, in
`ComposeViewModel.onCleared()`, launch a coroutine with `NonCancellable` context to
perform a final save if the dirty flag is set (ViewModel is cleared when the screen
leaves the back-stack). Note: `onCleared()` is not called when the OS kills the process
under memory pressure; unsaved draft state is lost in that case. No recovery mechanism
is needed for v1.

The auto-save request body is a `DraftRequest` JSON object with fields: `identity_id`
(integer — the ID of the selected identity from the From dropdown), `to_addr` (string),
`cc_addr` (string), `bcc_addr` (string), `reply_to_addr` (string, may be empty),
`subject` (string), `body_text` (string). For Reply and Reply-All (live session), also
include `in_reply_to` and `references` as described above — these fields are present in
every save call (both `POST` and every `PUT`) for the lifetime of the reply draft. For
draft edit mode (`compose?draftId={id}`), read `in_reply_to` and `references` from the
fetched `MessageDetail` response and include them in every save call if non-empty — this
preserves threading fields that were set when the draft was originally created as a reply.
For new compose and Forward modes, omit `in_reply_to` and `references`.
The `body_html` and `send_at` fields are intentionally omitted — v1 is plain-text only,
and scheduled send is out of scope; their absence in a PUT causes the server to clear
those fields, which is the correct behaviour. Address fields use RFC 5322
comma-separated format for multiple addresses (e.g. `"Alice <a@b.com>, Bob <c@d.com>"`).
Note: for Forward, the initial `POST /api/v1/drafts` additionally includes
`source_message_id` as described in the Forward pre-population paragraph above; all
subsequent `PUT` calls for Forward omit it. No auto-save status indicator is shown to
the user; the only auto-save feedback is the failure Snackbar ("Draft could not be
saved — will retry"). It never uploads, creates, or deletes attachments; attachments
are handled via immediate upload on file selection (see Attachments section) and
immediate delete for existing draft attachments. Note: `PUT /api/v1/drafts/{id}` does
not modify existing attachment rows — they are preserved and only replaced wholesale via
`PUT /api/v1/drafts-with-attachments/{id}`. This means the auto-save loop running
concurrently with an attachment upload will not overwrite any uploaded attachments.

**Send:**
The Send button is disabled when all three recipient fields (To, Cc, Bcc) are empty;
it becomes enabled as soon as any one of them is non-empty. Before initiating Send,
cancel the running auto-save job. Disable the Send button while in flight. If no `draftId` exists yet (the user tapped Send before the first 30-second
auto-save fired), perform a synchronous `POST /api/v1/drafts` first to obtain one, then
proceed. Because newly added files are uploaded immediately on selection (not deferred to
send time), no attachment upload step is needed here. Always send via
`POST /api/v1/drafts/{id}/send`.
On 201 or 202, navigate back (the draft is consumed by the send operation; the auto-save
loop is not restarted). 202 is theoretically unreachable because v1 never sets `send_at`,
but accepting it is harmless.
On 400/500 show the server error message inline above the Send button and restart the
auto-save loop so subsequent edits continue to be auto-saved. If a `draftId` was obtained
via a synchronous `POST /api/v1/drafts` immediately before the send attempt, it is
retained; the restarted auto-save loop uses `PUT /api/v1/drafts/{id}` from that point on.
On 404 (draft was discarded from another client between the auto-save and the send), show
the server error message inline above the Send button; do not restart the auto-save loop
since the draft no longer exists on the server.

**Attachments:**
Attach files using `ActivityResultContracts.GetMultipleContents`. Newly added files are
uploaded **immediately on selection** rather than deferred to send time. Because
`PUT /api/v1/drafts-with-attachments/{id}` replaces all attachments wholesale, the upload
request must include ALL currently retained attachments: re-download any existing
server-side attachments via `GET /api/v1/attachments/{id}` (caching them in the app's
cache directory is acceptable) and include them alongside the new files as `attachments`
parts in the same `multipart/form-data` PUT. Each re-uploaded attachment part must use
the `filename` from `AttachmentMeta` as the `Content-Disposition` filename and the
`content_type` from `AttachmentMeta` as the part's `Content-Type`. Note that
`PUT /api/v1/drafts-with-attachments/{id}` also replaces draft content (same as
`PUT /api/v1/drafts/{id}`), so the `message` part of the multipart request must always
contain the current form field values (same `DraftRequest` body as the auto-save) in
addition to the `attachments` parts; omitting the `message` part clears all draft text
fields. While re-downloading, show a loading indicator in the attachments area. If any
re-download fails, show an inline error with a Retry button in the attachments area and
abort the upload. If no `draftId` exists at file-selection time, acquire `saveMutex` and,
if `draftId` is still null inside the lock, call `POST /api/v1/drafts` with the current
form field values (same request body as the auto-save) to obtain one; release the lock
before proceeding with the upload. Acquiring `saveMutex` here prevents the auto-save loop
from creating a duplicate draft simultaneously. Show an inline error if the upload fails;
the user can retry by tapping a Retry button in the attachments area.

For draft edits, existing server-side attachments are shown as removable chips; tapping
× calls `DELETE /api/v1/drafts/{id}/attachments/{attachment_id}` immediately.

Re-downloaded attachment files cached in `getCacheDir()/attachments/` during the
re-upload flow are deleted when the Compose screen leaves the composition (via
`DisposableEffect`), consistent with the Message Detail attachment cleanup policy.


---

## Search Screen

- Search bar at the top (triggered by clicking the search icon in the Folder List).
- Optional folder filter: `DropdownMenu` listing all folders; default is "All mail" — when
  `folder_id` is omitted, the API searches all folders **except** Junk (id=7), Drafts (id=3),
  and Scheduled (id=5). The UI label or tooltip should clarify this exclusion to avoid user
  confusion.
- Optional date range: two `DatePicker` dialogs (From date, To date).
- Calls `GET /api/v1/messages/search?q=…&folder_id=…&date_from=…&date_to=…&limit=50&offset=0`.
  `date_from` and `date_to` are RFC 3339 timestamps with timezone offset.
  When a date filter is set from a `DatePicker` (which gives a local calendar date),
  convert to the start of that day in the device's local timezone for `date_from` and to
  the start of the following calendar day in the device's local timezone for `date_to`
  (exclusive upper bound). Use `ZonedDateTime.of(localDate, LocalTime.MIDNIGHT,
  ZoneId.systemDefault())` to produce an RFC 3339 timestamp with the correct timezone
  offset (e.g. `2025-01-15T00:00:00+02:00`).
- The search query field has a maximum length of 500 characters (hard cap, matching the
  server's `maxLength` validation). Do not issue a search request when the query string is
  empty or whitespace-only; display an initial placeholder state (e.g. "Enter a search
  query") instead. The server returns 400 for empty/whitespace queries.
- Whenever the query text or any date filter changes, reset `offset` to 0 and discard
  previously loaded results before issuing a new request. Pull-to-refresh reloads from
  offset 0 with the current filters applied.
- Results rendered as a `LazyColumn` of message summaries, each showing the FTS
  `snippet` below the subject. The `snippet` field is guaranteed non-null in search
  results (it is in the `required` list of the search response schema). The snippet
  contains matched keywords surrounded by `**` markers (e.g. `…the **keyword** in…`).
  Parse these markers and render matched terms in bold using `AnnotatedString` with
  `SpanStyle(fontWeight = FontWeight.Bold)`; strip the `**` delimiters from the displayed
  text.
- Tapping a result navigates to Message Detail.
- When returning from Message Detail after a destructive action (Delete, Move, Discard,
  Mark as junk, etc.), the Search screen observes the `removedMessageId` key on its
  `SavedStateHandle` (set by Message Detail before `popBackStack()`) and removes the
  affected message from the in-memory results list without re-fetching. See the
  [Navigation results for list refresh](#navigation-results-for-list-refresh) section.
- Long-press is not supported; multi-select is not available on the Search screen (results
  span multiple folders including restricted ones that the server rejects for bulk
  operations).
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
| Identities   | Read-only list of identities                       |
| Folders      | CRUD list of user folders                          |
| Filters      | Read-only list of filters (editing is out of scope for v1) |
| Spam         | Enable/disable toggle, score header, threshold     |
| Contacts     | Paginated contact list; add / edit / delete        |
| Preferences  | App-level preferences (see below)                  |

**Identities tab:** Fetches `GET /api/v1/identities` on enter. Show an inline error with
a Retry button if the fetch fails. The API returns identities already ordered by position,
then id; display them in the server-returned order. Each row shows name and address.
Identity management (create, edit, delete, set default) is out of scope for v1.

**Folders tab:** Fetches `GET /api/v1/folders` on enter. Show an inline error with a
Retry button if the fetch fails. Only user-created folders (id ≥ 100) can be renamed or
deleted. Built-in folders are shown but edit/delete controls are hidden. A **+** FAB
opens a simple dialog with a single Name text field to create a new folder. Renaming a
folder opens the same dialog pre-filled with the current name. Deleting a folder shows
a confirmation dialog with the message "Messages will be moved to Trash". Mutation
endpoints:

| Operation | Endpoint                          |
|-----------|-----------------------------------|
| Create    | `POST /api/v1/folders`            |
| Rename    | `PATCH /api/v1/folders/{id}`      |
| Delete    | `DELETE /api/v1/folders/{id}`     |

For Create, on 201 success, dismiss the dialog and reload the folder list. For Create and Rename, on 409 Conflict (duplicate folder name), show the server error
message inline in the dialog. For Rename, on 400 (e.g. attempting to rename a built-in
folder, which the UI should prevent but handle defensively), show the server error message
inline in the dialog. For Rename, on 404 (folder was deleted between list load
and the rename attempt), close the dialog and show a Snackbar with the error message.

**Filters tab:** Fetches `GET /api/v1/filters` on enter. Show an inline error with a
Retry button if the fetch fails. Each filter row shows the filter name on the first line
and a one-line summary on the second line. Build the summary as follows:

- **Condition part:** list every non-empty match field joined with " AND ":
  `from contains '<value>'`, `to contains '<value>'`, `subject contains '<value>'`.
  Example: `from contains 'newsletter@' AND subject contains 'weekly'`. If all three
  match fields are empty (not possible via v1's read-only UI, but defensive handling for
  legacy data), display the condition as "(matches all)".
- **Action part:** map the `action` enum to a friendly string:
  - `move` → `Move to <folder name>` (look up the folder name from the same folder list
    fetched for the Folders tab; if the folder is not found, fall back to
    `Move to folder #<id>`)
  - `trash` → `Move to Trash`
  - `mark_read` → `Mark as read`
  - `drop` → `Drop`
- **Full summary:** `If <condition> → <action>`

The `stop` flag is not shown in the row. Read-only in v1 — no add, edit, delete, or
reorder. Filter editing is deferred to v1+.

To display folder names in the "move" action, the Filters tab reuses the folder list from
the same `GET /api/v1/folders` call as the Folders tab. Fetch this list on tab enter
(shared with the Folders tab; one request is sufficient if both tabs are displayed in the
same settings session).

**Spam tab:** Toggle (`enabled` boolean field) plus two text fields for score header name
(`score_header`) and threshold (`score_threshold`), and an explicit **Save** button. On
enter, call `GET /api/v1/spam-filter` to load the current settings into the form fields.
Show a loading indicator while fetching; show an inline error with a Retry button if the
fetch fails. Tapping **Save** calls `PUT /api/v1/spam-filter` with the current form values.
On success, show a brief informational Snackbar ("Settings saved"). On 400, show the server
error message inline near the relevant field.
The `score_threshold` field is
a non-negative floating-point number (`minimum: 0`; a value of `0` means "match any
message whose score header parses as a number"); use a decimal-accepting input (not
integer-only). The `score_header` field must be non-empty after trimming.

**Contacts tab:** Paginated list via `GET /api/v1/contacts?q=…&limit=50&offset=0`.
Contacts are returned ordered by name (empty names last), then address. Each contact has
a **name** (display name) and an **email address**; the list row shows the name on the
first line and the email on the second line. Show an inline error with a Retry button if
the initial fetch fails; if a subsequent infinite-scroll page fetch fails, show an inline
error with a Retry button at the bottom of the list (retries the same page at the failed
`offset`, not a full reload from offset 0). Search field filters via `q=`;
keystrokes are debounced with a 300 ms delay before issuing a request. When the search
field is cleared, re-issue the request with no `q=` parameter to restore the full
paginated list. When the query changes (including clearing), reset `offset` to 0 and
discard previously loaded results before issuing a new request. Infinite scroll with `offset += 50`; stop paginating when the returned count is
less than 50. Tapping a contact opens an edit dialog; a **+** FAB creates a new contact.
Both add and edit dialogs have Name (optional) and Email (required, RFC 5322 addr-spec)
fields. On 409 Conflict (duplicate address), show the server error message inline in the
dialog. Deleting a contact shows a confirmation dialog before calling
`DELETE /api/v1/contacts/{id}`. Add / edit / delete. Mutation endpoints:

| Operation | Endpoint                          |
|-----------|-----------------------------------|
| Create    | `POST /api/v1/contacts`           |
| Update    | `PUT /api/v1/contacts/{id}`       |
| Delete    | `DELETE /api/v1/contacts/{id}`    |

For Create, on 201 success dismiss the dialog and reload the list.
For Update (`PUT /api/v1/contacts/{id}`), on 200 success dismiss the dialog and reload the
list. On 404 (contact deleted from another client between list load and the edit submit),
close the dialog and show a Snackbar with the server error message; reload the list so the
deleted contact disappears. On 409 Conflict (duplicate address), show the server error
message inline in the dialog. On 400, show the server error message inline in the dialog.
`PUT /api/v1/contacts/{id}` uses **full-replacement semantics**: any field omitted from the
request body is cleared (omitting `name` sets it to empty string). The dialog must always
send both `address` and `name` fields, even when `name` is an empty string.

**Preferences tab:** These are stored in regular `SharedPreferences` (not encrypted):

| Preference          | Default    | Notes                                           |
|---------------------|------------|-------------------------------------------------|
| Dark mode           | System     | System / Light / Dark                           |
| Message list density| Normal     | Compact / Normal / Relaxed (row height)         |
| New-mail notifications | Off    | Enables Android notification channel; requests POST_NOTIFICATIONS permission at the moment the user toggles the preference on (if not already granted). If the user denies the permission, revert the toggle to Off and show a Snackbar explaining that the notification permission was denied. |

There is also a **Server** entry (not a tab) at the bottom of the Settings screen that
shows the current server URL and a **Change server** button which navigates to Setup.


---

## New Mail Notifications

### Channel registration

The `mymail_new_mail` `NotificationChannel` must be created in
`MyMailApplication.onCreate()` before any notification is posted. Creation is
idempotent — calling `createNotificationChannel()` on an existing channel is a no-op.

### Foreground polling

While the app is in the foreground, a coroutine on `MainActivity.lifecycleScope` polls
`GET /api/v1/folders` every 30 seconds. The in-memory baseline resets on activity
recreation (e.g. screen rotation), consistent with the "reset on each app launch"
behaviour — the first poll after recreation does not fire a notification. The result of the first poll is used as the
in-memory baseline (reset on each app launch); no notification fires on that first
result. On every subsequent poll, when the Inbox `unread_count` is higher than the
previous value, fire an Android notification (if permission granted and preference
enabled) and update the in-memory baseline to the new `unread_count`. (The launcher
badge count is derived automatically from active notifications, not managed directly.) If a poll fails (network error or non-401 HTTP
error), log the failure and continue — no UI is shown, and the next 30-second tick
retries automatically. A 401 response is handled by the `AuthEventBus` path (see
Authentication section) and is not specific to the poller.

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
**Notification content (new mail):** title = "New mail", body = "You have new messages
in your inbox".
Tapping the notification launches the app and navigates to the Inbox message list.
Use `NavDeepLinkBuilder` with the `mymail://messages/{folderId}` deep-link URI
(substituting `INBOX_ID`); Compose Navigation synthesises the back-stack automatically
so the user can press Back from Inbox to reach the Folder List.

The background worker checks whether the app is currently in the foreground and skips
its poll and notification if so, deferring to the foreground poller. Foreground state
is tracked via an `AtomicBoolean isAppInForeground` property in `MyMailApplication`,
set to `true` in `DefaultLifecycleObserver.onStart()` and `false` in `onStop()`. The
observer is registered in `MyMailApplication.onCreate()`. The worker reads this flag
directly since it runs in the same process.

Because `MailPollingWorker` needs Hilt-injected dependencies (`RetrofitHolder`,
`CredentialStore`, `SharedPreferences`), annotate it with `@HiltWorker` and inject via
`@AssistedInject`. Wire `HiltWorkerFactory` in `MyMailApplication`:
```kotlin
@HiltAndroidApp
class MyMailApplication : Application(), Configuration.Provider {
    @Inject lateinit var workerFactory: HiltWorkerFactory
    override val workManagerConfiguration get() =
        Configuration.Builder().setWorkerFactory(workerFactory).build()
}
```
Remove the default `WorkManager` initializer from `AndroidManifest.xml` by adding:
```xml
<provider
    android:name="androidx.startup.InitializationProvider"
    android:authorities="${applicationId}.androidx-startup"
    tools:node="remove" />
```

The background worker persists the last-known Inbox unread count in `SharedPreferences`
file `"mymail_prefs"` under the key `"inbox_unread_count"`. This is the same
`SharedPreferences` file used by the Preferences tab for non-sensitive app settings.

**Storage separation:** `"mymail_prefs"` (plain `SharedPreferences`) must contain only
non-sensitive values: `inbox_unread_count`, dark-mode preference, message-list density
preference, and the new-mail notification preference. The server URL, username, and
password must be stored exclusively in `EncryptedSharedPreferences` and must never be
written to plain `SharedPreferences`.

The background worker is enqueued immediately after a successful Connect on the Setup
screen, and again on app cold-start if credentials are already stored, using
`ExistingPeriodicWorkPolicy.KEEP` so duplicate enqueues are no-ops and it survives
app restarts.

If the worker receives HTTP 401: cancel the periodic work request so polling stops until
the user re-authenticates (the worker is re-enqueued after a successful Setup). If
`isAppInForeground` is false, also post a notification on the `mymail_new_mail` channel
with title = "Session expired" and body = "Tap to sign in again", using
`NavDeepLinkBuilder` with the `mymail://setup` deep-link URI. If `isAppInForeground` is
true, skip posting the notification — the `AuthEventBus` path in `MainActivity` handles
navigation to Setup for the foreground case.

Clear all posted new-mail notifications, reset the persisted unread-count baseline in
`SharedPreferences` (`"inbox_unread_count"` key in `"mymail_prefs"`), and reset the
in-memory foreground-poller baseline when the Inbox `MessageListScreen` composable
enters composition. Implement via `LaunchedEffect(Unit)` in the Inbox message list composable — this fires
once each time the Inbox screen enters the composition tree (forward navigation,
back-navigation into it, and screen rotation each cause a new composition entry).


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
  `lifecycleScope` and, only when `isAppInForeground` is `true`, executes:
  ```kotlin
  navController.navigate("setup") { popUpTo(0) { inclusive = true } }
  ```
  The navigation call is a no-op if `navController.currentDestination?.route` is already
  `"setup"` — this prevents duplicate back-stack manipulations when several simultaneous
  401 responses are emitted. This clears the entire back-stack and presents the Setup
  screen. When the app is in the background the collector skips navigation; the background
  worker's own 401 path (post "Session expired" notification, cancel periodic work)
  handles that case instead. After a successful reconnect, `RetrofitHolder.rebuild()`
  rebuilds the `Retrofit` instance with the new base URL. The `OkHttpClient` is not
  rebuilt; credentials are picked up automatically on the next request because
  `BasicAuthInterceptor` reads from `CredentialStore` at request time.


---

## Error Handling

| Condition                  | Behaviour                                                         |
|----------------------------|-------------------------------------------------------------------|
| Network error / timeout    | `Snackbar` with "Retry" action shown immediately on the first failure. The request is then automatically retried once after 2 seconds; if the retry also fails, the `Snackbar` persists until the user taps Retry. The 2-second delay is launched in `viewModelScope`; if the user navigates away before the retry fires, the ViewModel is cleared and the pending retry is cancelled automatically. This behaviour applies to all `IOException` and timeout errors across all screens, **except** `POST /api/v1/drafts/{id}/send` (see note below). |
| 400 Bad Request            | Show server `error` message inline near the triggering UI element |
| 401 Unauthorized           | Navigate to Setup screen                                          |
| 404 Not Found (detail screen) | Show "Not found" inline in the detail pane                     |
| 404 Not Found (list screen)   | Navigate back to the Folder List with a Snackbar error          |
| 404 Not Found (Compose — draft fetch on open) | Show a centred error with a Retry button; do not start the auto-save loop |
| 404 Not Found (Search, Settings sub-tabs) | Show a centred inline error message with a Retry button |
| 409 Conflict               | Show server `error` message inline                                |
| 500 Internal Server Error  | `Snackbar` with server `error` message (exception: 400/500 on `POST /api/v1/drafts/{id}/send` are shown inline above the Send button — see Compose Screen → Send) |

**Auto-retry exclusion for Send:** `POST /api/v1/drafts/{id}/send` is not idempotent —
a timeout may occur after the server already dispatched the email. The auto-retry policy
does **not** apply to the send call. On a network error or timeout during send, show the
`Snackbar` with a Retry action but do not automatically retry after 2 seconds. The
synchronous `POST /api/v1/drafts` call that may precede the send (when no `draftId` exists
yet) is a separate, idempotent-safe operation and does follow the normal auto-retry policy.


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
| 2–6 days ago       | Weekday + time             | "Mon 14:32"       | ⁵
| 7 days – same year | Short date + time          | "Apr 3, 14:32"    | ⁴
| Previous years     | Short date with year only  | "Apr 3, 2023"     | ⁴

¹ "min" is an intentional abbreviation for the Android client; `REQUIREMENTS.md` uses
"minutes ago" (full word) for the web UI.

² "Yesterday" means the calendar day before today in the device's local timezone (any
time on that calendar date, regardless of elapsed hours). Because rules are evaluated
top-to-bottom, a message sent yesterday but within the last hour still matches the
"1 min – < 1 hour" rule first.

³ "Same day" means the same calendar day as today in the device's local timezone
(midnight boundary), consistent with the Yesterday definition in footnote ².

⁴ "7 days – same year" means: the message date is ≥ 7 full calendar days before today
AND in the same calendar year as today. "Previous years" means a different calendar year
from today (the two rules are mutually exclusive). The time component is shown for the
same-year rule but intentionally omitted for previous years — older messages are
displayed date-only for brevity.

⁵ "2–6 days ago" covers calendar days 2 through 6 before today, where day boundaries
are at midnight in the device's local timezone (consistent with the Yesterday and
Same-day rules).

Message detail always shows the full form with timezone abbreviation:
`EEE, d MMM yyyy, HH:mm z` (e.g. "Mon, 3 Apr 2023, 14:32 CEST"). Use
`java.time.format.DateTimeFormatter` with the device's locale for day/month names and
a fixed 24-hour time pattern.
Long-press a date/time chip to copy the full ISO 8601 string. On Android 13+
(API 33+) the system displays a clipboard confirmation automatically. On Android 12L and
below (API ≤ 32), show a brief `Snackbar` confirming that the date was copied to the
clipboard.


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
- Snooze — no snooze creation or re-snooze actions; the Snoozed folder is accessible for
  reading, replying, forwarding, and cancelling the snooze (returning the message to its
  original folder). Moving or deleting a snoozed message is not permitted by the server
  (see action bar table)
- Identity management — the Identities settings tab is read-only; create/edit/delete/
  set-default is deferred to v1+
- Filter editing — the Filters settings tab is read-only; add/edit/delete/reorder of
  filters is deferred to v1+
- Scheduled send — no "Send later" in Compose; Scheduled folder is accessible for reading
  and cancelling the scheduled send (which moves the message back to Drafts); direct
  deletion via `DELETE /api/v1/messages/{id}` is rejected by the API for Scheduled messages
- Folder reordering (entirely out of scope for v1)
- Drag-to-reorder for filters and identities (tap-to-reorder with up/down arrows as
  a simpler alternative is acceptable for v1)
- PGP / S-MIME
- Multi-account support
- Tablet adaptive layout (single-column phone layout is sufficient for v1)
- Download raw `.eml` file (`GET /api/v1/messages/{id}/raw`)
- Flagged / starred messages — the `flagged` field is present in the API but no flag/star
  UI is exposed; a future version may add it
