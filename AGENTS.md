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

**Authentication:** `BasicAuthInterceptor` adds `Authorization: Basic …` to every OkHttp request, reading from `CredentialStore` at request time. On HTTP 401, the interceptor emits on `AuthEventBus` (a `@Singleton` `MutableSharedFlow`); `MainActivity` collects this and navigates to `setup`, clearing the back-stack. The logging interceptor must **never** log request headers (they contain credentials).

**Credentials storage:** Server URL, username, and password go exclusively in `EncryptedSharedPreferences`. Non-sensitive prefs (`inbox_unread_count`, dark mode, density, notification preference) go in plain `SharedPreferences` file `"mymail_prefs"`. Never mix these.

**Error handling pattern:** Repositories wrap network calls in `Result<T>`, catching `HttpException` and `IOException`. ViewModels expose a `UiState` sealed class with `Loading`, `Success`, and `Error` variants. Network errors auto-retry once after 2 seconds (except `POST /api/v1/drafts/{id}/send` — non-idempotent).

**Navigation results:** When Message Detail performs a destructive action and pops back, it sets `needsRefresh = true` and `removedMessageId = id` on `previousBackStackEntry?.savedStateHandle` before calling `popBackStack()`. Message List observes `needsRefresh` (triggers full reload); Search observes `removedMessageId` (removes item locally without re-fetching).

**Background polling:** `MailPollingWorker` is a `@HiltWorker` registered via `HiltWorkerFactory` (custom `WorkManager` configuration in `MyMailApplication`). The default `WorkManager` initializer is removed from the manifest. The worker skips polling when `MyMailApplication.isAppInForeground` is `true`; foreground polling runs on `MainActivity.lifecycleScope` every 30 seconds instead.

**Compose auto-save:** Uses a `Mutex` (`saveMutex`) to serialise the 30-second auto-save loop and the `onCleared()` final-save path. First save calls `POST /api/v1/drafts`; subsequent saves call `PUT /api/v1/drafts/{id}`. Attachment uploads use `PUT /api/v1/drafts-with-attachments/{id}` (replaces all attachments wholesale, must re-include existing ones).

**RFC 5322 parsing:** Always use `org.apache.james:apache-mime4j-core` (`AddressList.parse()`) for address parsing — handles edge cases like quoted display names containing commas.

**Built-in folder IDs** (`FolderIds.kt`): `INBOX_ID=1`, `SENT_ID=2`, `DRAFTS_ID=3`, `TRASH_ID=4`, `SCHEDULED_ID=5`, `SNOOZED_ID=6`, `JUNK_ID=7`. User folders have `id ≥ 100`.

## Key Security Constraints

- TLS: never disable hostname verification or use a trust-all `TrustManager`. Default OkHttp TLS settings must be preserved in all build variants.
- Release builds: `cleartextTrafficPermitted = false` in `network_security_config.xml`.
- Attachment FileProvider: restrict shared paths to `getCacheDir()/attachments/` only (not all of `getCacheDir()`). Block dangerous MIME types before calling `startActivity` (`text/html`, `application/xhtml+xml`, shell/executable types, APKs).
- Reject attachment downloads where `Content-Length` exceeds 100 MB or is absent/unparseable.
