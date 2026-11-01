"""Validate aapt badging against approved Build 1092; never touch the APK."""
import re
import sys

text = open(sys.argv[1], encoding="utf-8").read()
assert re.search(r"package: name='com\.massisolutions\.tridrone'", text), "Package identity changed"
version = re.search(r"versionCode='(\d+)'", text)
assert version and int(version[1]) > 1092, "Development APK must update Build 1092 in place"
assert "sdkVersion:'26'" in text and "targetSdkVersion:'35'" in text, "SDK compatibility changed"
expected = {
    "android.permission.INTERNET", "android.permission.REQUEST_INSTALL_PACKAGES",
    "android.permission.BLUETOOTH", "android.permission.BLUETOOTH_ADMIN",
    "android.permission.BLUETOOTH_CONNECT", "android.permission.BLUETOOTH_SCAN",
    "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.FOREGROUND_SERVICE", "android.permission.FOREGROUND_SERVICE_LOCATION",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE", "android.permission.POST_NOTIFICATIONS",
    "com.massisolutions.tridrone.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
}
actual = set(re.findall(r"uses-permission(?:-sdk-\d+)?: name='([^']+)'", text))
assert actual == expected, f"Permission difference: added={actual - expected}, removed={expected - actual}"
print("PASS: package, upgrade version, SDK levels and permission names match Build 1092 compatibility requirements")
