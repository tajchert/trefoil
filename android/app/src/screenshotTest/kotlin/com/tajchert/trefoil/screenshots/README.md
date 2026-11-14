# Preview screenshot tests

Compose Preview Screenshot Testing through AGP test suites (suite `screenshotTest`, variant `debug`),
configured in `android/app/build.gradle.kts` under `testOptions.screenshotTests`. No Roborazzi or Paparazzi.

## Adding a test

1. Add a file here named after the area, for example `UsageScreenshots.kt`.
2. Write a top-level `@Composable` annotated with `@PreviewTest` and `@Preview`, named `<Area>_<State>`
   (for example `ThreadList_Populated_Dark`). Add a light and a dark variant where colors matter.
3. Wrap content in `TrefoilTheme(darkTheme = ...)` and pass state from `ScreenshotFixtures`.
   Fixtures are fake only: never real machines, tokens, threads or conversation content.
   Use `ScreenshotFixtures.ago(minutes)` for timestamps so relative labels stay stable.

## Commands (run from `android/`)

```sh
./gradlew :app:updateDebugScreenshotTestDefaultTestSuite   # record or refresh reference images
./gradlew :app:testDebugScreenshotTestDefaultTestSuite     # render and compare against references
```

Reference PNGs land in `android/app/src/screenshotTestDefaultDebug/reference/` and are committed.
The comparison report is at `android/app/build/reports/tests/testDebugScreenshotTestDefaultTestSuite/index.html`.
