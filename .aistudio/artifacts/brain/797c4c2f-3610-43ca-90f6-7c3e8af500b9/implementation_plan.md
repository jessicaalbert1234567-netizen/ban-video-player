# Implementation Plan: In-App Update Bypass & Keystore Alignment

The user reported being locked out of the app because the update screen blocks access, and when attempting to install the update, Android shows "App not installed as package conflicts with an existing package". Based on the user's answers, we will add a "Later" button to allow entering the app even when an update cannot be installed, and provide a solution to match signing keys between AI Studio and GitHub.

## User Requirements & Clarifications
1. **Add Later button so user can still enter app:** Users must have the option to skip or dismiss the update screen (e.g. "পরে করুন / অ্যাপে প্রবেশ করুন") so they are never locked out of using video playback and dubbing features.
2. **Match AI Studio and GitHub signing keys:** Resolve the signature mismatch between the currently installed APK (signed with AI Studio's `debug.keystore`) and the GitHub release APK.

---

## Proposed Changes

### 1. Update `MandatoryUpdateScreen.kt`
- Add a prominent **"পরে করুন (Later / Continue to App)"** action button below the primary update/install buttons on all relevant states (`UpdateRequired`, `ReadyToInstall`, `Error`).
- Pass an `onDismissOrSkip: () -> Unit` callback to allow the user to immediately transition into `AppNavigation`.
- On the `ReadyToInstall` screen, if Android package conflict occurs or the user returns after installation fails, display clear, actionable advice on package signature matching along with the "Later / Continue to App" button.

### 2. Update `MainActivity.kt`
- Introduce a session state `var userDismissedUpdate by rememberSaveable { mutableStateOf(false) }` (or managed in `AppUpdateManager`).
- When `userDismissedUpdate == true`, bypass the update screen completely and render `AppNavigation(viewModel, updateManager)` directly.
- The user can still check or trigger the update anytime from **SettingsScreen**.

### 3. Keystore Alignment (Matching AI Studio & GitHub Signatures)
- Clarify why Android displays *"App not installed as package conflicts with an existing package"*:
  - Android OS enforces that all updates for a package must have the **exact same signing certificate**.
  - AI Studio builds debug APKs signed with `debug.keystore` (store password: `android`, alias: `androiddebugkey`, key password: `android`).
  - If GitHub releases are signed with a different key (or vice-versa), Android blocks the update.
- Provide clear instructions and configurations so GitHub release workflows use the matching keystore (`debug.keystore` / `debug.keystore.base64`), ensuring seamless 1-click in-app updates for all users.

---

## Verification Plan
1. **Compilation Check:** Run `compile_applet` to verify all Kotlin and Jetpack Compose changes compile without errors.
2. **Unit Tests:** Run `gradle :app:testDebugUnitTest` to ensure all existing test suites and navigation flows pass.
3. **Manual Flow Verification:**
   - Verify that when an update is detected, the "Later" button appears.
   - Verify that clicking "Later" immediately opens the app navigation without blocking.
   - Verify that in-app update can still be accessed and triggered from Settings.
