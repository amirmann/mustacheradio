# Publishing to the Play Store

The repo now builds and signs a release APK **and** AAB on every `v*` tag.
The APK still goes to the GitHub Release; the AAB is uploaded to Play if
credentials are configured. Nothing changes if you don't set up the secrets
below — the workflow falls back to today's behavior.

## One-time setup

### 1. Create a release keystore (do this once, back it up forever)

```bash
keytool -genkey -v -keystore release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias mustacheradio
```

Losing this file/password means you can never update the Play Store listing
again under the same app. Store `release.jks` somewhere safe (password
manager, not in git — it's already gitignored).

### 2. Add signing secrets to GitHub

Repo → Settings → Secrets and variables → Actions → New repository secret:

| Secret | Value |
|---|---|
| `KEYSTORE_BASE64` | `base64 -w0 release.jks` output |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | `mustacheradio` (or whatever alias you used) |
| `KEY_PASSWORD` | key password (often same as store password) |

With just these four, CI starts producing a **signed** release APK attached
to GitHub Releases (previously it shipped an unsigned debug build).

For local signed builds, create `keystore.properties` in the repo root
(gitignored):

```properties
storeFile=/absolute/path/to/release.jks
storePassword=...
keyAlias=mustacheradio
keyPassword=...
```

### 3. Create the Play Console listing (manual, first time only)

1. Create an app in [Play Console](https://play.google.com/console) (package `com.mustacheradio.app`).
2. Fill in the store listing, content rating, data safety form, and privacy policy URL — required before any release.
3. Build a signed AAB locally (`./gradlew bundleRelease`) and upload it manually to the **Internal testing** track once. The Play Developer API cannot create the very first release for a new app — all later releases can go through CI.

### 4. Create a service account for CI publishing

1. In [Google Cloud Console](https://console.cloud.google.com/), create/select a project, enable the **Google Play Android Developer API**.
2. Create a service account + JSON key ([Credentials page](https://console.cloud.google.com/apis/credentials)).
3. In Play Console → **Users and permissions** → Invite the service account's email, grant it access to this app with permission to manage production/testing releases.
4. Add the JSON key contents as a GitHub secret named `PLAY_PUBLISHER_CREDENTIALS`.

Optionally add a repo **variable** (not secret) `PLAY_TRACK` set to
`internal`, `alpha`, `beta`, or `production` (defaults to `internal`).

## Releasing

Same as before — bump `versionCode`/`versionName` in
[app/build.gradle](app/build.gradle), update `CHANGELOG.md`, tag `vX.Y.Z`, push.
CI will:

1. Build + sign `MustacheRadio-vX.Y.Z.apk` and attach it to a GitHub Release.
2. If `PLAY_PUBLISHER_CREDENTIALS` is set, upload the AAB to the configured Play Store track.

Promote a release between tracks (e.g. internal → production) from the Play
Console UI, or run `./gradlew promoteArtifact --from-track internal --promote-track production` locally with the same credentials.
