# Copilot Instructions: Smash Project

## MMS Download/Parsing Subtlety

**When using `SmsManager.downloadMultimediaMessage()` as the default SMS app, the system does NOT insert the downloaded MMS into `content://mms/inbox`.**

- You must parse the downloaded PDU file yourself (see `MmsDownloadReceiver.kt`).
- A `ContentObserver` on `content://mms` will never fire for these downloads.
- This is confirmed by AOSP Messaging app source code.

**Summary:**
> Always parse the downloaded PDU file directly. Do NOT expect the system MMS database to update automatically.

## Smash is a Repeater, Not a User SMS App

Smash forwards messages between email and SMS/MMS. It is NOT a user-facing messaging app.

**Implication for sent messages:**
- We do NOT persist sent MMS to `content://mms/sent` - this is intentional
- The phone owner didn't compose these messages; they're automated forwards
- Persisting would clutter the sent folder with traffic the user never initiated
- Smash has its own audit trail via AWS cloud logging

> The "default SMS app contract" assumes user-initiated messages. Smash intentionally breaks this assumption.

NOTE: Do not build or install. Let the user do that.
Note: Do not deploy the lambdas. Simply tell the user it needs to be done.

Be sure to consult mmsnotes.md when working with MMS!

