# Android releases

Version values live in `android/app/build.gradle.kts`. The first release is
`0.1.0` (`versionCode = 1`), application ID `com.tajchert.trefoil`, minimum Android 10.
Increment both version values for subsequent published APKs.

## Signing

The release signing setup follows Flocket. All four values are required; without
them the release task produces an unsigned APK, suitable only for build validation.

Create an ignored `android/keystore.properties`:

```properties
storeFile=/absolute/path/to/trefoil-release.jks
storePassword=<store password>
keyAlias=trefoil
keyPassword=<key password>
```

Values are read from this file first, then matching environment variables or
Gradle properties: `TREFOIL_KEYSTORE_FILE`, `TREFOIL_KEYSTORE_PASSWORD`,
`TREFOIL_KEY_ALIAS`, `TREFOIL_KEY_PASSWORD`.

Signing material must never be committed or attached to a GitHub release.
Back up the keystore and passwords in your password manager: future updates must
use the same key. Local files created for the first release use owner-only permissions.

Certificate subject: `CN=Trefoil Release, O=Tajchert, C=PL`.
Verified certificate SHA-256:

```text
a058e08d99619a222472500a2b7661b59e47e6e36b656dc3990e58e9dc570a02
```

The first keystore is RSA-4096 in JKS format, alias `trefoil`, with separate
store and key passwords. The local keystore is outside the repository at
`~/keystores/trefoil-release.jks`. The generated private password-manager record
is `~/keystores/trefoil-release-credentials.txt`; it is not part of this repository.

## Verify, build and publish

```sh
cd android
./gradlew :core:protocol:test :core:session:test :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
"$ANDROID_HOME/build-tools/36.1.0/apksigner" verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

Install and smoke-test the signed APK on a test device before publishing. A debug
installation uses a different certificate; remove it from the test emulator before
installing the release APK. This clears only that installation's local app data.

Publish a versioned APK and its SHA-256 checksum from the verified commit:

```sh
git push origin main
git tag v0.1.0
git push origin v0.1.0
gh release create v0.1.0 trefoil-0.1.0.apk trefoil-0.1.0.apk.sha256 --repo tajchert/trefoil --verify-tag --title 'Trefoil 0.1.0' --notes-file release-notes.md
```

Adapt the version and asset paths for future releases. Only upload the public APK
and checksum; signing credentials stay outside Git.
