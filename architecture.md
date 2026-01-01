# smash Architecture

## Overview

smash is a foreground service that intercepts SMS and MMS messages, processes commands, and forwards messages to configured targets (phone numbers and email addresses).

**Smash is a repeater/forwarder, NOT a user-facing messaging app.** This distinction affects architectural decisions around message persistence (see "Message Persistence Philosophy" below).

```
┌─────────────────────────────────────────────────────────────────────┐
│                        SmashService                                  │
│                    (Foreground Service)                              │
│                                                                      │
│  ┌──────────────┐  ┌─────────────┐  ┌─────────────┐                 │
│  │MessageProc.  │  │CommandProc. │  │MessageFwd.  │                 │
│  │(single queue)│  │             │  │             │                 │
│  └──────┬───────┘  └──────┬──────┘  └──────┬──────┘                 │
│         │                 │                │                         │
└─────────┼─────────────────┼────────────────┼─────────────────────────┘
          │                 │                │
          ▼                 ▼                ▼
┌──────────────────────────────────────────────────────────────────────┐
│                     IncomingMessage                                   │
│            (unified format for SMS and MMS)                          │
│  - sender: String                                                     │
│  - body: String                                                       │
│  - timestamp: Long                                                    │
│  - attachments: List<MediaAttachment>  (empty for SMS)               │
└──────────────────────────────────────────────────────────────────────┘
```

## Unified Message Flow

Both SMS and MMS follow the same pattern: **extract → enqueue → process**

```
┌─────────────────┐         ┌─────────────────┐
│ SmsReceiver     │         │ MmsObserver     │
│ (main thread)   │         │ (bg looper)     │
│                 │         │                 │
│ extract from    │         │ extract from    │
│ intent (instant)│         │ content://mms   │
│      │          │         │      │          │
└──────┼──────────┘         └──────┼──────────┘
       │                           │
       ▼                           ▼
┌──────────────────────────────────────────────┐
│         MessageProcessor Queue               │
│         (LinkedBlockingQueue)                │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│      MessageProcessor Thread (single)        │
│                                              │
│  while(running) {                            │
│      message = queue.take()                  │
│      handleIncomingMessage(message)          │
│  }                                           │
└──────────────────────┬───────────────────────┘
                       │
                       ▼
┌──────────────────────────────────────────────┐
│ handleIncomingMessage()                      │
│                                              │
│ if (isCommand):                              │
│     CommandProcessor → reply via SMS         │
│ else:                                        │
│     MessageForwarder → forward to targets    │
└──────────────────────────────────────────────┘
```

**Key properties:**
- Single queue, single consumer thread
- All messages processed sequentially in order received
- Type-agnostic processing (SMS and MMS handled identically)
- Network I/O happens on processor thread (not main thread)

## SMS Implementation

SMS is implemented reliably because the message data is **embedded directly in the broadcast intent**.

### Architecture

```
SMS arrives (carrier)
       │
       ▼
┌──────────────────────────────────────────┐
│ Android Telephony Stack (RIL)            │
│                                          │
│ Broadcasts SMS_DELIVER_ACTION            │
│ Intent contains: PDU with sender + body  │
│ (only delivered to default SMS app)      │
└────────────────────┬─────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────┐
│ SmsReceiver.onReceive()                  │
│                                          │
│ messages = getMessagesFromIntent(intent) │
│ // Data is RIGHT HERE - no guessing      │
│                                          │
│ For each message:                        │
│   1. Write to system SMS provider        │
│      (content://sms/inbox)               │
│   2. Create IncomingMessage              │
│   3. Enqueue to MessageProcessor         │
└──────────────────────────────────────────┘
```

### Default SMS App Responsibility

As the default SMS app, Smash **must** write received messages to the system
SMS provider (`Telephony.Sms.Inbox.CONTENT_URI`). This is the implicit contract:

- Only the default SMS app can write to the provider
- Without this, messages won't appear in other apps (e.g., Google Messages)
- System backups won't include the messages
- MessageSyncManager relies on querying this provider

### Message Persistence Philosophy (Repeater vs User App)

Smash is a **repeater**, not a user messaging app. This affects persistence:

| Message Type | Persisted? | Why |
|--------------|------------|-----|
| **Received SMS** | ✅ Yes, to `content://sms/inbox` | Required for MessageSyncManager, backups, other apps |
| **Received MMS** | ✅ Yes, to `content://mms/inbox` | Same reasons |
| **Sent SMS** | ❌ No | User didn't compose these - they're automated forwards |
| **Sent MMS** | ❌ No | Same - would clutter sent folder with traffic user never initiated |

The "default SMS app contract" assumes user-initiated messages. Smash intentionally
breaks this assumption. Sent messages are logged to AWS for audit purposes instead.

```kotlin
val values = ContentValues().apply {
    put(Telephony.Sms.ADDRESS, sender)
    put(Telephony.Sms.BODY, body)
    put(Telephony.Sms.DATE, dateReceived)
    put(Telephony.Sms.DATE_SENT, dateSent)
    put(Telephony.Sms.READ, 0)
    put(Telephony.Sms.SEEN, 0)
    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
}
contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
```

### Why SMS is Reliable

1. **Direct data access**: `Telephony.Sms.Intents.getMessagesFromIntent(intent)` extracts the exact message from the broadcast
2. **No database queries**: We don't need to guess which row in a database corresponds to this message
3. **Synchronous**: The data is available immediately in `onReceive()`

### Multipart SMS

Long messages (>160 chars) arrive as multiple PDUs in the same intent. `SmsReceiver` groups them by sender and concatenates before processing.

## MMS Implementation

MMS is more complex because the broadcast only signals that an MMS is available—the actual content is downloaded asynchronously and stored in a content provider.

### Architecture

```
MMS notification arrives
       │
       ▼
┌──────────────────────────────────────────┐
│ Android System                           │
│ Broadcasts WAP_PUSH_DELIVER_ACTION       │
│ Intent contains: notification only       │
│ (NOT the actual MMS content)             │
└────────────────────┬─────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────┐
│ MmsReceiver.onReceive()                  │
│                                          │
│ 1. Extract transaction ID from PDU       │
│ 2. Extract contentLocation (MMSC URL)    │
│ 3. Create temp file URI for download     │
│ 4. Call downloadMultimediaMessage()      │
│    with PendingIntent to MmsDownload-    │
│    Receiver                              │
└────────────────────┬─────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────┐
│ Android System                           │
│                                          │
│ Downloads MMS from carrier MMSC          │
│ Writes raw PDU to our temp file URI      │
│                                          │
│ ⚠️  CRITICAL: System does NOT insert     │
│     into content://mms/inbox!            │
│     We MUST parse the PDU ourselves.     │
└────────────────────┬─────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────┐
│ MmsDownloadReceiver.onReceive()          │
│                                          │
│ 1. Read raw PDU bytes from temp file     │
│ 2. Parse using PduParser (mms-pdu lib)   │
│ 3. Cast to RetrieveConf                  │
│ 4. Persist to content://mms/inbox        │
│    (PduPersister - required for sync)    │
│ 5. Extract sender from getFrom()         │
│ 6. Extract body from text/plain parts    │
│ 7. Extract images from image/* parts     │
│ 8. Create IncomingMessage                │
│ 9. SmashService.enqueueMessage()         │
└──────────────────────────────────────────┘
```

### Why Direct PDU Parsing (Not ContentObserver)

> **IMPORTANT ARCHITECTURAL NOTE:**
> 
> When your app is the default SMS app and uses `downloadMultimediaMessage()`,
> the system downloads the MMS PDU to the Uri you provide but does **NOT**
> automatically populate `content://mms/inbox`. The telephony database is not
> updated.
> 
> This is confirmed by AOSP's own Messaging app source code: they parse the
> downloaded PDU directly and store messages in their own internal database,
> not the system telephony database.
> 
> A ContentObserver on `content://mms` will **never fire** because nothing is
> being inserted there. The correct approach is to parse the PDU file directly.

**Wrong approach** (what we tried first):
```
// Register ContentObserver on content://mms
// Wait for onChange() to fire after download
// Query content://mms WHERE _id > lastKnownId

// WRONG: System never inserts into content://mms!
// ContentObserver never fires. Polling finds nothing.
```

**Correct approach** (what we do now):
```
MmsDownloadReceiver.onReceive():
    val pduBytes = contentResolver.openInputStream(downloadedUri).readBytes()
    val pdu = PduParser(pduBytes, supportMmsContentDisposition).parse()
    val retrieveConf = pdu as RetrieveConf
    
    val sender = retrieveConf.from.string
    val body = extractTextParts(retrieveConf.body)
    val images = extractImageParts(retrieveConf.body)
    // CORRECT: Parse PDU directly, no database involved
```

### MMS PDU Structure (RetrieveConf)

When parsed using the `com.google.android.mms.pdu_alt` library:

```
RetrieveConf (parsed from raw PDU bytes)
├── getFrom()                    # Sender address (EncodedStringValue)
├── getSubject()                 # Subject line (if any)
├── getDate()                    # Timestamp
└── getBody() -> PduBody
    └── getPart(i) -> PduPart
        ├── getContentType()     # "text/plain", "image/jpeg", etc.
        ├── getData()            # Raw bytes for small parts
        └── getDataUri()         # Uri for large parts
```

> **Note:** The `content://mms/*` provider URIs are still used by other apps
> (like the stock messaging app) that insert their own data. Our app doesn't
> use them because `downloadMultimediaMessage()` gives us the PDU directly.

### Image Handling

```
MMS with image (parsed from PDU)
       │
       ▼
┌──────────────────────────────────────────┐
│ MmsDownloadReceiver extracts:            │
│                                          │
│ MediaAttachment(                         │
│   data = pduPart.getData(),              │
│   mimeType = "image/jpeg"                │
│ )                                        │
│                                          │
│ (Image bytes come directly from PDU,     │
│  NOT from content://mms/part)            │
└────────────────────┬─────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────┐
│ MessageForwarder                         │
│                                          │
│ Email target:                            │
│   - Bytes already extracted from PDU     │
│   - Base64 encode                        │
│   - Include in HTTP POST to Lambda       │
│                                          │
│ Phone target:                            │
│   - Build MMS PDU with SendReq           │
│   - Compress images to JPEG ≤100KB       │
│   - Send via SmsManager.sendMms()        │
└──────────────────────────────────────────┘
```

## AWS Architecture (Email Forwarding)

```
┌─────────────┐     ┌─────────────┐     ┌─────────────┐     ┌─────────┐
│ Android App │     │ API Gateway │     │   Lambda    │     │   SES   │
│             │     │             │     │             │     │         │
│ POST {      │────►│             │────►│ Upload to   │     │  Send   │
│  origin,    │     │             │     │ S3 bucket   │────►│  email  │
│  dest_email,│     │             │     │             │     │  with   │
│  body,      │     │             │     │ Build HTML  │     │  <img>  │
│  images:[]  │     │             │     │ with S3 URLs│     │  tags   │
│ }           │     │             │     │             │     │         │
└─────────────┘     └─────────────┘     └──────┬──────┘     └─────────┘
                                               │
                                               ▼
                                        ┌─────────────┐
                                        │  S3 Bucket  │
                                        │             │
                                        │ smash-images│
                                        │ -{accountId}│
                                        │             │
                                        │ Public read │
                                        │ 90-day TTL  │
                                        └─────────────┘
```

### S3 Bucket Naming Convention

The bucket name is deterministic: `smash-images-{AWS_ACCOUNT_ID}`

This allows:
- `provision-s3.js` to create the bucket
- Lambda to auto-detect the bucket name via STS
- Test scripts to derive the bucket name

No configuration sharing required.

## Threading Model

```
┌─────────────────────────────────────────────────────────────┐
│ Main Thread                                                  │
│                                                              │
│ - Service lifecycle (onCreate, onDestroy)                    │
│ - Notification updates                                       │
│ - SmsReceiver.onReceive() (extract + enqueue only, no I/O)  │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ MmsObserver HandlerThread                                    │
│                                                              │
│ - ContentObserver callbacks (onChange)                       │
│ - Database queries to content://mms                          │
│ - Enqueues IncomingMessage to shared queue                  │
│ - No network I/O here                                        │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│ MessageProcessor Thread (single)                             │
│                                                              │
│ - Consumes from shared queue                                 │
│ - Calls handleIncomingMessage()                              │
│ - Command processing                                         │
│ - Network I/O for forwarding (HTTP POST to Lambda)           │
│ - SMS sending                                                │
└─────────────────────────────────────────────────────────────┘
```

**Design principles:**
- Main thread does no I/O (database or network)
- MmsObserver thread does database I/O only (queries content://mms)
- MessageProcessor thread does network I/O (forwarding)
- Single queue ensures sequential processing
- No ad-hoc thread spawning

### Thread Safety

| Resource | Mechanism |
|----------|-----------|
| `MessageProcessor.queue` | `LinkedBlockingQueue` (inherently thread-safe) |
| `MmsObserver.processedIds` | `synchronized` block |
| `MmsObserver.lastKnownMmsId` | Single-threaded HandlerThread looper |
| `ConfigManager` | `ReentrantReadWriteLock` + `@Volatile` cache |
| `SmashLogger` | `ReentrantLock` on all writes |
| `SmashService.instance` | `@Volatile` |

No shared mutable state crosses thread boundaries without synchronization.

## AWS Infrastructure

### Lambda Functions

| File | Purpose |
|------|------|
| `smash-email-forwarder.mjs` | Receives JSON with base64 images, uploads to S3, sends email via SES with `<img>` tags |
| `smash-log-receiver.mjs` | Remote log receiver (optional) |

### S3 Bucket

- Name: `smash-images-{AWS_ACCOUNT_ID}` (auto-derived via STS)
- Public read access for image URLs in emails
- 90-day lifecycle expiration
- Provisioned via `provision-s3.js`

## MMS Sending (PDU Composition)

Sending MMS requires building a valid PDU. Required `SendReq` fields:

```kotlin
sendReq.transactionId = "UniqueId${timestamp}".toByteArray()
sendReq.mmsVersion = PduHeaders.MMS_VERSION_1_2
sendReq.date = System.currentTimeMillis() / 1000
sendReq.from = EncodedStringValue("insert-address-token")  // carrier replaces
sendReq.addTo(EncodedStringValue(destination))
sendReq.contentType = "application/vnd.wap.multipart.related".toByteArray()
```

Each `PduPart` requires: `contentType`, `contentId`, `contentLocation`, `data`.

PDU written to temp file, sent via `SmsManager.sendMultimediaMessage()`, callback cleans up.

**Note:** Sent MMS is intentionally NOT persisted to `content://mms/sent`. Smash is a
repeater - these are automated forwards, not user-composed messages. See "Message
Persistence Philosophy" above.

## Memory Management

### processedIds (MmsObserver)

`LinkedHashSet<Long>` capped at 100 entries with FIFO eviction. Prevents reprocessing same MMS while bounding memory.

### PDU Temp Files

Files in `cacheDir/mms_send/` cleaned up:
- By `MmsSentReceiver` on send callback
- By `MmsUtils.sendMms()` for files older than 1 hour (stale cleanup)
