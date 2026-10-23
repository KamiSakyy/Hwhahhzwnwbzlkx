Demo signing key for CI builds of CutPro.

The keystore is committed so every GitHub Actions build is signed with the SAME
key and users can update the app without uninstalling it first.

Alias:    webrecorder
Store/key password: android
Type:     PKCS12 (cutpro.p12.b64 is base64 of release.p12)
Cert SHA-256: see CERT-SHA256.txt

WARNING: this key is public (it lives in the repository). It is fine for a free
side-loadable demo APK, but never use it for Google Play or anything valuable.
Replace it with your own private key (repository secrets KEYSTORE_BASE64,
KEYSTORE_PASSWORD, KEY_ALIAS, KEY_PASSWORD) for a real release.
