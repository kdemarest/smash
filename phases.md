This set of implementation phases refers to spec.md

# Phase 1: Foundation

Project setup - Create Android project with Kotlin, set up Gradle, define package name, configure min/target SDK
AndroidManifest.xml - Declare all permissions, SMS receiver, service, and activities
Data model & persistence - Create SmashConfig data class and JSON read/write for smash.json

# Phase 2: Core Service

Foreground service - Implement SmashService with notification channel, persistent notification, and lifecycle
Logging utility - Create SmashLogger to write timestamped entries to smash.log
App startup - Load config on start, create defaults if missing, log startup state

# Phase 3: SMS Handling

SMS receiver - BroadcastReceiver for SMS_RECEIVED intent, extract sender and body
Sequential processing - Queue/synchronize incoming SMS to process one at a time
SMS sending utility - Helper function to send SMS with SmsManager, handle 160-char splitting for replies

# Phase 4: Command Processing

Command parser - Parse prefix, command, and arguments; handle whitespace rules
Command dispatcher - Route to handlers based on command name (case-insensitive)
Implement commands in order of dependency:
list (read-only, good for testing)
add / remove (modifies targets)
prefix (modifies prefix)
setmail (modifies mailEndpointUrl)
send (requires SMS sending)
log (requires logging to be working)
Invalid command handling - Reply "invalid command" for unrecognized commands

# Phase 5: Forwarding

Phone number cleaning - Implement cleanPhone() utility
SMS forwarding - Forward to phone targets
HTTP client - Set up OkHttp or similar for HTTP POST
Email forwarding - POST to mailEndpointUrl with JSON payload

# Phase 6: UI & Polish

Status activity - Minimal activity showing prefix, mailEndpointUrl, targets (read-only)
Default SMS app prompt - Check and prompt user on launch
Notification tap action - Open status activity from notification

# Phase 7: Testing & Hardening

Unit tests - Config parsing, command parsing, cleanPhone()
Integration tests - End-to-end command flows
Edge cases - Corrupted config, HTTP timeouts, SMS failures, multi-part SMS replies

Rationale: This order ensures each piece builds on tested foundations—you can't test commands without the service running, can't test forwarding without SMS reception working, etc. The logging utility early on helps debug everything that follows.