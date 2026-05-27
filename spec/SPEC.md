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
  inline error message:
  - Network error / no connectivity: "Could not connect — check the server URL and your
    network connection"
  - HTTP 401: "Invalid username or password"
  - HTTP 404 or 503: "Server not reachable — check the server URL"
  - Any other non-200 response: "Connection failed (HTTP {status_code})"
- If credentials already exist, this screen is skipped at startup.
- The Server URL and credential fields are pre-filled with the currently saved values whenever credentials are already stored — both when navigated to via the **Change server** button in Settings and when redirected after a 401 response.


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

- Fetches `GET /api/v1/folders` on enter and on pull-to-refresh.
- **Error state:** If the fetch fails, show a centred error message with a Retry button.
  While the error state is shown, the list is empty. The generic Snackbar also fires.
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
    response models): shown only in Scheduled (yellow) and Drafts (red); the flag
    does not appear in other folders
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
  button (the generic Snackbar still fires; the inline Retry is additional).
- Pull-to-refresh reloads from offset 0. Refresh replaces the entire in-memory list
  with only the first page result; previously loaded pages beyond page 1 are discarded.
- **Mark all as read** action in the overflow menu calls
  `POST /api/v1/folders/{folder_id}/mark-all-read`. On success, mark all currently
  loaded message rows as read locally (update their visual style without reloading from
  the server) and update the folder's unread-count badge to 0.
- **Empty folder** action (Trash and Junk only) calls
  `DELETE /api/v1/folders/{folder_id}/messages` after a confirmation dialog.
- Long-press a message to enter multi-select mode. Toolbar actions in multi-select:
  - **Mark read / unread** — `PATCH /api/v1/messages` with `{"ids": […], "read": true/false}`.
    This bulk endpoint is distinct from the single-message `PATCH /api/v1/messages/{id}`
    used in Message Detail; both must be defined in the OpenAPI spec.
  - **Move to folder** (folder picker dialog) — `POST /api/v1/messages/move` with body
    `{"ids": […], "folder_id": targetFolderId}`. The **Move** action is disabled entirely
    when the current folder is Drafts or Scheduled (the server rejects such sources with
    400). The folder picker lists all folders except Scheduled, Snoozed, Drafts, and the
    current folder (consistent with the Message Detail picker).
  - **Delete** — `DELETE /api/v1/messages` with `{"ids": […]}`

Pressing the system Back button while in multi-select mode exits multi-select (same as
the standard contextual-action-bar Back behaviour on Android).


---

## Message Detail Screen

- Fetches `GET /api/v1/messages/{id}`. If the initial fetch fails, show a centred
  error message with a Retry button.
- After a successful fetch, if the message has `read: false`, issues
  `PATCH /api/v1/messages/{id}` with `{"read": true}` to mark as read. On 200, update
  the `read` flag in the locally held `MessageDetail` — the endpoint returns a
  `MessageSummary` (no body text), so no re-fetch is needed and the already-loaded
  detail continues to be displayed.
- **Header section** (collapsed by default, expandable by tapping anywhere on the
  collapsed header row; a trailing chevron icon indicates the expand/collapse state):
  From, To, Cc, Bcc, Reply-To, Date, Subject. For messages in the Snoozed folder
  (`folder_id == SNOOZED_ID`), additionally show a **Snoozed until** row displaying the
  `snoozed_until` timestamp in the full date format (`EEE, d MMM yyyy, HH:mm z`).
- **`send_failed` banner:** If the message has `send_failed: true`, show a banner
  immediately below the header — yellow when `folder_id == SCHEDULED_ID`, red when
  `folder_id == DRAFTS_ID` (determined from the fetched message object, same colour
  coding as the Message List badge).
- **Body section:** displays `body_text` in a `SelectionContainer` with a monospace
  font (plain text only in v1).
- **Attachments section:** lists all attachments by name and size. Tapping an
  attachment downloads it via `GET /api/v1/attachments/{id}`, writes it to the app's
  cache directory (`getCacheDir()`), and opens it with `ACTION_VIEW` using a
  `FileProvider` URI (authority: `nu.staldal.mymail.fileprovider`). No
  `WRITE_EXTERNAL_STORAGE` permission is needed on API 29+.
- **Thread section:** fetches `GET /api/v1/messages/{id}/thread`. The thread fetch is
  issued in parallel with the main message fetch (no dependency between the two). If the
  thread fetch fails while the main message fetch succeeded, show an inline error message
  and a Retry button within the thread section; the main message body continues to be
  displayed normally. Shown as a collapsible list of message summaries below the body,
  expanded by default.
  Tapping a summary navigates to that message's detail screen; pressing Back from there
  returns directly to the Message List (not to the originating detail screen). Implement
  using:
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
| Scheduled        | Delete (permanent)                                                         |
| Snoozed          | Reply, Reply All, Forward, Move, Mark as junk                              |
| Junk             | Not junk, Move, Delete (permanent)                                         |
| Trash            | Move, Delete (permanent)                                                   |

**Move** opens a folder picker bottom sheet (lists all folders except Scheduled,
Snoozed, Drafts, and the current folder).

**Mark as junk** calls `POST /api/v1/messages/{id}/mark-junk`. On success navigate back
to the message list; the app stays in the current folder (does not navigate to Junk).

**Not junk** calls `POST /api/v1/messages/{id}/mark-not-junk`. On success call
`navController.popBackStack()` to return to the previous screen. The message is moved
to Inbox server-side; if the previous screen was the Junk message list, it will refresh
and no longer show the message.

The **Delete** action in Scheduled, Junk, and Trash folders is permanent. Show a
confirmation dialog before proceeding (same pattern as Discard in Drafts).

The **Move** action (single message) calls `POST /api/v1/messages/move` with body
`{"ids": [id], "folder_id": targetFolderId}` — the same bulk endpoint used in
multi-select, with a single-element array.

After a successful **Move** or **Delete** from Message Detail, call
`navController.popBackStack()` to return to the Message List, and trigger a full list
refresh (reload from offset 0) so the moved or deleted message no longer appears.


---

## Compose Screen

Supports new mail, reply, reply-all, forward, and draft editing.

**Fields:**

| Field      | Notes                                                                          |
|------------|--------------------------------------------------------------------------------|
| From       | `DropdownMenu` populated from `GET /api/v1/identities`. For new compose, pre-select the identity with `is_default: true`. For Reply/Reply-All, pre-select the identity whose address matches a To or Cc address of the source message; fall back to the default identity if no match is found. |
| To         | Chip text field with autocomplete from `GET /api/v1/contacts?q=…&limit=10`. Autocomplete fires after the user types at least 1 character, debounced at 300 ms. |
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
| To      | Source Reply-To if present, else source From       | Source Reply-To if present, else source From (same as Reply), excluding own identities | (empty)          |
| Cc      | (empty)                                            | All source To/Cc recipients, excluding own identities                     | (empty)          |
| Bcc     | (empty)                                            | (empty)                                                                   | (empty)          |
| Subject | `Re: ` + source subject (suppress duplicate `Re:` prefixes — strip all leading `Re:` prefixes case-insensitively using `^(?i)(re:\s*)+` before prepending `Re: `) | Same as Reply | `Fwd: ` + source subject (suppress duplicate `Fwd:` prefixes — strip all leading `Fwd:` prefixes case-insensitively using `^(?i)(fwd:\s*)+` before prepending `Fwd: `) |
| Body    | Attribution line + quoted source body (see below)  | Same as Reply                                                              | Original headers + quoted source body (see below) |

"Excluding own identities" means: omit any address whose email address matches one of
the user's identity addresses (comparison is case-insensitive on both local part and
domain, per RFC 5321 convention). This applies even if the only candidate for the `To`
field is a Reply-To address that matches an own identity — in that case `To` is left
empty and the user must fill it in manually.

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
Date: <source Date>
Subject: <source Subject>
To: <source To>

<source body>
```

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
  appending). Omit the field if both the source `references` list is empty and
  `message_id` is null.

**Auto-save:**
When the screen opens via `compose?draftId={id}`, initialise the ViewModel's `draftId`
from the navigation argument, then fetch the existing draft via `GET /api/v1/messages/{id}`
to pre-populate all form fields (From, To, Cc, Bcc, Reply-To, Subject, Body, and any
existing server-side attachments shown as removable chips). There is no dedicated
`GET /api/v1/drafts/{id}` endpoint — drafts are regular messages accessible via the
messages endpoint. To pre-populate the From dropdown, match the draft's `from_addr`
against the fetched identities list (case-insensitive address comparison); if no identity
matches, pre-select the default identity. If this fetch fails, show a
centred error message with a Retry button and do not start the auto-save loop until the
fetch succeeds — this prevents the loop from overwriting the server draft with blank
fields. The auto-save loop then uses `PUT /api/v1/drafts/{id}` from the very first save
and never calls `POST /api/v1/drafts`.

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

The auto-save request body is a `DraftRequest` JSON object with fields: `identity_id`
(integer — the ID of the selected identity from the From dropdown), `to_addr` (string),
`cc_addr` (string), `bcc_addr` (string), `reply_to_addr` (string, may be empty),
`subject` (string), `body_text` (string). Address fields use RFC 5322 comma-separated
format for multiple addresses (e.g. `"Alice <a@b.com>, Bob <c@d.com>"`). For Reply and
Reply-All, also include `in_reply_to` and `references` as described above — these fields
are present in every save call for the lifetime of the reply draft. It never uploads,
creates, or deletes attachments; attachments are handled exclusively at send time or via
immediate-delete for existing draft attachments.

Locally queued attachments are never uploaded during auto-save — they are uploaded only
at send time.

**Send:**
Before initiating Send, cancel the running auto-save job. Disable the Send button while
in flight. If no `draftId` exists yet (the user tapped Send before the first 30-second
auto-save fired), perform a synchronous `POST /api/v1/drafts` first to obtain one, then
proceed. If locally queued attachments exist, upload them via
`PUT /api/v1/drafts-with-attachments/{id}` before calling `/send`. The request is
`multipart/form-data` with a JSON part named `message` containing a `DraftRequest`
(same fields as the auto-save body: `identity_id`, `to_addr`, `cc_addr`, `bcc_addr`,
`reply_to_addr`, `subject`, `body_text`) and one or more file parts named `attachments`.
Always send via `POST /api/v1/drafts/{id}/send`.
On 201 navigate back (the draft is consumed by the send operation; the auto-save loop is
not restarted).
On 400/500 show the server error message inline above the Send button and restart the
auto-save loop so subsequent edits continue to be auto-saved.
If the attachment upload (`PUT /api/v1/drafts-with-attachments/{id}`) fails: show the
error message inline above the Send button; retain the draft ID and locally queued
attachments so the user can retry; restart the auto-save loop. Do not proceed to `/send`.

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
- Whenever the query text or any date filter changes, reset `offset` to 0 and discard
  previously loaded results before issuing a new request. Pull-to-refresh reloads from
  offset 0 with the current filters applied.
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
| Identities   | Read-only list of identities                       |
| Folders      | CRUD list of user folders                          |
| Filters      | Read-only list of filters (editing is out of scope for v1) |
| Spam         | Enable/disable toggle, score header, threshold     |
| Contacts     | Paginated contact list; add / edit / delete        |
| Preferences  | App-level preferences (see below)                  |

**Identities tab:** Fetches `GET /api/v1/identities` on enter. Show an inline error with
a Retry button if the fetch fails. Read-only list of identities; each row shows name and
address. Identity management (create, edit, delete, set default) is out of scope for v1.

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

**Filters tab:** Fetches `GET /api/v1/filters` on enter. Show an inline error with a
Retry button if the fetch fails. Each filter row shows the filter name on the first line
and a one-line summary on the second line in the form:
`If <field> contains <value> → <action>`
Read-only in v1 — no add, edit, delete, or reorder. Filter editing is deferred to v1+.

**Spam tab:** Toggle plus two text fields for score header name and threshold, and an
explicit **Save** button. On enter, call `GET /api/v1/spam-filter` to load the current
settings into the form fields. Show a loading indicator while fetching; show an inline
error with a Retry button if the fetch fails. Tapping **Save** calls
`PUT /api/v1/spam-filter` with the current form values.

**Contacts tab:** Paginated list via `GET /api/v1/contacts?q=…&limit=50&offset=0`.
Each contact has a **name** (display name) and an **email address**; the list row shows
the name on the first line and the email on the second line. Show an inline error with a
Retry button if the initial fetch fails; if a subsequent infinite-scroll page fetch
fails, show an inline error with a Retry button at the bottom of the list. Search field
filters via `q=`; keystrokes are debounced with a 300 ms delay before issuing a request.
When the query changes, reset `offset` to 0 and discard previously loaded results before
issuing a new request. Infinite scroll with `offset += 50`; stop paginating when the
returned count is less than 50. Tapping a contact opens an edit dialog; a **+** FAB
creates a new contact. Both add and edit dialogs have Name and Email fields. Add / edit /
delete. Mutation endpoints:

| Operation | Endpoint                          |
|-----------|-----------------------------------|
| Create    | `POST /api/v1/contacts`           |
| Update    | `PUT /api/v1/contacts/{id}`       |
| Delete    | `DELETE /api/v1/contacts/{id}`    |

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
enabled) and update the unread badge.

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

The background worker is enqueued immediately after a successful Connect on the Setup
screen, and again on app cold-start if credentials are already stored, using
`ExistingPeriodicWorkPolicy.KEEP` so duplicate enqueues are no-ops and it survives
app restarts.

If the worker receives HTTP 401: post a notification on the `mymail_new_mail` channel
with title = "Session expired" and body = "Tap to sign in again". Use `NavDeepLinkBuilder` with the `mymail://setup`
deep-link URI to navigate to the Setup screen. Then cancel the periodic work request
so polling stops until the user re-authenticates (the worker is re-enqueued after a
successful Setup).

Clear all posted new-mail notifications, reset the persisted unread-count baseline in
`SharedPreferences` (`"inbox_unread_count"` key in `"mymail_prefs"`), and reset the
in-memory foreground-poller baseline when the Inbox `MessageListScreen` composable
enters composition. Implement via `LaunchedEffect(Unit)` in the Inbox message list
composable — this fires on every composition of the Inbox screen (forward navigation,
Back navigation into it, and after rotation).


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
`Snackbar` with a Retry action but do not automatically retry after 2 seconds.


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
- Snooze — no snooze or edit-snooze actions; the Snoozed folder is accessible for
  reading, replying, forwarding, and moving messages, but deleting a snoozed message is
  not permitted (see action bar table)
- Identity management — the Identities settings tab is read-only; create/edit/delete/
  set-default is deferred to v1+
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
