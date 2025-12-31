App Name: smash
Platform: Android (Kotlin)

# Overview

smash is an Android application that:
- receives incoming SMS messages
- maintains a configuration stored in smash.json
- forwards incoming SMS messages to destinations defined in the config
- accepts remote commands via specially formatted SMS messages
- multiple simultaneous SMS messages are processed sequentially.

This app is just for me - I will never "release" it. Every install will be done by me, by hand, using adb, so various permission grants and so on can generally be scripted.

At the same time, I am using the app to run my business, so all code must be robust and correct. For example, when receiving an MMS notification, just guessing at it and polling the last two minutes would NOT be sufficient. Directly checking the database would be better.

# Runtime Behavior

The app has a variable called "prefix" which defaults to "Cmd".

The app runs continuously, receiving SMS messages and processing them. If the incoming SMS is not a command (see below) it is treated as a forwardable message.

The app must:
- receive SMS
- send outgoing SMS
- perform HTTP POST requests to forward email-type destinations
- maintain smash.json on internal storage
- run as a foreground service to avoid termination
- on run detect if not default SMS and request to be such

# Data Storage: smash.json

smash.json is UTF-8 JSON stored in app-private internal storage.

Fields:
{
  "prefix": "<Cmd_or_another_value>",
  "mailEndpointUrl": "<http_url_or_null>",
  "targets": [ "<phone_or_email>", ... ],
}

prefix rules:
- defaults to "Cmd", and can never be empty

targets rules:
- each element is any non-whitespace token

mailEndpointUrl rules:
- if null or an empty string, forwarding to email destinations is disabled

# Targets

A target is generally either a phone number or an email address.

- detection of email is: string contains '@'
- items in the targets list are stored verbatim from the "add" command, no matter what that content might be (excepting white space of course)
- cleanPhone(number) means:
	- removing everything except a leading plus and digits

# Logging

smash.log is clear text contains a running log of all interactions. A line always starts with yyyy-mm-dd-hh-mm-ss , and is prefixed with [info] or [warning] or [error]. Logging includes, but is not strictly limited to:
- app startup notification, including current smash.json values
- incoming SMS
- all executed commands
- status of every attempt at sending to email to sms (in brief on success, or in detail on error or failure)

There will be few enough log entries that we won't have to limit size or rotate for ten years.

# mailEndpointUrl

- Make standard assumptions for http timeout
- header content type is application/json
- The endpoint is typically an AWS Lambda function that calls SES (see lambda.js), but it conforms to the same specs as sendgrid or postmark.
- Body is 
{
	"origin": "<origin_number>",
	"destination_email": "<element>",
	"body": "<sms_body>",
	"timestamp": <epoch_millis>
}

# Forwarding Logic

For each incoming non-command SMS:
- extract origin phone number (sender)
- extract message body
- emit the message to the log with [SMS] as its marker
- reply with "Repeated to n targets" where n is the number of targets
- any errors should be logged as they are encountered, eg, it might take a while to discover that a send failed.
- loop over targets:
    if element contains '@':
        if mailEndpointUrl is non-null:
            HTTP POST to mailEndpointUrl with json body
    else:
		finalNumber = cleanPhone(number)
        send SMS to finalNumber with body identical to the received one

Failures:
- HTTP failures or SMS failures do not stop processing of other destinations
- minimal retry logic acceptable (not required by this specification)

# Command Processing

A value called the "prefix" initiates a command. By default, prefix is set to "Cmd".

Any incoming SMS whose body begins with prefix is parsed as a command.

Syntax:
"<prefix> <command> <data>"

Commands are case-insensitive.

Anything starting with <prefix> is NOT sent onward to the targets.

Any <prefix> with an invalid command, whether empty or simply not a command, gets the reply "invalid command"

## Replies

Command replies are always sent to origin, and the origin is not validated in any way or against any white list.

If a reply is longer than 160 characters (notably for the "list" and "log" commands), continue in another SMS

## Commands:

(1) <prefix> add <value>
- append <value> to targets if not already present (case insensitive)
- the <value> is not checked for validity. It is always added verbatim. That means you do case insensitive CHECKING, but you do NOT mess with the stored value.
- rewrite smash.json
- reply to origin: "added" or "exists"

(2) <prefix> remove <value>
- search for exact match of <value> in targets (case insensitive)
- if found remove, otherwise do nothing
- rewrite smash.json
- reply to origin: "removed" or "not found"

(3) <prefix> list
- reply to origin with:
	- "mailEndpointUrl="+mailEndpointUrl
	- "targets" then a newline, then each target on its own line

(4) <prefix> send <number> <text>
- send SMS with <text> to cleanPhone(<number>)
- reply to origin: "sent" or "failed"
- <text> may contain spaces

(5) <prefix> setmail <url>
- set mailEndpointUrl to <url> if url starts with "http"
- if <url> is "disable" then the mailEndpointUrl is set blank
- rewrite smash.json
- reply to origin: "mail endpoint set" or "invalid url"

(6) <prefix> log <value>
- if value is omitted, it is set to 20
- replies with the last <value> lines of the log file.

(7) <prefix> prefix <newPrefixValue>
- sets the <prefix> to a new value
- if newPrefixValue is empty the prefix is unchanged
- characters in newPrefixValue may be a-zA-Z0-9 or !@#$* otherwise the command is ignored

# Command Parsing Requirements

Basic parsing:
- remove leading <prefix>
- split remaining string into command and argument(s)
- for command send, split into 3 parts:
    command, number, text
- for other commands, split into 2 parts where appropriate

Whitespace:
- collapse leading/trailing whitespace
- multiple spaces inside <text> preserved. Any \n is retained.

# Persistence

After any command that changes targets or mailEndpointUrl or prefix:
- rewrite smash.json atomically

On app start:
- load smash.json if present
- if missing or corrupted, create default:
{
  "prefix": "Cmd",
  "mailEndpointUrl": null,
  "targets": [],
}

# Foreground Service Notification

The app runs as a foreground service with a persistent notification.

Notification requirements:
- Channel ID: "smash_service"
- Channel name: "smash service"
- Importance: LOW (no sound, no vibration)

Notification content:
- Title: "smash is running"
- Text: "Forwarding SMS commands and messages."
- if smash is not the default SMS app, text includes "WARNING: Not default SMS app"
- Small icon: app icon or a simple monochrome icon

Behavior:
- Tapping the notification opens a minimal activity (e.g., status screen showing prefix, mailEndpointUrl, and target in read-only form).
- Notification is always present while the service is active.
- Service must start in the foreground within the allowed time window   after creation, using this notification.

The notification does not expose sensitive data (no SMS content) and
does not permit stopping the service from the notification itself.


# Error Handling
- invalid command syntax: reply "invalid command"
- invalid url for setmail: reply "invalid url"
- unable to write smash.json: reply "persist failed"
- HTTP or SMS forward failure: no automatic retries required
- HTTP(s) failures are defined as anything other than a 2xx response.

# Non-Goals
- no encryption of smash.json
- no retries or queueing mechanisms
- no GUI configuration interface, although minimial notification as above is allowed.

# Installation

Since this app is just for me, on a phone I completely control, installation should be scripted to grant all required permissions.

For example:


adb shell pm grant com.example.smash android.permission.RECEIVE_SMS
adb shell pm grant com.example.smash android.permission.SEND_SMS
adb shell pm grant com.example.smash android.permission.READ_SMS
adb shell pm grant com.example.smash android.permission.READ_PHONE_STATE
adb shell pm grant com.example.smash android.permission.INTERNET
adb shell pm grant com.example.smash android.permission.POST_NOTIFICATIONS

... and if possible...
adb shell pm set-default-sms-app com.example.smash

I have node on this machine, and since I deeply hate .bat and .ps1 scripting, please make .js node scripts to do the install.

# Notes
- being default SMS app simplifies SMS receive/send behavior on modern Android
- consider disabling battery optimization for stability
