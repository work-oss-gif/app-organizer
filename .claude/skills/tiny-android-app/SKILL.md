---
name: tiny-android-app
description: Build small, dependency-free Android apps (launchers, organizers, utilities) as plain Java in a single Activity with the UI created in code, and get an installable APK from GitHub Actions without needing the Android SDK locally. Use this skill whenever the user wants to create, extend, or fix a simple Android app, an APK, a home-screen launcher, app shortcuts, or says "make me an app for my phone", even if they never say "Android Studio", "Java" or "Gradle". Also use it when working in a repo that has app/src/main/java, a minimal build.gradle and a Build APK workflow.
---

# Tiny Android app (plain Java, one Activity, APK built by GitHub Actions)

This is the approach used for the App Organizer app. It favors a very small
project that a beginner can read top to bottom and that builds anywhere, because
the build runs on GitHub's servers rather than on the user's machine.

## Why this shape

- **No AndroidX, no XML layouts, no libraries.** Fewer files and no version
  conflicts. `gradle.properties` sets `android.useAndroidX=false`. Everything
  needed is in the framework (`android.app.Activity`, `android.widget.*`).
- **UI built in Java code** (`LinearLayout`, `GridLayout`, `TextView`, ...).
  One file shows the whole screen, so explaining it to a beginner is easy.
  Convert `dp` to pixels with a small helper: `(int)(v * density)`.
- **One `MainActivity.java`.** Split into more files only when it passes
  roughly 400 lines.
- **Rebuild the screen in `onResume()`** so it is always current (for example
  after the user installs or removes an app).

## Project layout (copy this)

```
build.gradle              plugins { id 'com.android.application' version '8.5.2' apply false }
settings.gradle           repositories google()/mavenCentral(), include ':app'
gradle.properties         android.useAndroidX=false, org.gradle.jvmargs=-Xmx2g
app/build.gradle          namespace + applicationId, compileSdk 34, minSdk 24, targetSdk 34, Java 1.8
app/src/main/AndroidManifest.xml
app/src/main/java/<package path>/MainActivity.java
.github/workflows/build.yml
.gitignore                build/, .gradle/, local.properties
```

No Gradle wrapper is committed; the workflow installs Gradle 8.7 itself.

## Building the APK (no SDK needed locally)

The cloud sandbox usually has no Android SDK, so do not try to compile there.
Push to GitHub and let the workflow build. The workflow (runs on every push):

1. `actions/checkout@v4`
2. `actions/setup-java@v4` (temurin, Java 17)
3. `gradle/actions/setup-gradle@v4` with `gradle-version: 8.7`
4. `gradle assembleDebug`
5. `actions/upload-artifact@v4` uploading `app/build/outputs/apk/debug/*.apk`

After pushing, check the run with the GitHub Actions tools: list workflow runs
for the branch, confirm `conclusion: success`, then list the run's artifacts.
A green build means the code compiles; it does not prove the feature works on a
phone, so say that plainly.

Tell the user how to get the APK: open the run page on github.com while logged
in, scroll to **Artifacts**, download the zip, unzip, install `app-debug.apk`.
If the sandbox network blocks the artifact host, do not retry; give those steps.
If they report "nothing changed", suspect an old APK or a signing mismatch (debug
keys differ between CI runs, so uninstall the old app first) before suspecting code.

## Android details that cause bugs

- **Package visibility (Android 11+):** to list other apps you need a
  `<queries>` block in the manifest (a LAUNCHER intent, plus a browsable https
  VIEW intent to detect browsers). Without it the list comes back nearly empty.
- **Launcher role:** adding `CATEGORY_HOME` + `CATEGORY_DEFAULT` to the main
  intent filter lets the app be chosen as the home screen.
- **Listing and launching apps:** `queryIntentActivities` with
  `ACTION_MAIN` + `CATEGORY_LAUNCHER`; launch with `getLaunchIntentForPackage`;
  app settings via `ACTION_APPLICATION_DETAILS_SETTINGS` with `package:<pkg>`.
- **Categories:** group by `ApplicationInfo.category` and `FLAG_IS_GAME`, with an
  "Other" bucket, because many apps declare no category.
- **Home-screen shortcuts:**
  - API 26+: `ShortcutManager.requestPinShortcut` with a `ShortcutInfo`
    (stable id, short label, `Icon.createWithBitmap`, an intent that has an
    action). Check `isRequestPinShortcutSupported()` and show a toast if false
    (for example when this app is itself the default launcher).
  - Below 26: broadcast `com.android.launcher.action.INSTALL_SHORTCUT` and
    declare the `INSTALL_SHORTCUT` permission. Many launchers ignore it.
  - Apps can never create real folders on the home screen. Offer instead a
    shortcut that opens the app filtered to a category, or pinning many
    shortcuts the user drags into a folder. Say this up front, it saves time.
  - Shortcut icons must be bitmaps: draw the Drawable onto a Canvas, and restore
    its original bounds afterwards because the on-screen views share it.
- With `launchMode="singleTask"`, handle new intents in `onNewIntent` (call
  `setIntent`) or a second launch will silently keep the old state.

## Working with the user

- Many users are beginners: explain each change in plain words, and what they
  will see on their phone, not only what files changed.
- Make new features **visible** (a real button on screen). Hiding a feature
  behind long-press made the user think nothing had changed.
- Confirm ambiguous requests ("shortcut" vs "folder") with one short question
  before building; the wrong guess costs a full build-and-install cycle.
- Commit to the feature branch given in the task and push; never open a pull
  request unless asked.
