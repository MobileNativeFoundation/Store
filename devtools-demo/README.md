# devtools-demo

An unpublished reference-app seed for the Store6 devtools experience. It runs the same in-process
inspector on desktop, Android, and iOS. The inspector uses no host tooling or transport: no sockets,
desktop host, or web panel.

## Desktop

```shell
./gradlew :devtools-demo:run
```

## Android

Connect an emulator or device, choose its serial explicitly, then build, install, and launch:

```shell
adb devices
./gradlew :devtools-demo:assembleDebug
ANDROID_SERIAL=<serial> ./gradlew :devtools-demo:installDebug
adb -s <serial> shell am start -n org.mobilenativefoundation.store6.devtoolsdemo/.MainActivity
```

Replace `<serial>` with one `device` entry from `adb devices`. Explicit selection avoids installing
or launching against the wrong connected device.

## iOS

The Kotlin framework acceptance command is:

```shell
./gradlew :devtools-demo:linkDebugFrameworkIosSimulatorArm64
```

The committed Xcode host lives under `iosApp/`. Open `iosApp/iosApp.xcodeproj`, select an iOS
simulator, and run scheme `iosApp`.

To recreate the shell, use these exact settings:

1. In `devtools-demo/iosApp`, create an iOS App project named `iosApp` with scheme `iosApp`.
2. Set bundle identifier `org.mobilenativefoundation.store6.devtoolsdemo.iosApp` and deployment
   target iOS 15.
3. Keep the committed `iosApp/Info.plist`, including
   `CADisableMinimumFrameDurationOnPhone` as a Boolean `YES`.
4. In the target build settings for both Debug and Release, set **Generate Info.plist File** to
   `No` and **Info.plist File** to `Info.plist`.
5. Add this Run Script build phase **before Compile Sources**:

   ```shell
   cd "$SRCROOT/../.." && ./gradlew :devtools-demo:embedAndSignAppleFrameworkForXcode
   ```

6. Add framework search path
   `$(SRCROOT)/../build/xcode-frameworks/$(CONFIGURATION)/$(SDK_NAME)`.
7. Keep the existing `iosApp/iosApp/iOSApp.swift` and
   `iosApp/iosApp/ContentView.swift` sources.

From the repository root, run the exact Xcode acceptance command:

```shell
cd devtools-demo
xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -sdk iphonesimulator build
```

## Android and iOS manual checklist

Run all six steps on **both** Android and iOS:

1. Launch the app and open the inspector with the FAB.
2. Confirm the key appears as `FRESH` with its age ticking.
3. Set latency to 3000 ms, tap **Invalidate**, and confirm `STALE` then `FETCHING` plus refreshed
   content.
4. Enable failure, tap **Invalidate**, and confirm `ERROR` plus `fetch_failed`.
5. Tap **Clear** and confirm `CLEARED`.
6. Confirm logcat or the Xcode console contains Store6 v0 logger lines.
