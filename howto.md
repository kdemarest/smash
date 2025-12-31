# smash - Setup & Deployment Guide

This guide covers local development setup, and the operational steps to prepare, install, and configure the smash Android app.

---

## 0. Local Development Setup (Windows)

### 0.1 Install JDK 17+
1. Download from https://adoptium.net/ (Temurin LTS)
2. Run installer, accept defaults
3. Verify: `java -version` should show version 17 or higher

> Note: JDK 21+ shows harmless JNA warnings with Android tools. To suppress:
> `setx JAVA_TOOL_OPTIONS --enable-native-access=ALL-UNNAMED`

### 0.2 Install Android Command-Line Tools
1. Download "Command line tools only" from https://developer.android.com/studio#command-tools
1a. Scroll down until you see the "Command line tools only"
2. Create folder: `C:\Android\cmdline-tools`
3. Extract zip contents into `C:\Android\cmdline-tools\latest\`
   (so you have `C:\Android\cmdline-tools\latest\bin\sdkmanager.bat`)

### 0.3 Configure Environment Variables
From CLI run:
```
setx ANDROID_HOME C:\Android
```

Using sysdm.cpl add to PATH:
```
C:\Android\cmdline-tools\latest\bin
C:\Android\platform-tools
```

### 0.4 Install SDK Components
Open a new terminal and run:
```
sdkmanager --install "platform-tools" "platforms;android-34" "build-tools;34.0.0"
```

Accept licenses when prompted:
```
sdkmanager --licenses
```

Verify: `adb --version` should work.

### 0.5 Install Gradle 9.2.1
1. Download from https://gradle.org/releases/ (Binary-only zip, version 9.2.1+)
2. Extract to `C:\Gradle`
3. Add to PATH: `C:\Gradle\gradle-9.2.1\bin`
4. Verify: `gradle --version`

> Note: Gradle 9.x is required for JDK 25 support.

Then generate the wrapper (one-time, from project root):
```
gradle wrapper --gradle-version 9.2.1
```

### 0.6 VS Code Extensions
Install these extensions for the best development experience:
- **Gradle for Java** (`vscjava.vscode-gradle`) - Manage Gradle tasks, view dependencies, and run builds from the sidebar

### 0.7 Build & Install Commands

See the README for details, but to build you can use 

- **Ctrl+Shift+B** → Builds the debug APK (default build task)
- **F6** → Builds and installs APK to connected phone
- **Ctrl+Shift+P** → Type `Tasks: Run Task` → Press Enter → Then choose:
  - **Build Debug APK** - just builds
  - **Install APK** - builds then installs  
  - **Build & Install** - both in sequence
  - **Clean** - cleans build outputs
  - **View Logcat** - streams filtered logs


Or manually, from project root:
```
build.cmd      # builds debug APK
install.cmd    # installs to connected device
```

---

## 1. Phone Preparation

### 1.1 Android Version Requirements
- Minimum: Android 10 (API 29) recommended for foreground service requirements
- Target: Android 13+ (API 33+) for latest SMS permission handling

### 1.2 Developer Options (for sideloading)
1. Go to **Settings > About Phone**
2. Tap **Build Number** 7 times to enable Developer Options
3. Go to **Settings > Developer Options**
4. Enable **USB Debugging**

### 1.3 Network Requirements
Ensure the phone has:
- Active mobile data or Wi-Fi (for HTTP POST to Postmark)
- No VPN or firewall blocking outbound HTTPS traffic

---

## 2. App Installation

### 2.1 Connect Phone to Computer
1. Plug your Android phone into your computer using a USB cable
2. On your phone, you may see a prompt asking what to use USB for - select **File Transfer** (MTP) or just dismiss it
3. A dialog will appear asking "Allow USB debugging?" - tap **Allow** (check "Always allow from this computer" for convenience)
4. Verify connection by running in a terminal:
   ```
   adb devices
   ```
   Ken's Moto Power 5g is "ZT4226QJJ9"

   You should see your device listed (e.g., `XXXXXX device`). If it says `unauthorized`, check your phone for the authorization prompt.

### 2.2 Install via ADB

Hit F6 (a configured hotkey) to install.

or 

```bash
# From project root
install.cmd
```

### 2.3 Disable Battery Optimization (Critical for Reliability)
After installation, disable battery optimization for smash:
1. Go to **Settings > Apps > smash > Battery**
2. Select **Unrestricted** or **Don't optimize**
3. Alternatively: **Settings > Battery > Battery Optimization > All Apps > smash > Don't optimize**

> ⚠️ Without this, Android may kill the foreground service to save battery.

---

## 3. Launch and Configure the App

### 3.1 Launch smash
1. On your phone, find **smash** in the app drawer and tap to open it
2. Or from your computer: `adb shell am start -n com.smash.app/.MainActivity`

### 3.2 Set as Default SMS App
**This is mandatory for receiving SMS on Android 4.4+**

- The app will prompt you to set it as default on first launch - tap **Yes** or **Set as default**
- If you missed the prompt: **Settings > Apps > Default apps > SMS app** → select **smash**
- The notification will show "WARNING: Not default SMS app" if not set correctly

> ⚠️ While smash is the default SMS app, the phone's original SMS app will NOT receive messages. Consider this for the phone's primary use case.

---

## 4. Grant Required Permissions

The app requires these runtime permissions:

| Permission | Purpose |
|------------|---------|
| `RECEIVE_SMS` | Receive incoming SMS |
| `SEND_SMS` | Send SMS (forwards & replies) |
| `READ_SMS` | Required for default SMS app |
| `READ_PHONE_STATE` | Access phone number info |
| `INTERNET` | HTTP POST to Postmark |
| `FOREGROUND_SERVICE` | Keep service running |
| `POST_NOTIFICATIONS` | Show foreground notification (Android 13+) |

### Granting Permissions
1. Launch the app
2. Approve each permission dialog
3. Or manually: **Settings > Apps > smash > Permissions**

---

## 5. Postmark Setup

Postmark is used to forward SMS content to email addresses.

### 5.1 Create Postmark Account
1. Go to [https://postmarkapp.com](https://postmarkapp.com)
2. Sign up for an account
3. Verify your email address

### 5.2 Create a Server
1. In Postmark dashboard, click **Servers**
2. Create a new server (e.g., "smash-forwarder")
3. Note the **Server API Token** (you'll need this)

### 5.3 Verify Sender Signature
1. Go to **Sender Signatures**
2. Add and verify the email domain or address you'll send FROM
3. Complete DNS verification (DKIM, Return-Path) for domain-level verification

### 5.4 Create an HTTP Endpoint (Webhook Proxy)

Since smash sends a custom JSON format, you need a small serverless function or endpoint to translate the request to Postmark's API format.

#### Example: Cloudflare Worker / AWS Lambda / Vercel Function

Your endpoint receives:
```json
{
  "origin": "+15551234567",
  "destination_email": "user@example.com",
  "body": "SMS message content",
  "timestamp": 1703961600000
}
```

Your endpoint calls Postmark API:
```bash
POST https://api.postmarkapp.com/email
Headers:
  X-Postmark-Server-Token: <your-server-token>
  Content-Type: application/json

Body:
{
  "From": "sms-forwarder@yourdomain.com",
  "To": "user@example.com",
  "Subject": "SMS from +15551234567",
  "TextBody": "SMS message content\n\nReceived: 2024-12-30 12:00:00"
}
```

### 5.5 Configure smash with the Endpoint
Send this SMS command to the phone running smash:
```
Cmd setmail https://your-endpoint.example.com/forward
```

Reply should be: `mail endpoint set`

### 5.6 Postmark Pricing Notes
- Free tier: 100 emails/month
- Paid plans start at $15/month for 10,000 emails
- Monitor usage in Postmark dashboard

---

## 6. Initial Configuration via SMS

Once installed and running, configure smash by sending SMS commands from any phone:

### 6.1 Add Forwarding Targets
```
Cmd add +15559876543
Cmd add alerts@example.com
```

### 6.2 Verify Configuration
```
Cmd list
```

### 6.3 Test Send
```
Cmd send +15551234567 Test message from smash
```

### 6.4 Check Logs
```
Cmd log 50
```

---

## 7. Monitoring & Maintenance

### 7.1 View Logs via ADB
```bash
# View app logs
adb logcat | grep -i smash

# Pull the log file
adb shell run-as com.example.smash cat files/smash.log

# Or if accessible via external storage
adb pull /data/data/com.example.smash/files/smash.log
```

### 7.2 View/Edit Config via ADB
```bash
# View current config
adb shell run-as com.example.smash cat files/smash.json

# Note: Editing requires root or reinstalling app
```

### 7.3 Check Service Status
- Persistent notification indicates service is running
- If notification disappears, service was killed (check battery optimization)

### 7.4 Force Restart Service
```bash
adb shell am force-stop com.example.smash
adb shell am start -n com.example.smash/.MainActivity
```

---

## 8. Troubleshooting

### SMS Not Being Received
- [ ] Is smash set as default SMS app?
- [ ] Are SMS permissions granted?
- [ ] Is the foreground service running (notification visible)?
- [ ] Check battery optimization settings

### HTTP Forwarding Failing
- [ ] Is `mailEndpointUrl` set? (`Cmd list` to verify)
- [ ] Is the phone connected to internet?
- [ ] Check `Cmd log` for error details
- [ ] Test the endpoint URL directly with curl

### Service Keeps Stopping
- [ ] Disable battery optimization for smash
- [ ] On MIUI/Samsung/Huawei: Check manufacturer-specific battery settings
- [ ] Enable "Auto-start" in app settings (vendor-specific)

### "persist failed" Errors
- [ ] Check available storage space
- [ ] App may need reinstall if internal storage is corrupted

---

## 9. Security Considerations

### 9.1 Physical Security
- The phone running smash should be physically secured
- Anyone with SMS access to the phone can send commands
- Consider using a non-obvious prefix (`Cmd prefix X7q!`)

### 9.2 Network Security
- Use HTTPS for the mail endpoint
- Consider IP whitelisting on your endpoint if possible
- Monitor Postmark for unusual activity

### 9.3 No Authentication
- smash has no authentication mechanism
- Anyone who knows the prefix can send commands
- Mitigate by using an obscure prefix

---

## 10. Uninstallation

### 10.1 Reset Default SMS App First
1. Go to **Settings > Apps > Default apps > SMS app**
2. Select another SMS app (e.g., Messages)

### 10.2 Uninstall
```bash
adb uninstall com.example.smash
```
Or via **Settings > Apps > smash > Uninstall**

> ⚠️ If you uninstall while smash is the default SMS app, incoming SMS may fail until another default is set.

---

## Appendix: Quick Reference Commands

| Command | Description |
|---------|-------------|
| `Cmd add <target>` | Add phone/email to targets |
| `Cmd remove <target>` | Remove from targets |
| `Cmd list` | Show config |
| `Cmd send <number> <text>` | Send SMS |
| `Cmd setmail <url>` | Set email endpoint |
| `Cmd setmail disable` | Disable email forwarding |
| `Cmd log [n]` | Show last n log lines |
| `Cmd prefix <new>` | Change command prefix |
