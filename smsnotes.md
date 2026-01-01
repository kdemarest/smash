# SMS Notes

## Inbound SMS Flow (Android KitKat+)

```
┌─────────────────────────────────────────────────────────────────────┐
│                        Carrier / Radio                               │
│                                                                      │
│  SMS arrives at radio level (RIL)                                   │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                   Android Telephony Stack                            │
│                                                                      │
│  - System service receives from RIL                                 │
│  - On some OS versions/OEMs: writes to SMS provider directly        │
│  - On others: delivers to default SMS app which writes              │
│  - Broadcasts SMS_DELIVER_ACTION to default SMS app only            │
└─────────────────────────────────┬───────────────────────────────────┘
                                  │
                                  ▼
┌─────────────────────────────────────────────────────────────────────┐
│                     Default SMS App (Smash)                          │
│                                                                      │
│  1. Receive broadcast: Telephony.Sms.Intents.SMS_DELIVER_ACTION     │
│  2. Parse PDUs from intent                                          │
│  3. INSERT into system provider (required responsibility)           │
│  4. Process message (forward, execute command, etc.)                │
└─────────────────────────────────────────────────────────────────────┘
```

## Default SMS App Contract

As the default SMS app, Smash is the **only** app that can:
- Write to the system SMS/MMS provider
- Delete or modify SMS/MMS in that provider

**Responsibilities:**
1. Persist all received SMS into the system provider
2. Persist sent messages as MESSAGE_TYPE_SENT
3. Honor standard SMS provider schema

**Consequences of NOT persisting:**
- Messages exist only in app memory/logs
- Other SMS apps (Google Messages) won't see them if user switches
- System backups won't include them
- MessageSyncManager can't find them (queries the provider)

## Writing to the SMS Provider

Use `ContentResolver.insert()` with `Telephony.Sms.Inbox.CONTENT_URI`:

```kotlin
val values = ContentValues().apply {
    put(Telephony.Sms.ADDRESS, senderAddress)
    put(Telephony.Sms.BODY, messageBody)
    put(Telephony.Sms.DATE, System.currentTimeMillis())
    put(Telephony.Sms.DATE_SENT, dateSentFromPdu)
    put(Telephony.Sms.READ, 0)  // 0 = unread
    put(Telephony.Sms.SEEN, 0)  // 0 = not seen
    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX)
}

val uri = contentResolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
```

**Required columns:**
| Column | Description |
|--------|-------------|
| `ADDRESS` | Sender phone number |
| `BODY` | Message text |
| `DATE` | Timestamp when received (millis) |
| `DATE_SENT` | Timestamp when sent by sender (millis, from PDU) |
| `READ` | 0 = unread, 1 = read |
| `SEEN` | 0 = not seen by user, 1 = seen |
| `TYPE` | MESSAGE_TYPE_INBOX (1) for received |

**Important:** Insert will fail/be blocked if app is not the default SMS app.

## Outbound SMS

When sending SMS, persist to provider:

```kotlin
val values = ContentValues().apply {
    put(Telephony.Sms.ADDRESS, recipientAddress)
    put(Telephony.Sms.BODY, messageBody)
    put(Telephony.Sms.DATE, System.currentTimeMillis())
    put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_SENT)
    put(Telephony.Sms.READ, 1)
    put(Telephony.Sms.SEEN, 1)
}

val uri = contentResolver.insert(Telephony.Sms.Sent.CONTENT_URI, values)
```

## SMS vs MMS Provider Differences

| Aspect | SMS | MMS |
|--------|-----|-----|
| Provider URI | `content://sms/inbox` | `content://mms/inbox` |
| Data in broadcast | Yes (PDU in intent) | No (notification only) |
| Download required | No | Yes (`downloadMultimediaMessage()`) |
| System auto-inserts | Depends on OS/OEM | No (as default app) |
| Must parse PDU | For timestamp/details | Yes (for everything) |

## MessageSyncManager Interaction

`MessageSyncManager` queries `Telephony.Sms.Inbox.CONTENT_URI` for messages with `_id > lastWatermark`.

For this to work:
1. Smash must write received SMS to the provider
2. The inserted row gets an `_id` assigned by the system
3. Sync finds new rows by ID comparison

If Smash doesn't insert, sync finds nothing (the messages don't exist in the provider).

## Multipart SMS

Long messages (>160 chars) arrive as multiple PDUs in a single broadcast. The app must:
1. Concatenate all parts (same sender)
2. Insert as a single message in the provider

`SmsReceiver` already groups by sender and concatenates before processing.

## Permissions

As default SMS app, Smash has:
- `RECEIVE_SMS` - receive broadcasts
- `SEND_SMS` - send messages
- `READ_SMS` - query provider
- `WRITE_SMS` - insert/update/delete in provider (implicit for default app)

The `WRITE_SMS` capability is granted implicitly when the app becomes the default SMS handler.
