# AI coding agent instructions

This file provides guidance to AI coding agents when working with code in this repository.

## Build Commands

```bash
# Build debug APK (also runs openApiGenerate)
gradle :app:assembleDebug

# Run unit tests
gradle :app:testDebugUnitTest

# Regenerate API client from OpenAPI spec
gradle :app:openApiGenerate

# Override spec path (defaults to ../mymail/openapi.yaml)
gradle :app:openApiGenerate -PopenApiSpecPath=/path/to/openapi.yaml
```

## Technology Stack

| Concern              | Technology                               |
|----------------------|------------------------------------------|
| Language             | Kotlin                                   |
| UI                   | Jetpack Compose + Material 3             |
| Navigation           | Jetpack Navigation Compose               |
| API client           | Retrofit 2 (generated from OpenAPI spec) |
| JSON                 | kotlinx.serialization                    |
| HTTP                 | OkHttp 4                                 |
| Async                | Kotlin Coroutines + Flow                 |
| State / lifecycle    | ViewModel + StateFlow                    |
| Dependency injection | Hilt                                     |
| Credentials storage  | EncryptedSharedPreferences               |
| Background polling   | WorkManager                              |
| RFC 5322 parsing     | Apache MIME4J                            |

## Architecture Overview

Single-activity app (`MainActivity`) using Jetpack Compose + Navigation Compose. The layer order is:

```
API (generated) → Repository → ViewModel → Screen (Compose)
```

**Dependency injection:** Hilt throughout. `NetworkModule` provides `RetrofitHolder` and the shared `OkHttpClient`; `PrefsModule` provides `EncryptedSharedPreferences`.

**API client generation:** The `openApiGenerate` Gradle task generates the model classes into `app/build/generated/openapi/`. The Retrofit interfaces (`FoldersApi`, `MessagesApi`, etc.) are **not** generated — they are hand-written in `app/src/main/java/nu/staldal/mymail/api/`, so a new endpoint or query parameter has to be added there by hand. These are wired into compilation via `kotlin.sourceSets["main"].kotlin.srcDir(...)`. Never edit generated files — they live under `build/`.

**`RetrofitHolder`:** A `@Singleton` wrapping `AtomicReference<Retrofit>`. Repositories call `holder.get().create(FooApi::class.java)` on **every request** (not at field-init time) because `holder.get()` may return a new `Retrofit` after `rebuild()` is called when the server URL changes.

**Authentication:** `BasicAuthInterceptor` adds `Authorization: Basic …` to every OkHttp request, reading `CredentialStore.activeCredential` at request time (the stored credential, or the one fetched from MyPass — see **MyPass integration**). On HTTP 401, the interceptor emits on `AuthEventBus` (a `@Singleton` `MutableSharedFlow`); `MainActivity` collects this and navigates to `setup`, clearing the back-stack. The logging interceptor must **never** log request headers (they contain credentials).

**Credentials storage:** Server URL, username, and password go exclusively in `EncryptedSharedPreferences`, alongside the pw options `use_pw` and `pw_entry_name`. Non-sensitive prefs (`inbox_unread_count`, dark mode, density, notification preference) go in plain `SharedPreferences` file `"mymail_prefs"`. Never mix these.

**Error handling pattern:** Repositories wrap network calls in `Result<T>`, catching `HttpException` and `IOException`. ViewModels expose a `UiState` sealed class with `Loading`, `Success`, and `Error` variants. Network errors auto-retry once after 2 seconds (except `POST /api/v1/drafts/{id}/send` — non-idempotent).

**Navigation results:** When Message Detail performs a destructive action and pops back, it sets `needsRefresh = true` and `removedMessageId = id` on `previousBackStackEntry?.savedStateHandle` before calling `popBackStack()`. Message List observes `needsRefresh` (triggers full reload); Search observes `removedMessageId` (removes item locally without re-fetching).

**Background polling:** `MailPollingWorker` is a `@HiltWorker` registered via `HiltWorkerFactory` (custom `WorkManager` configuration in `MyMailApplication`). The default `WorkManager` initializer is removed from the manifest. The worker skips polling when `MyMailApplication.isAppInForeground` is `true`, and when `CredentialStore.hasCredentials()` is `false` (pw mode without a credential in this process); foreground polling runs on `MainActivity.lifecycleScope` every 30 seconds instead.

**Compose auto-save:** Uses a `Mutex` (`saveMutex`) to serialise the 30-second auto-save loop and the `onCleared()` final-save path. First save calls `POST /api/v1/drafts`; subsequent saves call `PUT /api/v1/drafts/{id}`. Attachment uploads use `PUT /api/v1/drafts-with-attachments/{id}` (replaces all attachments wholesale, must re-include existing ones).

**RFC 5322 parsing:** Always use `org.apache.james:apache-mime4j-core` (`AddressList.parse()`) for address parsing — handles edge cases like quoted display names containing commas.

**Date formatting** (`DateTimeUtils.kt`): two ladders that must not be swapped. `formatMessageListDate` measures elapsed time, so a *future* value lands in its under-an-hour branch and every scheduled send reads as "just now"; `formatScheduleDate` is the one for `send_at` and `snoozed_until` — it reads in both directions and keeps the time of day at every distance. `ui/component/ScheduleTimeLabel` renders them in the Scheduled and Snoozed listings and in search results, checking both the folder and the value.

**Built-in folder IDs** (`FolderIds.kt`): `INBOX_ID=1`, `SENT_ID=2`, `DRAFTS_ID=3`, `TRASH_ID=4`, `SCHEDULED_ID=5`, `SNOOZED_ID=6`, `JUNK_ID=7`. User folders have `id ≥ 100`.

## MyPass integration

The server credentials can optionally come from the [MyPass Android app](https://github.com/mikaelstaldal/mypass-android)
instead of this app's encrypted preferences. The contract is documented in `../mypass-android/INTEGRATION.md`.

- `auth/MyPassClient.kt` duplicates MyPass's action and extra names; keep them in step with that contract.
  The intent is explicit — the `nu.staldal.mypass` package is pinned so no other app can claim the action.
- MyPass guards the activity with the signature permission `nu.staldal.mypass.permission.FETCH_PASSWORD`.
  Both apps must be signed with the same key (see the `debug` signing config in `app/build.gradle.kts`),
  and the manifest `<queries>` entry makes MyPass visible to `resolveActivity` on Android 11 and later.
  Launching can still throw `ActivityNotFoundException` or `SecurityException`; both are handled.
- Only `use_pw` and the exact `pw_entry_name` are persisted. The returned username and password live
  in `PwCredentialSession` (a `@Singleton` `StateFlow`) for the current process only, and must never
  be logged, persisted, or placed in saved instance state. `Credential.toString()` redacts both halves,
  and `AuthConfig` has a hand-written `toString()` because a data class would print the password.
- The session holds a `FetchedCredential` — the credential **plus the entry it was fetched for** —
  and readers name the entry they want (`credentialFor`, `AuthConfig.activeCredential`). A credential
  fetched for one entry must never authenticate a server configured for another, which is exactly
  what happens if the entry name is edited in the setup screen after a fetch.
- Startup: `MainActivity` launches the pw activity when `CredentialStore.needsPwFetch()` holds, and
  moves on to the folder list once that fetch produces a credential. The setup screen offers the same
  fetch manually, and `SetupUiState.canConnect` blocks **Connect** until the credential is in memory.
- The "already asked pw" guard is `PwCredentialSession.fetchAttempted`, which is process-scoped on
  purpose. It must **not** be derived from `savedInstanceState == null`: Android restores that bundle
  after a process kill too, which is precisely when the memory-only credential is gone and pw has to
  be asked again. Saved instance state carries only `awaiting_pw_fetch`, which distinguishes the
  startup fetch (navigates onward) from one started on the setup screen across a rotation.
- **Limitation:** because the secret is process-memory-only, background polling cannot authenticate
  after a process restart. `MailPollingWorker` then skips the poll (returning `success` so the
  periodic work stays scheduled) until the user opens MyMail and completes the pw activity.

## Key Security Constraints

- TLS: never disable hostname verification or use a trust-all `TrustManager`. Default OkHttp TLS settings must be preserved in all build variants.
- Release builds: `cleartextTrafficPermitted = false` in `network_security_config.xml`.
- Attachment FileProvider: restrict shared paths to `getCacheDir()/attachments/` only (not all of `getCacheDir()`). Block dangerous MIME types before calling `startActivity` (`text/html`, `application/xhtml+xml`, shell/executable types, APKs).
- Reject attachment downloads where `Content-Length` exceeds 100 MB or is absent/unparseable.
