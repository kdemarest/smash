# smash

Android SMS forwarding app.

## Build (Command Line)

```bash
build.cmd            # Windows
./gradlew assembleDebug   # Cross-platform
```

## Build (VS Code)

- **Ctrl+Shift+B** → Builds the debug APK (default build task)
- **F6** → Builds and installs APK to connected phone
- **Ctrl+Shift+P** → "Tasks: Run Task" → Choose:
  - **Build Debug APK** - just builds
  - **Install APK** - builds then installs  
  - **Build & Install** - both in sequence
  - **Clean** - cleans build outputs
  - **View Logcat** - streams filtered logs

## Install

```bash
install.cmd          # Windows
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Project Structure

```
app/src/main/
├── java/com/smash/app/
│   ├── SmashConfig.kt       - Data model for smash.json
│   ├── ConfigManager.kt     - JSON persistence
│   ├── PhoneUtils.kt        - Phone number utilities
│   ├── MainActivity.kt      - Launcher activity
│   ├── StatusActivity.kt    - Status display (from notification)
│   ├── SmashService.kt      - Foreground service
│   ├── SmsReceiver.kt       - SMS broadcast receiver
│   ├── MmsReceiver.kt       - MMS receiver (required for default SMS app)
│   ├── HeadlessSmsSendService.kt - Required for default SMS app
│   └── ComposeSmsActivity.kt - Required for default SMS app
├── res/
│   ├── layout/              - Activity layouts
│   ├── values/              - Strings, colors, themes
│   ├── drawable/            - Icons
│   └── mipmap-*/            - Launcher icons
└── AndroidManifest.xml      - App manifest with permissions
```

## See Also

- [spec.md](spec.md) - Full specification
- [howto.md](howto.md) - Setup & deployment guide
- [phases.md](phases.md) - Implementation phases
