# Signing OpenFlow — instructions only

No keystore, password, or key material belongs in this repo or in any chat message.

1. On your own machine, generate a release keystore with `keytool` (Android Studio's "Generate Signed Bundle / APK" does the same):
   - Choose a strong store password and a separate key password.
   - Keep the alias, passwords, and keystore file in your password manager.
2. In the GitHub repository, go to Settings → Secrets and variables → Actions, and add:
   - `ANDROID_KEYSTORE_BASE64`: base64 encoding of the keystore file
   - `KEYSTORE_PASSWORD`
   - `KEY_ALIAS`
   - `KEY_PASSWORD`
3. Push to `main`. The workflow decodes the keystore to a temporary file only for that run, signs release, and uploads the artifacts. The keystore file itself is never committed.

To rotate a compromised key, generate a new keystore, replace the four secrets, and delete the old keystore file from your machine and password manager history as appropriate. Android release updates require the same signing key as the installed version, so do not lose the key you ship with.
