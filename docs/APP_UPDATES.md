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
