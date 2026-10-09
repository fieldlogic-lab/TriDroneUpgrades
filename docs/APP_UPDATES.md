# TriDrone updates — public repository distribution

TriDrone source repository is public. The Android updater defaults to:
`https://github.com/fieldlogic-lab/TriDroneUpgrades/releases/download/dev-latest/latest.json`

The GitHub Actions workflow builds an APK on every app change. If the persistent signing secrets are configured, it publishes `tridrone.apk` and `latest.json` together to the `dev-latest` GitHub Release in the **same repository**, using the built-in GitHub Actions token. No second repository or distribution PAT is required.

## Required one-time signing setup

1. Generate a **permanent Android keystore locally** (do not commit it):
   `keytool -genkeypair -v -keystore tridrone-development.jks -alias tridrone -keyalg RSA -keysize 3072 -validity 3650`
2. Back up the keystore and passwords securely.
3. In GitHub repository Settings → Secrets and variables → Actions, add:
   - `TRIDRONE_KEYSTORE_BASE64`: base64 encoding of keystore bytes.
   - `TRIDRONE_STORE_PASSWORD`: keystore password.
   - `TRIDRONE_KEY_ALIAS`: `tridrone`.
   - `TRIDRONE_KEY_PASSWORD`: key password.
4. In repository Settings → Actions → General → Workflow permissions, ensure **Read and write permissions** is enabled so Actions can publish releases. Organization policy may override this.
5. Trigger the workflow from Actions → Build TriDrone Android APK → Run workflow. Verify its `Publish signed update to this repository` step succeeds and the public release contains both files.
6. Install the signed APK once. Previously installed randomly signed debug APKs may require uninstalling first; **export survey data before uninstalling**. Afterward, the updater can install over the existing app when signing and package name remain unchanged.

On the phone: tap **Check for App Updates** → **Check now** → **Download** → Android installer approval. Android does not permit a normal app to silently overwrite itself.

The app verifies APK SHA-256 against the manifest, but this manifest is trusted via HTTPS rather than an independent cryptographic signature. GitHub release publication and APK signing are necessary safeguards. Never place keystore material in this public repository.

## Current status
Code is configured; automatic release publication is skipped until signing secrets are set. The app's button will not find a valid feed until a signed release is published.
