# smash Architecture

## Overview

smash is a foreground service that intercepts SMS and MMS messages, processes commands, and forwards messages to configured targets (phone numbers and email addresses).

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
│ Android System                           │
│ Broadcasts SMS_DELIVER_ACTION            │
│ Intent contains: PDU with sender + body  │
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
│   sender = message.originatingAddress    │
│   body = message.messageBody             │
│   └──► SmashService.processSms()         │
└──────────────────────────────────────────┘
                     │
                     ▼
┌──────────────────────────────────────────┐
│ SmsProcessor                             │
│                                          │
│ - LinkedBlockingQueue for sequential     │
│   processing                             │
│ - Background thread consumes queue       │
│ - Ensures messages processed in order    │
└──────────────────────────────────────────┘
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
│ 4. Extract sender from getFrom()         │
│ 5. Extract body from text/plain parts    │
│ 6. Extract images from image/* parts     │
│ 7. Create IncomingMessage                │
│ 8. SmashService.enqueueMessage()         │
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
│   - SMS only (no MMS sending yet)        │
│   - Append "[1 image not forwarded]"     │
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
