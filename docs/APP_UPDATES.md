# TriDrone in-app updates

Implemented: **Check for App Updates** opens a feed URL editor and fetches an HTTPS JSON manifest. The app compares versionCode, downloads APK, verifies SHA-256, then opens Android's standard installer. It does not silently install. The APK must be signed with the same certificate as the installed version.

## One-time operator setup

1. Generate and securely back up a persistent Android keystore. Never commit it.
2. Add GitHub Actions secrets: TRIDRONE_KEYSTORE_BASE64 (base64 of keystore bytes), TRIDRONE_STORE_PASSWORD, TRIDRONE_KEY_ALIAS, TRIDRONE_KEY_PASSWORD. The build uses these when provided. Without them GitHub runners produce differently signed debug APKs.
3. Publish each successful signed APK to a **controlled HTTPS download endpoint** reachable from the phone. Because this is a private GitHub repo, ordinary Actions artifact links are not suitable as an anonymous updater feed; they require GitHub authorization and expire. Never embed a personal access token in the APK.
4. Publish an HTTPS latest.json manifest:
```json
{
  "versionCode": 1234,
  "apkUrl": "https://your-download-host.example/tridrone/build-1234.apk",
  "sha256": "64-character lowercase hex SHA-256 of the APK"
}
```
5. On the phone, open **Check for App Updates**, enter the manifest URL, and tap **Check now**. Android may ask to allow installation from TriDrone.
6. Keep the package name com.massisolutions.tridrone unchanged. If the currently installed APK uses a different signing certificate, uninstall/reinstall is required once; export important data first.

## Limitations / pending
- This commit **does not create or provision a hosting endpoint** or upload signed APKs to it. A controlled distribution target and credentials are needed to complete automatic publication.
- The feed is trusted by its configured HTTPS origin and SHA-256 manifest; no detached publisher signature or pinned TLS key is implemented. Only configure a trusted endpoint.
- GitHub Actions run number is mapped to versionCode 1000 + run_number; local builds remain versionCode 3.
- Release-channel testing is required to verify signing continuity and Android package installer behavior.

## Automated distribution (configured in workflow)

A successful **signed** GitHub Actions build now attempts to publish `tridrone.apk` as the `dev-latest` release asset in a separate public distribution repository, then writes `latest.json` there. No source files are published.

**One-time actions required in GitHub UI:**

1. Create a **public** GitHub repository named `TriDrone-Updates` under `fieldlogic-lab` (initialize it with a README so the default branch is `main`). Anyone with the link can download the development APK; do not use this method for a confidential APK.
2. Generate a persistent signing key locally with `keytool -genkeypair -v -keystore tridrone-development.jks -alias tridrone -keyalg RSA -keysize 3072 -validity 3650`. Store the keystore and passwords in a secure backup. Do **not** commit or upload the key to the public repo.
3. In the **private source repository**, open Settings → Secrets and variables → Actions. Add secrets `TRIDRONE_KEYSTORE_BASE64`, `TRIDRONE_STORE_PASSWORD`, `TRIDRONE_KEY_ALIAS`, `TRIDRONE_KEY_PASSWORD`. Encode the keystore with `base64 -w0 tridrone-development.jks` on Linux (on Windows use `[Convert]::ToBase64String([IO.File]::ReadAllBytes("tridrone-development.jks"))`).
4. Create a fine-grained GitHub personal access token scoped **only to the public distribution repository** with Contents read/write permission; save it as the private source repository Actions secret `TRIDRONE_DISTRIBUTION_TOKEN`. The token is used by Actions only and is never shipped in the APK.
5. Add Actions **repository variable** `TRIDRONE_DISTRIBUTION_REPO` = `fieldlogic-lab/TriDrone-Updates`.
6. Run the Build TriDrone Android APK workflow manually once or push an app change. Check that the publishing step succeeds. Set the app's update feed to `https://raw.githubusercontent.com/fieldlogic-lab/TriDrone-Updates/main/latest.json`.
7. The first transition from older randomly signed debug APKs may require uninstalling TriDrone once. Export existing survey data before uninstalling. Thereafter the persistent key permits Android in-place updates.

**Important:** Without these user-owned secrets and public distribution repository, the workflow still builds an artifact but cannot configure publishing. The updater is not fully operational until an end-to-end test succeeds. The app must not contain GitHub PATs or signing passwords.
