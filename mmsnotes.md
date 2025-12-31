
# MMS Code Review Notes

## What’s Good / Aligned with Your Spec

- **No “sleep then query last row” hack:**
	- `MmsObserver` tracks `lastKnownMmsId` and queries `_id > ?` in ascending order. This is the cleanest minimal strategy.
- **Work off main thread:**
	- `MmsObserver` uses a `HandlerThread`, so querying and part extraction won’t block the broadcast thread/UI.
- **Forwarding only images to email (not MMS):**
	- `MessageForwarder` intentionally doesn’t attempt MMS re-send to phone targets, and annotates with `[x images not forwarded]` — good and minimal.
- **Attachment extraction:**
	- `EmailForwarder.forwardWithAttachments()` reads attachment bytes via `contentResolver.openInputStream(uri)` which is exactly what you want for `content://mms/part/...` URIs.

---

## What’s Wrong / Likely Buggy

- **Bug: duplicate-detection can prematurely stop processing**
	- In `MmsObserver.checkForNewMms()`, when an ID is already in `processedIds`, the code does `return@use`. That exits the entire cursor loop early, so you can skip later MMSes in the same query result.
	- *Minimal fix conceptually:* it should `continue`, not `return`.
- **`getHighestMmsId()` uses `"_id DESC LIMIT 1"`**
	- Many Android content providers *happen* to accept `LIMIT` in the sortOrder string, but it’s not guaranteed behavior. On devices/providers that don’t support it, you can get incorrect results or exceptions.
	- *Minimal safer pattern:* sort by `"_id DESC"` and just read the first row (no `LIMIT`).
- **MMS sender extraction is too optimistic**
	- `extractMmsSender()` queries `type = 137` and returns `address` verbatim.
	- In real MMS databases you often see placeholders like `insert-address-token` or weird formatting; you should filter those out (otherwise you’ll log/forward garbage “senders”).
- **MmsReceiver downloads to a file, but the success receiver ignores the file**
	- Right now `MmsReceiver` creates a cache `.pdu` file + passes a `PendingIntent` to `downloadMultimediaMessage()`, but `MmsDownloadReceiver` only logs success/failure and never uses the `pdu_path`.
	- That’s not *wrong* if your real extraction is always via `content://mms` (and it is), but it’s a smell: you’re doing extra work/permissions without any functional benefit unless the provider population depends on the download call (device-dependent).
	- If you keep it (reasonable), at least be clear that the file is only a download sink and not a parsing input.
- **Hard-coded MMS service package grant is fragile**
	- `grantUriPermission("com.android.mms.service", ...)` works on AOSP-like stacks, but OEMs can vary. If MMS downloads fail on a specific device, this is a prime suspect.
	- *Minimal robustness:* grant to any resolved handler packages for the internal MMS download service (or broaden the grant via the `PendingIntent` mechanism instead of package hard-coding). If you want to stay minimal, just be aware this is a device-compat hazard.

---

## “Could Be Better” (Minimal, High Value, Not Feature Creep)

- **PendingIntent mutability**
	- `PendingIntent.FLAG_MUTABLE` is not needed here and is generally best avoided. You can keep the exact behavior and use immutable.
- **Content observer noise**
	- You register on `content://mms` with descendants; you then query only `content://mms/inbox`. That’s fine, but it can trigger on a lot of unrelated changes. Minimal improvement: register directly on `content://mms/inbox` if you only care about inbox.
- **Memory / payload size risk for email**
	- `EmailForwarder` base64-encodes full image bytes into JSON. That’s simple and minimal, but it can explode payload sizes and memory usage for large images. If you ever see OOMs or HTTP 413/502 issues, this is why.
	- *Minimal mitigation without redesign:* cap attachment size (log + skip oversized) or reduce the image count.