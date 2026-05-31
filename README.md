# mymail Android

Native Android client for [mymail](https://github.com/mikaelstaldal/mymail), a self-hosted email server. Connects over the mymail REST API — no offline support in v1.

## Requirements

- Android 8.0+ (API 26)
- A running mymail server

## Building

```bash
# Debug APK
gradle :app:assembleDebug

# Release APK
gradle :app:assembleRelease

# Run unit tests
gradle :app:testDebugUnitTest
```

The build automatically generates the Retrofit API client from the OpenAPI spec at `../mymail/openapi.yaml`. To point at a different spec:

```bash
gradle :app:assembleDebug -PopenApiSpecPath=/path/to/openapi.yaml
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

## Features

- Browse folders and messages with infinite scroll
- Read messages (plain text) with thread view
- Compose, reply, reply-all, forward
- Draft auto-save every 30 seconds
- File attachments (upload and download)
- Search across all mail
- Multi-select: bulk mark read/unread, move, delete
- Folder management (create, rename, delete)
- Contact book with autocomplete
- Spam filter settings
- Sending identities
- New-mail notifications (foreground + background polling)
- Dark mode / light mode / system default
- Message list density preference (compact / normal / relaxed)

## First Launch

On first launch the app shows a setup screen. Enter your mymail server URL (e.g. `https://mail.example.com`), username, and password. 
The app validates the credentials by calling the server before saving them.

## Security Notes

- Credentials are stored in `EncryptedSharedPreferences` (Android Keystore-backed).
- HTTPS is enforced in release builds; plain HTTP is allowed in debug builds only.
- Certificate validation uses the system trust store and cannot be disabled.
- The `Authorization` header is never written to logs.

## License

Copyright 2026 Mikael Ståldal.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
