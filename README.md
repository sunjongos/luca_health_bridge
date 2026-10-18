# Luca Health Bridge

Privacy-first Android bridge that turns Health Connect data into authenticated, automated health briefings.

`Galaxy Watch / Galaxy Ring → Samsung Health → Health Connect → Luca Health Bridge → authenticated HTTPS receiver → OpenClaw briefing`

## What we achieved

Luca Health Bridge is no longer a manual JSON-export prototype. Version **0.6.0** has completed an end-to-end real-device proof:

- read-only collection from Health Connect;
- source-aware deduplication and paginated reads;
- background collection and upload with WorkManager;
- Android Keystore-backed encrypted credential storage;
- authenticated HTTPS upload with retry/backoff;
- atomic server-side snapshots with restrictive file permissions;
- stale-data, malformed-value, and timestamp-regression safeguards;
- first-upload acknowledgement and visible sync status;
- successful production receiver response (`202 Accepted`) after credential rotation;
- rejected old credential (`401 Unauthorized`) after rotation.

This is a meaningful engineering milestone: a wearable-to-analysis closed loop running on a real phone and a real server.

## Current release status

### Ready

- **Personal/internal production use:** yes, subject to the final reboot-without-opening-the-app observation.
- **Closed/internal Google Play testing:** technically close; an Android App Bundle can be built and the app targets API 36.

### Not yet ready for unrestricted public Google Play production

Public distribution needs more than a working APK. Before production submission, complete the following:

1. Publish a public privacy-policy URL and link the identical policy from both the Play listing and the app's Health Connect permission flow.
2. Complete Play Console **Data safety** and **Health apps / Health Connect** declarations, justifying every requested data type.
3. Replace the current single-owner provisioning model with per-user authentication, revocation, deletion, retention, and support flows if the app will serve anyone beyond the owner.
4. Add an in-app disclosure explaining cloud upload before permission/activation, plus controls to disconnect, delete cloud data, and rotate/revoke credentials.
5. Create a Play App Signing release key, upload an AAB to internal testing, and pass Play pre-launch reports.
6. Add automated unit/instrumentation coverage and test Android 9 through Android 16, especially permission denial/revocation, reboot recovery, offline retry, and OEM background restrictions.
7. Prepare store assets, content rating, support contact, privacy contact, and a documented incident-response/data-deletion process.
8. Obtain a policy/legal review appropriate to the actual countries and users served. The app is wellness monitoring, not a diagnostic or emergency system.

Official references:

- [Publish a Health Connect app on Google Play](https://developer.android.com/health-and-fitness/health-connect/publish)
- [Health Connect permissions policy](https://support.google.com/googleplay/android-developer/answer/16558241)
- [Google Play target API requirements](https://support.google.com/googleplay/android-developer/answer/11926878)

The repository deliberately does **not** contain live credentials, private health records, signing keys, or service-account files.

## Health data scope

The app requests **read-only** access to steps, exercise sessions, heart rate, sleep, oxygen saturation, weight, body fat, and VO2 max. It also requests background Health Connect reads so scheduled briefings can continue when the UI is closed. It does not request Health Connect write or delete permissions.

## Security model

- Upload credentials are provisioned after installation and are never compiled into the APK.
- Credentials are stored using Android Keystore-backed encrypted preferences.
- Uploads use HTTPS and bearer authentication.
- The receiver validates schema, timestamps, bounds, body size, and finite numeric values.
- Snapshots are written atomically and stored with mode `0600`; report directories use restrictive permissions.
- Credential rotation invalidates the previous credential.
- Logs must never contain authorization headers, payload bodies, or health records.

For a public multi-user service, bearer-token provisioning must be replaced or extended with account-bound credentials, lifecycle management, abuse controls, audit trails, and user-directed deletion.

## Build

Requirements: JDK 17 and Android SDK 36.

```bash
./gradlew clean lintDebug testDebugUnitTest assembleDebug bundleRelease \
  -PlucaHealthEndpoint=https://your-health-receiver.example/v1/ingest
```

The repository default is the non-routable placeholder `https://example.invalid/v1/ingest`. Supply the real receiver URL only through a local Gradle property or CI secret. Do not commit a production hostname or credential.

Expected artifacts:

- debug APK: `app/build/outputs/apk/debug/app-debug.apk`
- release AAB: `app/build/outputs/bundle/release/app-release.aab`

The release AAB is unsigned unless a secure signing configuration is supplied outside the repository. Prefer Play App Signing and never commit keystores or passwords.

## Device setup

1. Enable Health Connect sharing in Samsung Health.
2. Install the app on Android 9 or newer; recent Android versions are preferred.
3. Open Luca Health Bridge and connect the requested read permissions.
4. Enter the separately delivered one-time linking credential.
5. Activate automatic linking and confirm the first successful upload.
6. Set the app battery mode to **Unrestricted** on Samsung devices.
7. For the reboot acceptance test, restart the phone, unlock it once, do not open the app, and confirm the next scheduled upload at the receiver.

Manual JSON sharing remains available only as a diagnostic fallback.

## Data quality behavior

- Samsung Health is preferred for each metric.
- Another Health Connect writer is used only when Samsung Health has no record for that metric and window.
- Overlapping Samsung Health exercise intervals are merged before total duration is calculated.
- Heart-rate output is labeled as exported samples rather than resting heart rate because source sampling can be exercise-heavy.
- Wearable measurements are trend-screening inputs, not medical diagnoses.

## Repository policy

Do not commit upload/linking credentials, health JSON or reports, signing keys or passwords, Play service-account credentials, production server secrets, or personally identifying screenshots/logs. If a secret is exposed, rotate it first, verify the old value is rejected, and only then clean history.

## Version history

- **0.6.0** — hardened validation and retry classification; production credential rotation and end-to-end verification.
- **0.5.0** — authenticated background upload, encrypted credential storage, pagination, first-upload acknowledgement, and visible sync state.
- **0.3.0** — exercise interval merging and more precise heart-rate labeling.
- **0.2.0** — source preference and duplicate-source avoidance.

## Disclaimer

Luca Health Bridge supports personal wellness monitoring and executive health briefings. It is not a medical device, diagnostic system, emergency service, or substitute for professional medical care.
