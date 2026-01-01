# MMS Notes

## Receiving MMS (IMPLEMENTED)

**Key Discovery:** When using `SmsManager.downloadMultimediaMessage()` as the default SMS app, the system does NOT insert the downloaded MMS into `content://mms/inbox`. You must parse the downloaded PDU file yourself.

- See `MmsDownloadReceiver.kt` for implementation
- Uses `PduParser` and `RetrieveConf` from `com.google.android.mms.pdu_alt`
- A `ContentObserver` on `content://mms` will never fire for these downloads

---

## Sending MMS (RESEARCH - Dec 2025)

### What We Have
The `com.klinkerapps:android-smsmms:5.2.6` library (already in use for parsing) provides everything needed:

**High-level API (recommended):**
- `com.klinker.android.send_message.Transaction` - handles MMS sending
- `com.klinker.android.send_message.Settings` - configuration (MMSC, proxy, etc.)

**Low-level PDU building (if needed):**
- `com.google.android.mms.pdu_alt.SendReq` - outgoing MMS PDU structure
- `com.google.android.mms.pdu_alt.PduBody` - container for parts  
- `com.google.android.mms.pdu_alt.PduPart` - individual text/image parts
- `com.google.android.mms.pdu_alt.PduComposer` - serializes PDU to bytes
- `com.google.android.mms.pdu_alt.EncodedStringValue` - for addresses

### Two Approaches

**Option A: Use Klinker's Transaction class (easiest)**
```kotlin
val settings = Settings()
settings.useSystemSending = true  // Uses SmsManager.sendMultimediaMessage()

val transaction = Transaction(context, settings)
val message = Message(text, recipientAddress)
message.addImage(bitmap)  // or addMedia(uri, mimeType)
transaction.sendNewMessage(message, threadId)
```

**Option B: Build PDU manually + SmsManager (more control)**
1. Create `SendReq` with recipient (`EncodedStringValue`)
2. Create `PduBody`, add `PduPart`s (text, images)
3. Use `PduComposer` to serialize to `byte[]`
4. Write bytes to a file, get `Uri` via `FileProvider`
5. Call `SmsManager.sendMultimediaMessage(context, contentUri, ...)`

### Required SendReq Fields (CRITICAL)
`PduComposer.make()` returns null if required fields are missing! Must set:

```kotlin
val sendReq = SendReq()

// Required headers
sendReq.transactionId = "UniqueId${System.currentTimeMillis()}".toByteArray()
sendReq.mmsVersion = PduHeaders.MMS_VERSION_1_2  // 0x12
sendReq.date = System.currentTimeMillis() / 1000
sendReq.from = EncodedStringValue("insert-address-token".toByteArray())  // Carrier replaces
sendReq.addTo(EncodedStringValue(destinationNumber))
sendReq.contentType = "application/vnd.wap.multipart.related".toByteArray()
```

### Required PduPart Fields
Each part needs contentType, contentId, contentLocation, and data:

```kotlin
// Text part
val textPart = PduPart()
textPart.contentType = "text/plain; charset=utf-8".toByteArray()
textPart.contentId = "<text>".toByteArray()
textPart.contentLocation = "text.txt".toByteArray()
textPart.charset = CharacterSets.UTF_8
textPart.data = text.toByteArray(Charsets.UTF_8)

// Image part
val imagePart = PduPart()
imagePart.contentType = "image/jpeg".toByteArray()
imagePart.contentId = "<image0>".toByteArray()
imagePart.contentLocation = "image0.jpg".toByteArray()
imagePart.name = "image0.jpg".toByteArray()
imagePart.data = compressedImageBytes
```

### Key Considerations
- **Image compression**: MMS has carrier limits (~300KB-1MB typically). May need to resize/compress images.
- **Carrier config**: `SmsManager.getCarrierConfigValues()` provides `MMS_CONFIG_MAX_MESSAGE_SIZE`
- **Permissions**: Already have `SEND_SMS` - may need explicit MMS permission check
- **Threading**: Sending should be async (coroutine)

### Recommendation
Start with **Option A** (Klinker's Transaction class with `useSystemSending = true`). It handles the complexity and uses the modern `SmsManager.sendMultimediaMessage()` API under the hood. Fall back to Option B only if we need finer control.

# Limitations to address

## Smash is a Repeater - Sent MMS Not Persisted (BY DESIGN)

Smash is a **forwarder/repeater**, not a user-facing messaging app. This affects how we handle sent messages:

**We intentionally do NOT persist sent MMS to `content://mms/sent`:**
- The phone owner didn't compose these - they're automated forwards from email/commands
- Persisting would confuse users who switch to another SMS app and see "sent" messages they never wrote
- Smash maintains its own audit trail via AWS cloud logging
- The "default SMS app contract" assumes user-initiated messages; Smash breaks this assumption by design

**We DO persist received MMS to `content://mms/inbox`:**
- Required for MessageSyncManager to catch missed messages
- Ensures messages survive if Smash is uninstalled
- Done in `MmsDownloadReceiver.persistMmsToProvider()`

---

## Message Reliability: MessageSyncManager (IMPLEMENTED)

The `MessageSyncManager` provides a safety net for message reliability:

**Problem:** As the default SMS app, Smash receives broadcasts for incoming messages. If the app is killed, crashes, or misses a broadcast, those messages could be lost.

**Solution:** Periodic sync with the system SMS/MMS database:
- Runs every 5 minutes by default
- Tracks "watermarks" (highest seen SMS/MMS IDs) 
- Only processes unread messages less than 24 hours old
- Logs warnings when missed messages are recovered

**Commands:**
- `sync` - manually trigger a sync, reports found/processed counts
- `sync status` - show current watermarks and last sync time
- `sync reset` - reset watermarks to current (skip old messages)

---

MMS Limitations to Address
Total message size limit (~300KB-1.5MB varies by carrier)

Current: Each image compressed to 100KB, but no aggregate check
Proposal: Log a warning if total PDU exceeds 300KB; optionally reduce image count or quality
Max attachments per MMS (1-10 varies by carrier)

Current: No limit enforced
Proposal: Cap at 3 images, log warning if dropping extras
Text length limit (~5000 chars)

Current: No limit enforced
Proposal: Truncate with "..." if over limit
Unsupported image formats (HEIC/HEIF/WebP)

Current: Passed through as-is, will fail
Proposal: Convert to JPEG (Android's BitmapFactory already handles this during compression)
Video attachments

Current: Filtered out by imageAttachments getter
Proposal: Already handled - no change needed
Audio/vCard/other non-image attachments

Current: Filtered out by imageAttachments getter
Proposal: Already handled - no change needed
GIF handling

Current: Converted to static JPEG (loses animation)
Proposal: Either keep as GIF (risky - may be too large) or accept the loss of animation
PNG transparency

Current: Converted to JPEG (black background for transparency)
Proposal: Accept this or keep small PNGs as PNG
