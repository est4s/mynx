# Releasing

Release APKs are built by `.github/workflows/release.yml` when a tag like
`v0.1.0` is pushed, signed with the release key from the repo's secrets,
and published on GitHub Releases as `mynx-0.1.0.apk`.

The release key is the app's identity. **Every update must be signed with
the same key: if it's lost, the app can never be updated again** (people
would have to uninstall, which deletes their Debian). Only the maintainer makes
and holds it. It is never committed; `.gitignore` blocks `*.jks`,
`*.p12` and `*.keystore` (except `signing/debug.keystore`).

## 1. Make the key (once)

In the app's Debian (`keytool` comes with the JDK) or on a computer:

```sh
keytool -genkeypair -v \
  -keystore mynx-release.jks -storetype PKCS12 \
  -alias mynx -keyalg RSA -keysize 4096 -validity 18250 \
  -dname "CN=Mynx"
```

- RSA 4096, valid for 50 years (Android wants at least 25).
- It asks for a password. A PKCS12 keystore has one password for the
  store and the key, so the store password and the key password are the
  same. Use a long random one and keep it in your password manager.
- `-dname` only goes into the certificate; change it as you like.

Check it: `keytool -list -v -keystore mynx-release.jks` (alias `mynx`,
RSA 4096, the dates).

## 2. Back it up (before anything else)

Keep the keystore file **and** its password in two places offline, for
example:
1. a USB stick kept at home, and
2. a second one (or an encrypted backup) somewhere else.

A password manager entry with the file attached can be a third copy.
Don't keep it only on the phone: losing the phone must not lose the key.
Then remove any copy you don't need (e.g. in `Download`).

## 3. Give GitHub the key (once)

From the folder with the keystore, in this repo (`gh` is logged in):

```sh
base64 -w0 mynx-release.jks | gh secret set MYNX_RELEASE_KEYSTORE_BASE64
gh secret set MYNX_RELEASE_STORE_PASSWORD   # paste the password when asked
gh secret set MYNX_RELEASE_KEY_PASSWORD     # the same password
gh secret set MYNX_RELEASE_KEY_ALIAS --body mynx
gh secret list                              # the four MYNX_RELEASE_* names
```

Typing the passwords at the prompt keeps them out of the shell history.
The workflow stops with an error naming any secret that's missing.

## 4. Cut a release

On `main`, with the commit you want to release pushed:

```sh
git tag v0.1.0
git push origin v0.1.0
```

- The tag gives the version name (`v0.1.0` → `0.1.0`, shown by
  `mynx about`); the version code is the workflow's run number.
- A tag with a suffix (`v0.2.0-beta.1`) makes a pre-release.
- The workflow runs all tests, builds the APK (about the same time as a
  debug build), checks its signature and creates the release with notes
  generated from the commits. Watch it with `gh run watch`.
- If it fails, fix it on `main`, then move the tag:
  `git tag -d v0.1.0 && git push origin :v0.1.0`, tag again and push.
  Delete a half-made release first if there is one
  (`gh release delete v0.1.0`).

## Debug and release builds on one phone

Both use the application ID `io.github.est4s.terminal` (the launcher
shortcut names it, and it can never change) but different keys, so
Android won't install one over the other. To try a release build on the
development phone, uninstall the debug build first, which **deletes its
Debian**; going back to debug builds means uninstalling again.

## Later: Google Play

Play takes an AAB and signs what it delivers with the key held in Play
App Signing. To keep people who installed from GitHub able to update
from Play, give Play this same key as the app signing key when the app
is first set up there (and make a separate upload key). Decide this when
the final name is chosen.
