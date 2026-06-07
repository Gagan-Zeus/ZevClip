# Contributing to ZevClip

Thanks for helping improve ZevClip. The project is especially useful when it is
tested on real Android phones, real Mac networks, and the awkward edge cases
that show up outside a perfect home Wi-Fi setup.

## Good Ways To Help

- Test clipboard sync on different Android phones and macOS versions.
- Report reconnect behavior on Wi-Fi, LAN, phone hotspot, and VPN setups.
- Improve Android background/autostart behavior for specific phone brands.
- Improve UI polish on Android and macOS.
- Add clear setup docs, screenshots, and demo media.
- Help with future image clipboard sync.

## Before Opening an Issue

Please include:

- Android phone model and Android version.
- macOS version and Mac model if relevant.
- ZevClip version on both Android and Mac.
- Network type: same Wi-Fi, LAN, phone hotspot, or VPN.
- What direction failed: Android to Mac, Mac to Android, notifications, or calls.
- Whether permissions are enabled: Accessibility, Notification Access, Phone.
- Screenshots or logs when possible.

For reconnect issues, mention whether either device changed networks, slept,
rebooted, or switched hotspot/Wi-Fi.

## Development Setup

### macOS App

Open `ZevClip.xcodeproj` in Xcode and run the `ZevClip` scheme.

If Xcode is configured on your machine, you can also use:

```sh
./script/build_and_run.sh
```

### Android App

Build the debug APK:

```sh
cd android
./gradlew :app:assembleDebug
```

Install on a connected Android device:

```sh
cd android
./gradlew :app:installDebug
```

Build the release APK:

```sh
cd android
./gradlew :app:assembleRelease
```

Release APKs must be signed before they can be distributed as final builds.

## Testing Checklist

Before submitting a pull request, test the parts your change touches.

For clipboard changes:

- Android copy appears on Mac.
- Mac copy appears on Android.
- Re-copying the same text later still works when expected.
- Copy loops do not bounce the same content endlessly.
- Sync still works after stopping and starting sync.

For network/discovery changes:

- Same Wi-Fi discovery works.
- Phone hotspot works.
- Reconnect works after toggling Wi-Fi or hotspot.
- Manual IP fallback still works.

For Android background changes:

- Foreground notification status is accurate.
- Services restart when sync is enabled.
- Reboot/autostart behavior is documented honestly for phones that block it.

For notification/call changes:

- Android notifications appear on Mac.
- Clearing a mirrored notification stays in sync where supported.
- Incoming calls show caller info.
- Accept, reject, silence, and end call actions still behave correctly.

## Pull Request Guidelines

- Keep changes focused. Avoid unrelated refactors in the same PR.
- Follow the existing Swift/Kotlin style in the surrounding files.
- Include a short explanation of what changed and how you tested it.
- Do not commit private signing keys, pairing tokens, screenshots with phone
  numbers, or local build artifacts.
- If a behavior depends on a phone brand or Android permission, document that
  limitation in the PR.

## Security And Privacy

ZevClip sends clipboard and notification data directly between paired devices on
the local network. Please be careful with logs and screenshots:

- Do not paste real pairing tokens into issues.
- Redact phone numbers, notification contents, and private clipboard text.
- Do not upload private release keystores or Apple signing certificates.

## Release Notes

For user-facing changes, add a short note that explains the change in plain
language. Good release notes say what got better, what users may need to do,
and any known limitations.
