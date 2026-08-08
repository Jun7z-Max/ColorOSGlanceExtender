---
name: coloros-glance-extender
description: Maintain, debug, build, and release the ColorOS Glance Extender Android LSPosed module. Use for changes involving ColorOS negative-screen cards, UMS/AppWidget injection, the module management UI, dynamic third-party card discovery, dark/light theme behavior, device validation, or GitHub APK releases.
---

# ColorOS Glance Extender

Use this skill when working in this repository or a fork of it. Treat the module as a compatibility layer around private ColorOS/UMS implementations: preserve fail-closed behavior and verify on the target device before claiming compatibility.

## Repository Map

- `app/src/main/java/io/github/colorosglance/extender/GlanceObserver.java`: LSPosed entry point, UMS/assistant hooks, synthetic widget configuration, refresh broadcast, and dynamic catalog publication.
- `app/src/main/java/io/github/colorosglance/extender/CardCatalog.java`: reads the resident catalog and installed `AppWidgetProviderInfo` objects, resolves labels/icons, filters disabled cards, and groups third-party cards by package.
- `app/src/main/java/io/github/colorosglance/extender/ModuleInfoActivity.java`: management UI; keep the single third-party list, collapsed app groups, per-card switches, inset handling, hidden scroll bar, and persisted theme toggle coherent.
- `app/src/main/java/io/github/colorosglance/extender/CardControlProvider.java` and `ModuleBridge.java`: the app/host process bridge for disabled IDs, catalog data, and refresh requests.
- `.github/package-apk.sh`: canonical build, metadata, signature, and checksum entry point.
- `.github/workflows/release.yml`: tag-triggered signed GitHub Release workflow.

## Change Rules

1. **Keep discovery dynamic.** Resolve app labels, icons, widget providers, and component identities from the installed package manager/UMS data. Do not add app-name, package, model, device, or ROM-version allowlists for ordinary behavior.
2. **Separate injected cards from official cards.** The module may inspect both UMS config sources to preserve metadata and final-list identity matching, but the management catalog should publish and display only synthetic third-party entries. Never use the UI as the source of truth for host injection.
3. **Fail closed on unknown ROM structures.** If target classes, methods, fields, signatures, or process guards do not match, leave the host configuration unchanged and log the reason. Do not guess a new hook from a single crash.
4. **Preserve stable identity.** Prefer the marker component, package/component name, and type fallbacks already used by `GlanceObserver`; check collisions before creating synthetic configurations.
5. **Keep user controls local.** Read and write disabled card IDs through `CardControlProvider`; do not write UMS databases or mutate unrelated app data.
6. **Maintain UI accessibility and layout.** Account for status/navigation insets, keep text contrast valid in both themes, use content descriptions for the sun/moon button, and disable font padding when visual centering matters.

## UI Workflow

When changing `ModuleInfoActivity.java`:

1. Load `dark_mode` before creating the window and persist it in the module's `ui_preferences` file.
2. Set status/navigation bar colors and light-system-bar flags from the same palette as the content.
3. Use the existing rounded surface hierarchy; keep app groups collapsed by default and cards independently switchable.
4. Animate theme changes without rotating the icon: slide the old icon horizontally, fade the whole palette between light and dark, then slide the new icon in from the opposite side.
5. Keep the catalog reload asynchronous and request UMS refresh on resume and after a card switch.

## Build and Device Validation

Use the narrowest validation that answers the change, then run the release wrapper for a releasable artifact.

```bash
./gradlew clean lintDebug assembleDebug
```

For a signed artifact, load local release credentials without committing them:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
export ANDROID_SDK_ROOT="$ANDROID_HOME"
. .secrets/release.env
export CGE_EXPECTED_TAG=15-0.1.14
./.github/package-apk.sh release
```

With a connected rooted device, verify installation and the two host scopes:

```bash
adb devices -l
adb install -r dist/ColorOS-Negative-Screen-Extension-v0.1.14.apk
adb shell am force-stop com.oplus.pantanal.ums
adb shell am force-stop com.coloros.assistantscreen
adb shell am start -n io.github.colorosglance.extender/.ModuleInfoActivity
```

Check the UI and relevant module logs. If a test temporarily disables a card, restore the disabled-ID set to its original value before handing off. Do not claim X9 or another ROM is supported without device properties, target-package versions, LSPosed scope state, and filtered module logs.

## GitHub Release

Keep the Gradle `versionCode` and `versionName` aligned with the LSPosed tag format `<versionCode>-<versionName>`. After the working tree passes validation:

```bash
git add README.md app .github skills
git commit -m "Describe the focused change"
git push origin main
git tag -a 15-0.1.14 -m "Release 0.1.14"
git push origin 15-0.1.14
```

The tag starts `.github/workflows/release.yml`, which builds the signed APK and uploads the APK, checksum, and `SHA256SUMS`. Never stage `.secrets/`, keystores, local preference dumps, screenshots, or generated `dist/` files unless the release process explicitly requires them.
