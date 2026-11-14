# Screenshot testing

Trefoil renders Compose previews to PNG with Android's
[Compose Preview Screenshot Testing](https://developer.android.com/studio/preview/compose-screenshot-testing-with-testsuites),
run through AGP test suites. Rendering uses Layoutlib on the JVM, so no device or emulator is needed.

## Where things live

| What | Path |
|---|---|
| Suite config | `android/app/build.gradle.kts` → `testOptions.screenshotTests` (suite `screenshotTest`, variant `debug`) |
| Experimental flags | `android/gradle.properties`: `android.experimental.enableScreenshotTest`, `android.experimental.testSuiteSupport` |
| Preview tests and fixtures | `android/app/src/screenshotTest/kotlin/com/tajchert/trefoil/screenshots/` |
| Reference PNGs (committed) | `android/app/src/screenshotTestDefaultDebug/reference/com/tajchert/trefoil/screenshots/<File>Kt/` |
| Comparison report | `android/app/build/reports/tests/testDebugScreenshotTestDefaultTestSuite/index.html` |
| Widget renders (not committed) | `android/app/build/widget-shots/` |

AGP 9.5 replaces the `com.android.compose.screenshot` plugin with the test-suite setup above, so the plugin is not applied.

## Commands

Run from `android/`. Without `local.properties`, export `ANDROID_HOME=/opt/homebrew/share/android-commandlinetools`.

```sh
# Record or refresh reference images
./gradlew :app:updateDebugScreenshotTestDefaultTestSuite

# Render and compare against references (this is the check)
./gradlew :app:testDebugScreenshotTestDefaultTestSuite
```

### Run only part of it

Both tasks accept `--tests`. Preview files compile to `<File>Kt` classes, and each preview function is a test method.

```sh
# One file
./gradlew :app:testDebugScreenshotTestDefaultTestSuite --tests '*UsageScreenshotsKt'

# One preview
./gradlew :app:testDebugScreenshotTestDefaultTestSuite --tests '*ThreadList_Populated_Dark'

# Every preview whose name matches
./gradlew :app:updateDebugScreenshotTestDefaultTestSuite --tests '*Conversation_*'
```

Add `--rerun` to force a run when Gradle reports the task up to date.

### Widgets

Glance widgets can't be rendered by Layoutlib. A separate Robolectric test renders them, and it is skipped unless `WIDGET_SHOTS=1` is set:

```sh
WIDGET_SHOTS=1 ./gradlew :app:testDebugUnitTest --tests '*WidgetScreenshotTest' --rerun
```

Known gaps: the background is flat, and widgets that use a Glance `LazyColumn` (usage table, full limit bars) render without their rows.

## Adding a preview test

1. Use the file for the area, or add one named after it (`UsageScreenshots.kt`). Put area-specific fake data in a matching `*Fixtures.kt`.
2. Write a top-level function with `@PreviewTest` and `@Preview`, named `<Area>_<State>[_Light|_Dark]`, for example `DownloadFileCard_Downloading_Light`.
3. Wrap it in `TrefoilTheme(darkTheme = …)` and pass fixture state.
4. Run the update task for just that file, open the new PNG, then run the validate task.
5. Commit the Kotlin and the PNGs together.

```kotlin
@PreviewTest
@Preview(showBackground = true, widthDp = 400, heightDp = 800, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
fun Usage_Limits_Dark() { UsageShot(0, dark = true) }  // helper in UsageScreenshots.kt wraps TrefoilTheme + fixtures
```

## Best practices

- **Fake data only.** Never real machines, hosts, tokens, threads or conversation content. Reuse the helpers in `ScreenshotFixtures.kt`.
- **Keep renders deterministic.** Anything tied to the wall clock shifts pixels between runs and fails the comparison:
  - use `ScreenshotFixtures.ago(minutes)` for relative labels;
  - use fixed dates for charts;
  - leave out elapsed timers, pace markers and other live values.
- **Render stateless composables.** UI composables take state and callbacks, never the ViewModel. When something is buried in a loader or popup, extract a small stateless composable instead of faking it. `LoadedImageContent`, `ShellMenuItems` and `MachineMenuItems` were extracted this way.
- **Some things don't render here:**
  - popups such as `DropdownMenu` (render the extracted menu items instead);
  - WebViews;
  - Glance widgets;
  - state that appears after a delay (for example the reconnecting banner, which waits 1.5 s);
  - state held in `rememberSaveable` that a preview can't set.
- **Don't set `name =` on `@Preview`.** The name then appears twice in the PNG filename.
- **Light and dark** are worth it for full screens and anything color-coded. Small components can be light only.
- **Review image diffs like code.** When a reference changes, open the old and new PNG, or the HTML report, and confirm the change is intended before committing.
- **Re-record after intended UI changes,** narrowly: update just the affected file or preview, not the whole suite, so unrelated references stay untouched.
- **Remove stale references.** Renaming or deleting a preview leaves its old PNG behind. Delete it in the same commit.
- `:app:tasks --all` fails because AGP can't create the release preview task. Run the tasks above directly.
