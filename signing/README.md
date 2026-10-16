# Public development signing key
This PKCS12 key is deliberately public, password `themestudio-public`, alias
`themestudio`. It produces a stable **release-signed** APK without GitHub Secrets.
It is NOT a private production identity: anyone can sign an update with this key.
Do not use this certificate for Play Store or trusted distribution. A confidential
production identity cannot be provided by a publicly committed key. Certificate
fingerprints are printed and verified by the workflow.
