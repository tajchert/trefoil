# Preview screenshot tests

Compose preview screenshot tests (`@PreviewTest`) with fake fixtures. See
[docs/SCREENSHOT_TESTING.md](../../../../../../../../../docs/SCREENSHOT_TESTING.md) for commands, filtering and best practices.

Quick reference (from `android/`):

```sh
./gradlew :app:updateDebugScreenshotTestDefaultTestSuite --tests '*UsageScreenshotsKt'   # record one file
./gradlew :app:testDebugScreenshotTestDefaultTestSuite                                  # validate all
```
