# mymail Android Client — Functional Requirements

---

## 1. General

**REQ-GEN-01** The app connects to a self-hosted mymail server over its REST API.

**REQ-GEN-02** There is no offline support in v1.

**REQ-GEN-03** The app uses a single-activity architecture with a single navigation host.

**REQ-GEN-04** All credentials (server URL, username, password) are stored encrypted. Non-sensitive preferences are stored in plain app storage.

---

## 2. Built-in Folder IDs

**REQ-FOLDER-ID-01** The following folder IDs are fixed constants that must match the server:

| Constant       | ID | Folder    |
|----------------|----|-----------|
| INBOX_ID       | 1  | Inbox     |
| SENT_ID        | 2  | Sent      |
| DRAFTS_ID      | 3  | Drafts    |
| TRASH_ID       | 4  | Trash     |
| SCHEDULED_ID   | 5  | Scheduled |
| SNOOZED_ID     | 6  | Snoozed   |
| JUNK_ID        | 7  | Junk      |

**REQ-FOLDER-ID-02** User-created folders always have id ≥ 100.

---

## 3. Setup Screen

**REQ-SETUP-01** The Setup screen is shown on first launch when no credentials are stored.

**REQ-SETUP-02** The Setup screen is shown after receiving an HTTP 401 response from any request.

**REQ-SETUP-03** If credentials are already stored, the Setup screen is skipped at startup.

**REQ-SETUP-04** The Setup screen has three fields: Server URL, Username, and Password (obscured with a toggle-to-reveal option).

**REQ-SETUP-05** The Server URL must be validated as a well-formed URL. HTTPS is required in release builds; plain HTTP is permitted in debug builds only.

**REQ-SETUP-06** When credentials are already stored, the Server URL and Username fields are pre-filled with the currently saved values. The Password field is always left blank.

**REQ-SETUP-07** The app tracks whether the user has edited the Password field (using a `passwordTouched` flag set on the first keystroke).

**REQ-SETUP-08** If the user taps Connect and the Password field is empty and `passwordTouched` is false, the previously stored password is reused unchanged.

**REQ-SETUP-09** If `passwordTouched` is true but the Password field is empty (the user deliberately cleared it), show the inline validation error: "Password is required".

**REQ-SETUP-10** Tapping Connect calls `GET /api/v1/folders`. On success (HTTP 200), credentials are stored and the app navigates to the Folder List.

**REQ-SETUP-11** On failure during Connect, show an inline error message:
- Network error / no connectivity: "Could not connect — check the server URL and your network connection"
- HTTP 401: "Invalid username or password"
- HTTP 404 or 503: "Server not reachable — check the server URL"
- Any other non-200 response: "Connection failed (HTTP {status_code})"

**REQ-SETUP-12** The pre-fill behaviour applies both when navigated to via the Change server button in Settings and when redirected after a 401 response.

---

## 4. Navigation

**REQ-NAV-01** The app uses the following named routes:

| Route                        | Screen                    |
|------------------------------|---------------------------|
| `setup`                      | Setup / login             |
| `folders`                    | Folder list               |
| `messages/{folderId}`        | Message list for a folder |
| `message/{messageId}`        | Message detail            |
| `compose`                    | New compose               |
| `compose?replyTo={id}`       | Reply                     |
| `compose?replyAllTo={id}`    | Reply All                 |
| `compose?forwardOf={id}`     | Forward                   |
| `compose?draftId={id}`       | Edit draft                |
| `search`                     | Search                    |
| `settings`                   | Settings (tabbed)         |

**REQ-NAV-02** On phones (and v1 in general), folder list and message list are separate full-screen destinations. Tablet adaptive layout is deferred to v1+.

**REQ-NAV-03** The `mymail://` URI scheme is registered so the OS can resolve deep links. Deep links are declared on the `messages/{folderId}` and `setup` routes.

**REQ-NAV-04** When Message Detail performs a destructive action that removes the current message from the list (Move, Delete, Mark as junk, Not junk, Cancel scheduled send, Cancel snooze), it sets `needsRefresh = true` and `removedMessageId = <id>` on the previous back-stack entry before calling popBackStack.

**REQ-NAV-05** Message List observes `needsRefresh` and triggers a full reload from offset 0 when it becomes true.

**REQ-NAV-06** Search Screen observes `removedMessageId` and removes the affected message from its in-memory results list without re-fetching.

**REQ-NAV-07** Both `needsRefresh` and `removedMessageId` are always set before popBackStack, regardless of which screen is below in the back-stack. Message List ignores `removedMessageId`; Search ignores `needsRefresh`.

---

## 5. Folder List Screen

**REQ-FOLDERLIST-01** The Folder List fetches the folder list on enter, on pull-to-refresh, and whenever the screen becomes the current destination again (e.g. after back-navigation from a sub-screen).

**REQ-FOLDERLIST-02** Each folder row shows the folder name and its unread count badge.

**REQ-FOLDERLIST-03** Unread counts are shown in a coloured chip.

**REQ-FOLDERLIST-04** If the fetch fails, show a centred error message with a Retry button. While the error state is shown, the list is empty. A network-error Snackbar also fires.

**REQ-FOLDERLIST-05** Tapping a folder navigates to the Message List for that folder.

**REQ-FOLDERLIST-06** A Compose FAB navigates to the Compose screen.

**REQ-FOLDERLIST-07** A Search icon in the top app bar navigates to Search.

**REQ-FOLDERLIST-08** A Settings icon navigates to Settings.

**REQ-FOLDERLIST-09** The launcher icon badge count is derived automatically from active notifications posted to the new-mail notification channel.

---

## 6. Message List Screen

**REQ-MSGLIST-01** The screen title is the folder name.

**REQ-MSGLIST-02** The initial load fetches messages with limit=50 and offset=0.

**REQ-MSGLIST-03** Each message row shows:
- From address (or display name if available)
- Subject (bold when unread)
- Date (adaptive format per date/time display rules)
- Attachment indicator icon
- `send_failed` badge: shown only in Scheduled (yellow) and Drafts (red); not shown in other folders

**REQ-MSGLIST-04** Row height adapts to the message list density preference: Compact = 56 dp, Normal = 72 dp (default), Relaxed = 88 dp. The `send_failed` badge and attachment indicator icon are always vertically centred within the row regardless of row height.

**REQ-MSGLIST-05** Infinite scroll: loads the next page when the user reaches the end of the list (offset += 50). Pagination stops when the returned item count is less than 50. A page returning 0 items is discarded.

**REQ-MSGLIST-06** When the total count is an exact multiple of 50, one extra empty request is issued before pagination stops; this is intentional.

**REQ-MSGLIST-07** Pull-to-refresh reloads from offset 0 and replaces the entire in-memory list with only the first page result; previously loaded pages beyond page 1 are discarded.

**REQ-MSGLIST-08** Empty state: when the initial load returns 0 items, show centred "No messages" text.

**REQ-MSGLIST-09** Error state on initial load: show a centred error message with a Retry button (in addition to the network-error Snackbar). Exception: a 404 response navigates back to the Folder List with a Snackbar error — no inline Retry is shown.

**REQ-MSGLIST-10** Mark all as read action is in the overflow menu. It is:
- Hidden while multi-select is active
- Shown for all folders **except** Drafts and Scheduled (where read state is not meaningful)
- On success, all currently loaded message rows are marked read locally and the folder's unread-count badge is updated to 0
- On failure, show a Snackbar with the error message; the message list is not modified

**REQ-MSGLIST-11** Empty folder action appears in the overflow menu for Trash and Junk only, hidden while multi-select is active. Requires a confirmation dialog ("Permanently delete all messages in this folder?") before proceeding. On success, reload the message list from offset 0. On failure, show a Snackbar with the error message.

**REQ-MSGLIST-12** Multi-select mode is not available in Drafts, Scheduled, or Snoozed folders. Long-press has no effect in those folders.

**REQ-MSGLIST-13** Long-pressing a message in any other folder enters multi-select mode. During multi-select:
- The Compose FAB is hidden
- The top app bar is replaced by a contextual action bar showing the selected-item count and three action buttons
- Pressing the system Back button exits multi-select and restores the normal top app bar and Compose FAB

**REQ-MSGLIST-14** Multi-select toolbar action — Mark read / unread:
- Toggle logic: if at least one selected message is unread, mark all as read; only when every selected message is already read, mark all as unread
- On success, update the read state of the affected rows locally
- On 400 or 404, show a Snackbar with the server error message; no local changes are made

**REQ-MSGLIST-15** Multi-select toolbar action — Move to folder:
- Opens a folder picker dialog listing all folders except Scheduled, Snoozed, Drafts, and the current folder
- On success, remove the affected rows from the list and exit multi-select
- On 400 or 404, show a Snackbar with the server error message; no local changes are made

**REQ-MSGLIST-16** Multi-select toolbar action — Delete:
- When the current folder is Trash or Junk, deletion is permanent — show a confirmation dialog before proceeding
- For all other folders, messages are moved to Trash and no confirmation is required
- On success, remove the affected rows from the list and exit multi-select
- On 400 or 404, show a Snackbar with the server error message; no local changes are made

---

## 7. Message Detail Screen

**REQ-MSGDETAIL-01** The screen fetches the message on load. If the initial fetch fails, show a centred error message with a Retry button.

**REQ-MSGDETAIL-02** After a successful fetch, if the message has `read: false`, send a mark-as-read request. Errors from this request are silently ignored.

**REQ-MSGDETAIL-03** The header section (From, To, Cc, Bcc, Reply-To, Date, Subject) is collapsed by default. Tapping anywhere on the collapsed header row expands/collapses it. A trailing chevron icon indicates the expand/collapse state.

**REQ-MSGDETAIL-04** For messages in the Snoozed folder, the header additionally shows a "Snoozed until" row with the `snoozed_until` timestamp in full date format. If `snoozed_until` is null, the row is omitted entirely.

**REQ-MSGDETAIL-05** For messages in the Scheduled folder, the header additionally shows a "Scheduled for" row with the `send_at` timestamp in full date format. If `send_at` is null, the row is omitted entirely.

**REQ-MSGDETAIL-06** A `send_failed` banner is shown immediately below the header only when `send_failed: true` AND the folder is Scheduled (yellow) or Drafts (red). Hidden in all other folders. The `send_error` field is not displayed in the UI.

**REQ-MSGDETAIL-07** The body section displays the plain text body in a selectable area with a monospace font (plain text only in v1).

**REQ-MSGDETAIL-08** The attachments section lists all attachments by name and size. Tapping an attachment downloads and opens it via the system viewer.

**REQ-MSGDETAIL-09** Before opening a downloaded attachment, check its `content_type` against a blocklist of dangerous MIME types (strip MIME parameters before comparing). Blocked types: `text/html`, `application/xhtml+xml`, `application/x-sh`, `application/x-shellscript`, `application/x-executable`, `application/vnd.android.package-archive`. If blocked, show an inline error ("This file type cannot be opened for security reasons") and do not open the file.

**REQ-MSGDETAIL-10** Attachment downloads where `Content-Length` exceeds 100 MB, or where `Content-Length` is absent or unparseable as a non-negative integer, are rejected with an inline error ("Attachment too large to download"). No bytes are written to disk.

**REQ-MSGDETAIL-11** Cached attachment files are deleted when the Message Detail screen leaves the composition.

**REQ-MSGDETAIL-12** The thread section fetches the thread in parallel with the main message fetch. If the thread fetch fails while the main message fetch succeeded, show an inline error and Retry within the thread section; the main message body remains displayed. The thread is shown as a collapsible list of message summaries below the body, expanded by default.

**REQ-MSGDETAIL-13** Tapping a thread summary navigates to that message's detail, replacing the current detail entry in the back-stack so that pressing Back returns to whichever screen was below the originating detail entry.

**REQ-MSGDETAIL-14** When `truncated` is true in the thread response, show a "Thread too long" notice.

**REQ-MSGDETAIL-15** The action bar actions depend on the message's `folder_id`:

| Folder                                        | Actions                                                     |
|-----------------------------------------------|-------------------------------------------------------------|
| Inbox or user folders (id ≥ 100)              | Reply, Reply All, Forward, Move, Mark as junk, Delete       |
| Sent                                          | Forward, Move, Delete                                       |
| Drafts                                        | Edit (→ Compose), Discard (with confirmation)               |
| Scheduled                                     | Cancel scheduled send (→ Drafts)                            |
| Snoozed                                       | Reply, Reply All, Forward, Cancel snooze                    |
| Junk                                          | Not junk, Move, Delete (permanent)                          |
| Trash                                         | Move, Delete (permanent)                                    |

**REQ-MSGDETAIL-16** Move opens a folder picker bottom sheet listing all folders except Scheduled, Snoozed, Drafts, and the current folder (where "current folder" is the message's `folder_id` field).

**REQ-MSGDETAIL-17** Mark as junk: on success, pop back to the Message List and trigger a full list refresh. The app stays in the current folder. On 400, show a Snackbar with the server error message.

**REQ-MSGDETAIL-18** Not junk: on success, pop back and trigger a full list refresh. On 400, show a Snackbar with the server error message.

**REQ-MSGDETAIL-19** Discard: requires a confirmation dialog. On 204 success or 404 (already discarded), pop back and trigger a full list refresh. On 404, also show a Snackbar ("Draft already discarded").

**REQ-MSGDETAIL-20** Delete for Inbox, user folders, and Sent: moves the message to Trash (non-permanent). No confirmation dialog required.

**REQ-MSGDETAIL-21** Delete for Junk and Trash: permanent deletion. Requires a confirmation dialog before proceeding.

**REQ-MSGDETAIL-22** Cancel scheduled send: requires a confirmation dialog. On success, pop back and trigger a full list refresh. On 404, show a Snackbar ("Message was already processed") and pop back.

**REQ-MSGDETAIL-23** Cancel snooze: no confirmation dialog required. On success, pop back and trigger a full list refresh. On 400 or 404, show a Snackbar with the server error message and pop back.

**REQ-MSGDETAIL-24** All destructive actions (Move, Delete, Discard, Mark as junk, Not junk, Cancel scheduled send, Cancel snooze) set the navigation result keys (`needsRefresh = true`, `removedMessageId = <id>`) on the previous back-stack entry before calling popBackStack.

---

## 8. Compose Screen

**REQ-COMPOSE-01** The Compose screen supports: new mail, reply, reply-all, forward, and draft editing.

**REQ-COMPOSE-02** Fields:
- From: dropdown of identities. Pre-select the identity with `is_default: true` for new compose. For Reply/Reply-All, pre-select the identity whose address matches a To or Cc address of the source message; fall back to the default identity if no match.
- To, Cc, Bcc: chip text fields with contact autocomplete (fires after 1 character, debounced 300 ms). Autocomplete closes on empty input or no contacts found. When `total` exceeds 10, show a non-selectable "Type more to narrow…" hint at the bottom. Maximum 8192 characters (hard cap).
- Reply-To: single plain text field (optional, collapsed by default). Maximum 8192 characters (hard cap).
- Subject: single-line text field. Maximum 998 characters (hard cap). Strip all CR and LF characters from the subject before saving or sending.
- Body: multi-line plain text field (plain text only in v1).
- Attachments: list of attached files with an Add attachment button.

**REQ-COMPOSE-03** Contact autocomplete: tapping a suggestion adds a chip displaying the contact's name if non-empty, otherwise the bare address. The chip encodes the address in RFC 5322 format. The user may also type an address manually and commit it as a chip by pressing Enter, comma, or Tab; no client-side RFC 5322 validation is performed.

**REQ-COMPOSE-04** If the identities fetch returns an empty list, show a centred error: "No sending identities configured — go to Settings → Identities to add one." Disable all form fields and the Send button. The auto-save loop must not start in this state.

**REQ-COMPOSE-05** For Reply / Reply All / Forward: fetch the source message and identities in parallel. While fetches are in-flight, show a circular progress indicator over disabled/blank fields. If either fetch fails, show a centred error with a Retry button. The auto-save loop must not start until both fetches succeed.

**REQ-COMPOSE-06** Pre-population for Reply/Reply All/Forward:

| Field   | Reply                                                                    | Reply All                                                      | Forward         |
|---------|--------------------------------------------------------------------------|----------------------------------------------------------------|-----------------|
| To      | Source Reply-To if present, else source From (excluding own identities)  | Source Reply-To if present, else source From (same as Reply), excluding own identities | (empty)         |
| Cc      | (empty)                                                                  | All source To/Cc recipients, excluding own identities          | (empty)         |
| Bcc     | (empty)                                                                  | (empty)                                                        | (empty)         |
| Subject | `Re: ` + source subject (strip leading `Re:` prefixes case-insensitively before prepending) | Same as Reply | `Fwd: ` + source subject (strip leading `Fwd:` prefixes case-insensitively before prepending) |
| Body    | Attribution line + quoted source body                                    | Same as Reply                                                  | Original headers + quoted source body |

**REQ-COMPOSE-07** "Excluding own identities" means: omit any address whose email address (case-insensitive on both local part and domain) matches one of the user's identity addresses. Plus-addressed variants (`local+tag@domain`) also match identity `local@domain`. If every candidate matches an own identity, the To field is left empty.

**REQ-COMPOSE-08** `reply_to_addr` is a full RFC 5322 comma-separated address list; all addresses (after excluding own identities) become To chips.

**REQ-COMPOSE-09** Quoted body format for Reply / Reply All:
```
On <date>, <from address> wrote:
> <source body, each line prefixed with "> ">
```
`<date>` is formatted as RFC 1123.

**REQ-COMPOSE-10** Quoted body format for Forward:
```
---------- Forwarded message ----------
From: <source From>
Date: <source Date, RFC 1123>
Subject: <source Subject>
To: <source To>

<source body>
```

**REQ-COMPOSE-11** Signature pre-population: the selected identity's `signature` field is appended in all compose modes. When non-empty, append `\n\n-- \n<signature>`. For new compose the body starts as just the signature block. For reply/reply-all/forward, two blank lines are placed at the top and the cursor is positioned at offset 0.

**REQ-COMPOSE-12** When the user changes the From identity, the signature block at the bottom of the body is replaced with the new identity's signature (or removed if none). Only the trailing signature block is replaced; typed text above is preserved. Replacement searches for `\n\n-- \n<currentSignature>` at the end; if not found, searches for the last `\n\n-- \n` and treats everything from that point to the end as the block to replace.

**REQ-COMPOSE-13** When the initial identity had no signature and the user switches to one with a signature, and no `\n\n-- \n` is found anywhere in the body, append `\n\n-- \n<newSignature>` at the end.

**REQ-COMPOSE-14** Auto-save: start a loop that saves on a 30-second delay. The first save calls POST /api/v1/drafts and stores the returned id. Subsequent saves call PUT /api/v1/drafts/{id}. Track a dirty flag; mark dirty on any field edit, clear after each successful save.

**REQ-COMPOSE-15** If the first POST /api/v1/drafts fails with a network or non-400 server error, show a non-blocking informational Snackbar ("Draft could not be saved — will retry"). Keep the dirty flag set and retry at the next tick.

**REQ-COMPOSE-16** If the first POST /api/v1/drafts fails with HTTP 400, stop the auto-save loop permanently and show a persistent non-dismissible inline error above the compose fields with the server error message.

**REQ-COMPOSE-17** A mutex serialises save operations. Both the auto-save loop and the final-save path on navigation away must acquire this mutex before checking the dirty flag and performing a save.

**REQ-COMPOSE-18** When the user navigates away, perform a final save if the dirty flag is set.

**REQ-COMPOSE-19** For draft editing (`compose?draftId={id}`): fetch the existing draft to pre-populate all fields (From, To, Cc, Bcc, Reply-To, Subject, Body, existing server-side attachments). If this fetch fails, show a centred error with a Retry button and do not start the auto-save loop. Pre-populating fields from the fetched draft does not set the dirty flag.

**REQ-COMPOSE-20** Threading fields for Reply and Reply-All (in_reply_to, references) are included in every save call (both POST and every PUT) for the lifetime of the reply draft.

**REQ-COMPOSE-21** For draft edit mode, `in_reply_to` and `references` are read from the fetched MessageDetail and included in every save call if non-empty.

**REQ-COMPOSE-22** The Send button is disabled when all three recipient fields (To, Cc, Bcc) are empty. It becomes enabled as soon as any one is non-empty. The Send button is disabled while the send request is in-flight.

**REQ-COMPOSE-23** Before sending, cancel the running auto-save. If no draftId exists yet, create a draft first, then send via POST /api/v1/drafts/{id}/send.

**REQ-COMPOSE-24** On send success (HTTP 201 or 202), navigate back.

**REQ-COMPOSE-25** On send failure with HTTP 400 or 500, show the server error message inline above the Send button and restart the auto-save loop. On HTTP 404 (draft was discarded from another client), show the server error message inline but do not restart the auto-save loop.

**REQ-COMPOSE-26** Attachments: newly added files are uploaded immediately on selection. The upload must include all currently retained attachments (both newly added and existing server-side ones). While re-downloading existing attachments, show a loading indicator. If any re-download fails, show an inline error with a Retry button in the attachments area.

**REQ-COMPOSE-27** For draft edits, existing server-side attachments are shown as removable chips. Tapping × deletes the attachment from the server immediately.

**REQ-COMPOSE-28** Attachment files cached during the re-upload flow are deleted when the Compose screen leaves the composition.

**REQ-COMPOSE-29** For Forward, `source_message_id` is included in the initial POST /api/v1/drafts (and in every retry of that POST). It is omitted from all subsequent PUT calls.

---

## 9. Search Screen

**REQ-SEARCH-01** The Search screen is accessed via the search icon in the Folder List.

**REQ-SEARCH-02** A search bar is at the top. No search request is issued when the query is empty or whitespace-only; an initial placeholder state ("Enter a search query") is displayed instead.

**REQ-SEARCH-03** The search query has a maximum length of 500 characters (hard cap).

**REQ-SEARCH-04** Optional folder filter: a dropdown listing all folders; default is "All mail". When "All mail" is selected, the search covers all folders **except** Junk (id=7), Drafts (id=3), and Scheduled (id=5). The UI label or tooltip must clarify this exclusion.

**REQ-SEARCH-05** Optional date range: From date and To date via date picker dialogs. `date_from` maps to the start of the selected calendar day in the device's local timezone; `date_to` maps to the start of the following calendar day (exclusive upper bound).

**REQ-SEARCH-06** Whenever the query text or any date filter changes, reset offset to 0 and discard previously loaded results before issuing a new request.

**REQ-SEARCH-07** Results are rendered as a list of message summaries. Each result shows the FTS `snippet` below the subject. Matched keywords surrounded by `**` markers in the snippet are rendered in bold; the `**` delimiters are stripped from the displayed text.

**REQ-SEARCH-08** Tapping a result navigates to Message Detail.

**REQ-SEARCH-09** When returning from Message Detail after a destructive action, the search screen removes the affected message from the in-memory results list without re-fetching (via `removedMessageId`).

**REQ-SEARCH-10** Long-press is not supported; multi-select is not available on the Search screen.

**REQ-SEARCH-11** Infinite scroll: same page size and offset increment as the Message List (limit=50, offset += 50). Stop paginating when the returned item count is less than 50. A page returning 0 items is discarded.

**REQ-SEARCH-12** Pull-to-refresh reloads from offset 0 with the current filters applied.

**REQ-SEARCH-13** Empty state: when 0 results are returned for a submitted query, show centred "No results" text.

**REQ-SEARCH-14** Error state on initial load: show a centred error message with a Retry button.

---

## 10. Settings Screen

**REQ-SETTINGS-01** The Settings screen has six tabs: Identities, Folders, Filters, Spam, Contacts, Preferences.

**REQ-SETTINGS-02** At the bottom of the Settings screen (not a tab) is a Server entry showing the current server URL and a Change server button that navigates to Setup.

### Identities Tab

**REQ-SETTINGS-ID-01** Fetches the identity list on enter. Show an inline error with a Retry button if the fetch fails.

**REQ-SETTINGS-ID-02** Identities are displayed in the server-returned order (ordered by position, then id). Each row shows name and address.

**REQ-SETTINGS-ID-03** Identity management (create, edit, delete, set default) is out of scope for v1. The tab is read-only.

### Folders Tab

**REQ-SETTINGS-FOLDERS-01** Fetches the folder list on enter. Show an inline error with a Retry button if the fetch fails.

**REQ-SETTINGS-FOLDERS-02** Only user-created folders (id ≥ 100) can be renamed or deleted. Built-in folders are shown but edit/delete controls are hidden.

**REQ-SETTINGS-FOLDERS-03** A + FAB opens a dialog with a Name text field to create a new folder. On 201 success, dismiss the dialog and reload the folder list.

**REQ-SETTINGS-FOLDERS-04** Renaming a folder opens the same dialog pre-filled with the current name.

**REQ-SETTINGS-FOLDERS-05** Deleting a folder shows a confirmation dialog: "Messages will be moved to Trash".

**REQ-SETTINGS-FOLDERS-06** Error handling for folder mutations:
- Create or Rename on 409 Conflict: show server error message inline in the dialog
- Rename on 400: show server error message inline in the dialog
- Rename on 404 (folder deleted between list load and rename): close the dialog and show a Snackbar with the error message

### Filters Tab

**REQ-SETTINGS-FILTERS-01** Fetches the filter list on enter. Show an inline error with a Retry button if the fetch fails.

**REQ-SETTINGS-FILTERS-02** Each filter row shows the filter name on the first line and a one-line summary on the second line.

**REQ-SETTINGS-FILTERS-03** The summary is built as follows:
- Condition part: list every non-empty match field joined with " AND ": `from contains '<value>'`, `to contains '<value>'`, `subject contains '<value>'`. If all three match fields are empty, display "(matches all)".
- Action part:
  - `move` → `Move to <folder name>` (fall back to `Move to folder #<id>` if folder not found)
  - `trash` → `Move to Trash`
  - `mark_read` → `Mark as read`
  - `drop` → `Drop`
- Full summary: `If <condition> → <action>`

**REQ-SETTINGS-FILTERS-04** The Filters tab reuses the same folder list fetched for the Folders tab; one request is sufficient if both tabs are displayed in the same settings session.

**REQ-SETTINGS-FILTERS-05** The `stop` flag is not shown in the row. Filter editing (add, edit, delete, reorder) is out of scope for v1.

### Spam Tab

**REQ-SETTINGS-SPAM-01** On enter, fetch current spam filter settings and load them into the form. Show a loading indicator while fetching. Show an inline error with a Retry button if the fetch fails.

**REQ-SETTINGS-SPAM-02** The form has: an enabled toggle, a score header name text field, and a score threshold text field. There is an explicit Save button.

**REQ-SETTINGS-SPAM-03** The `score_threshold` field accepts non-negative floating-point values (minimum 0; a value of 0 means "match any message whose score header parses as a number").

**REQ-SETTINGS-SPAM-04** The `score_header` field must be non-empty after trimming.

**REQ-SETTINGS-SPAM-05** On Save success, show a brief informational Snackbar ("Settings saved"). On 400, show the server error message inline near the relevant field.

### Contacts Tab

**REQ-SETTINGS-CONTACTS-01** Paginated contact list (limit=50). Contacts are ordered by name (empty names last), then address. Each row shows name on the first line and email on the second.

**REQ-SETTINGS-CONTACTS-02** Search field filters contacts (debounced 300 ms). When the search field is cleared, re-issue the request with no query parameter to restore the full list. When the query changes (including clearing), reset offset to 0 and discard previously loaded results.

**REQ-SETTINGS-CONTACTS-03** Infinite scroll (offset += 50); stop when returned count is less than 50.

**REQ-SETTINGS-CONTACTS-04** If the initial fetch fails, show an inline error with a Retry button. If a subsequent page fetch fails, show an inline error with a Retry button at the bottom of the list (retries the same page, not a full reload).

**REQ-SETTINGS-CONTACTS-05** Tapping a contact opens an edit dialog. A + FAB creates a new contact.

**REQ-SETTINGS-CONTACTS-06** Both add and edit dialogs have Name (optional) and Email (required, RFC 5322 addr-spec) fields.

**REQ-SETTINGS-CONTACTS-07** On Create (201) success or Update (200) success, dismiss the dialog and reload the list.

**REQ-SETTINGS-CONTACTS-08** On Update 404 (contact deleted from another client), close the dialog, show a Snackbar with the error message, and reload the list.

**REQ-SETTINGS-CONTACTS-09** On 409 Conflict (duplicate address) for Create or Update, show the server error message inline in the dialog.

**REQ-SETTINGS-CONTACTS-10** On 400 for Create or Update, show the server error message inline in the dialog.

**REQ-SETTINGS-CONTACTS-11** Update uses full-replacement semantics: both `address` and `name` fields must always be sent, even when `name` is empty.

**REQ-SETTINGS-CONTACTS-12** Deleting a contact shows a confirmation dialog before proceeding.

### Preferences Tab

**REQ-SETTINGS-PREFS-01** App-level preferences:

| Preference              | Default | Options                                     |
|-------------------------|---------|---------------------------------------------|
| Dark mode               | System  | System / Light / Dark                       |
| Message list density    | Normal  | Compact / Normal / Relaxed                  |
| New-mail notifications  | Off     | On / Off                                    |

**REQ-SETTINGS-PREFS-02** When the user toggles New-mail notifications on, request the POST_NOTIFICATIONS permission if not already granted. If the user denies the permission, revert the toggle to Off and show a Snackbar explaining that the notification permission was denied.

---

## 11. New Mail Notifications

**REQ-NOTIF-01** A notification channel (`mymail_new_mail`) is created on app startup before any notification is posted. Creation is idempotent.

**REQ-NOTIF-02** Foreground polling: while the app is in the foreground, poll `GET /api/v1/folders` every 30 seconds on the main activity's lifecycle scope. The first poll result is used as the in-memory baseline; no notification fires on that first result. On subsequent polls, when the Inbox `unread_count` is higher than the previous baseline, fire a notification (if permission granted and preference enabled) and update the baseline.

**REQ-NOTIF-03** The in-memory foreground-poller baseline resets on activity recreation (e.g. screen rotation). The first poll after recreation does not fire a notification.

**REQ-NOTIF-04** If a foreground poll fails (network error or non-401 HTTP error), log the failure and continue. The next 30-second tick retries automatically. No UI is shown for foreground poll failures.

**REQ-NOTIF-05** Background polling: a periodic worker runs at a minimum interval of 15 minutes. The worker requires a network connection. The worker calls `GET /api/v1/folders`, compares the Inbox `unread_count` against the last-known count persisted in app storage, and posts a notification if it increased.

**REQ-NOTIF-06** On the first background worker run (no persisted value), the worker stores the current `unread_count` as the baseline and does not post a notification.

**REQ-NOTIF-07** Notification content for new mail: title = "New mail", body = "You have new messages in your inbox". Tapping navigates to the Inbox message list with the correct back-stack.

**REQ-NOTIF-08** The background worker checks whether the app is in the foreground and skips its poll and notification if so, deferring to the foreground poller.

**REQ-NOTIF-09** The background worker is enqueued immediately after a successful Connect on the Setup screen, and again on app cold-start if credentials are already stored. Duplicate enqueues are no-ops.

**REQ-NOTIF-10** If the background worker receives HTTP 401: cancel the periodic work so polling stops. If the app is in the background, also post a notification with title = "Session expired" and body = "Tap to sign in again" that navigates to Setup. If the app is in the foreground, skip posting the notification (the foreground 401 handler navigates to Setup instead).

**REQ-NOTIF-11** The last-known Inbox unread count is persisted under the key `inbox_unread_count` in plain app storage file `mymail_prefs`.

**REQ-NOTIF-12** When the Inbox Message List screen enters the composition, all posted new-mail notifications are cleared, the persisted unread-count baseline is reset, and the in-memory foreground-poller baseline is reset.

---

## 12. Authentication

**REQ-AUTH-01** Every outgoing request includes an `Authorization: Basic <base64(username:password)>` header, using the currently stored credentials.

**REQ-AUTH-02** No `Origin` header is sent; the server's CSRF check is skipped automatically for native clients.

**REQ-AUTH-03** On receiving HTTP 401: when the app is in the foreground, navigate to the Setup screen, clearing the entire back-stack. This is a no-op if the app is already on the Setup screen.

**REQ-AUTH-04** When the app is in the background and a 401 is received via the background worker, the background worker handles it per REQ-NOTIF-10.

**REQ-AUTH-05** After a successful reconnect, the network client is rebuilt with the new server URL. Credentials are picked up automatically on the next request.

---

## 13. Error Handling

**REQ-ERR-01** Network errors and timeouts trigger a Snackbar with a "Retry" action. The request is automatically retried once after 2 seconds; if the retry also fails, the Snackbar persists until the user taps Retry.

**REQ-ERR-02** The auto-retry policy does **not** apply to POST /api/v1/drafts/{id}/send (non-idempotent). On a network error or timeout during send, show a Snackbar with a Retry action but do not auto-retry.

**REQ-ERR-03** HTTP 400 Bad Request: show the server `error` message inline near the triggering UI element.

**REQ-ERR-04** HTTP 401 Unauthorized: navigate to the Setup screen (see Authentication section).

**REQ-ERR-05** HTTP 404 on a detail screen: show "Not found" inline in the detail pane.

**REQ-ERR-06** HTTP 404 on a list screen: navigate back to the Folder List with a Snackbar error.

**REQ-ERR-07** HTTP 404 on Compose draft fetch on open: show a centred error with a Retry button; do not start the auto-save loop.

**REQ-ERR-08** HTTP 404 on Search or Settings sub-tabs: show a centred inline error message with a Retry button.

**REQ-ERR-09** HTTP 409 Conflict: show the server `error` message inline.

**REQ-ERR-10** HTTP 500 Internal Server Error: show a Snackbar with the server `error` message. Exception: 400 and 500 on POST /api/v1/drafts/{id}/send are shown inline above the Send button.

---

## 14. Date / Time Display

**REQ-DATE-01** Timestamps are displayed in the device's local timezone using these adaptive rules (evaluated top-to-bottom; first match wins):

| Age                | Format                     | Example           |
|--------------------|----------------------------|-------------------|
| < 1 minute         | "just now"                 | "just now"        |
| 1 min – < 1 hour   | Relative ("42 min ago")    | "42 min ago"      |
| Same day, ≥ 1 hour | Time only (HH:mm, 24-hour) | "14:32"           |
| Yesterday          | "Yesterday HH:mm"          | "Yesterday 09:15" |
| 2–6 days ago       | Weekday + time             | "Mon 14:32"       |
| 7 days – same year | Short date + time          | "Apr 3, 14:32"    |
| Previous years     | Short date with year only  | "Apr 3, 2023"     |

**REQ-DATE-02** "Yesterday" means the calendar day before today in the device's local timezone. "Same day" means the same calendar day as today. Day boundaries are at midnight in the device's local timezone.

**REQ-DATE-03** "7 days – same year" means the message date is ≥ 7 full calendar days before today AND in the same calendar year as today. "Previous years" means a different calendar year from today.

**REQ-DATE-04** "2–6 days ago" covers calendar days 2 through 6 before today at midnight boundaries.

**REQ-DATE-05** Message detail always shows the full form with timezone abbreviation: `EEE, d MMM yyyy, HH:mm z` (e.g. "Mon, 3 Apr 2023, 14:32 CEST"). Use 24-hour time and the device's locale for day/month names.

**REQ-DATE-06** Long-pressing a date/time chip copies the full ISO 8601 string to the clipboard. On Android 13+ the system displays a clipboard confirmation automatically. On Android 12L and below, show a brief Snackbar confirming the copy.

---

## 15. Security Constraints

**REQ-SEC-01** TLS hostname verification must never be disabled. No trust-all TrustManager or custom SSLSocketFactory may be used in any build variant.

**REQ-SEC-02** In release builds, cleartext (plain HTTP) traffic is prohibited at the OS level via the network security configuration.

**REQ-SEC-03** The debug build may permit cleartext to allow plain HTTP, consistent with the UI-level URL validation for debug builds.

**REQ-SEC-04** The Authorization header must never be logged. Only URL, method, response code, and response time may be logged in debug builds.

**REQ-SEC-05** Credentials must never appear in any log call, crash report, or analytics.

**REQ-SEC-06** Attachment FileProvider sharing is restricted to the `attachments/` subdirectory only, not all of the app's cache directory.

**REQ-SEC-07** Dangerous MIME types are blocked before opening attachments (see REQ-MSGDETAIL-09).

**REQ-SEC-08** Attachment downloads exceeding 100 MB or with absent/unparseable Content-Length are rejected (see REQ-MSGDETAIL-10).

**REQ-SEC-09** Server URL, username, and password must be stored exclusively in encrypted storage and must never be written to plain storage.

**REQ-SEC-10** The subject field must have all CR and LF characters stripped before saving or sending, to prevent MIME header injection (see REQ-COMPOSE-02).

---

## 16. Out of Scope (v1)

- Offline support / local message cache
- Push notifications (FCM)
- HTML message body rendering
- Rich text / HTML compose
- Inline attachment preview
- Snooze creation or re-snooze actions
- Identity management (create, edit, delete, set default)
- Filter editing (add, edit, delete, reorder)
- Scheduled send ("Send later")
- Folder reordering
- PGP / S-MIME
- Multi-account support
- Tablet adaptive layout
- Download raw `.eml` file
- Flagged / starred messages UI
