Project signing keystore (chromium-release.jks)
Default passwords (local / CI without secrets):
  storePassword = chromium123
  keyAlias      = chromium
  keyPassword   = chromium123

Same signature + same applicationId + higher versionCode
= in-place update without uninstall (rootfs / Chromium data kept).

For production, replace this keystore and set GitHub secrets:
  STORE_FILE_BASE64, STORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD
