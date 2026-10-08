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

Do steps 1-3 yourself, in a tab of your own (or on a computer). An AI
agent can walk you through them, but never give it the password or run
these through it: what an agent runs ends up in its conversation.

First make a long random password in your password manager (24+
characters) and save the entry. Then, in the app's Debian (`keytool`
comes with the JDK) or on a computer:

```sh
mkdir -m 700 ~/release-key && cd ~/release-key
keytool -genkeypair -v \
  -keystore mynx-release.jks -storetype PKCS12 \
  -alias mynx -keyalg RSA -keysize 4096 -validity 18250 \
  -dname "CN=Mynx"
```

- RSA 4096, valid for 50 years (Android wants at least 25).
- It asks for the password twice (paste it; nothing shows). A PKCS12
  keystore has one password for the store and the key, so the store
  password and the key password are the same.
- `-dname` only goes into the certificate; change it as you like.

Check it: `keytool -list -v -keystore mynx-release.jks | head -20`:
alias `mynx`, `PrivateKeyEntry`, a 4096-bit RSA key, valid until about
50 years from now. Save its `SHA256:` fingerprint in the password
manager entry: it's how you can tell later that an APK was signed with
this key (`apksigner verify --print-certs`).

## 2. Back it up (before anything else)

Keep the keystore file **and** its password in two places offline, for
example:
1. a USB stick kept at home, and
2. a second one (or an encrypted backup) somewhere else.

A password manager entry with the file attached can be a third copy
(copy the file to `Download` to attach it, then delete it there).
Don't keep it only on the phone: losing the phone must not lose the key.
Check that a backup copy opens: `keytool -list -keystore COPY` with the
password.

## 3. Give GitHub the key (once)

From the folder with the keystore (`gh` is logged in; `-R` names the
repo, since that folder isn't in it):

```sh
base64 -w0 mynx-release.jks | gh secret set MYNX_RELEASE_KEYSTORE_BASE64 -R est4s/mynx
gh secret set MYNX_RELEASE_STORE_PASSWORD -R est4s/mynx   # paste the password when asked
gh secret set MYNX_RELEASE_KEY_PASSWORD -R est4s/mynx     # the same password
gh secret set MYNX_RELEASE_KEY_ALIAS -R est4s/mynx --body mynx
gh secret list -R est4s/mynx                              # the four MYNX_RELEASE_* names
```

Typing the passwords at the prompt keeps them out of the shell history.
GitHub never shows a secret again, and only the release workflow (on
tags, which only collaborators can push) gets them. The workflow stops
with an error naming any secret that's missing.

Then delete the key from the phone: `rm -r ~/release-key` (GitHub and
your backups have it; left in `/root`, it would also go into a
`backup-root.sh` backup on phone storage). Copy something else to the
clipboard so the password isn't left there.

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

Debug builds are a separate app, **Mynx Dev**, with the application ID
`io.github.est4s.terminal.dev` (`applicationIdSuffix`), so they install
beside the release (`io.github.est4s.terminal`), each with its own
Debian. The release keeps the ID it can never change.

Builds from before Mynx Dev (up to build 87) are debug-signed with the
release's ID. Android won't install a release over one of those:
uninstall it first, which **deletes its Debian**. To move to the
release:

1. In the old app: download the release APK
   (`gh release download v0.1.0 -D /storage/emulated/0/Download`), then
   `scripts/backup-root.sh`. It saves `/root` (without caches, which
   rebuild themselves), the packages installed by hand and
   `restore-root.sh` to `/storage/emulated/0/mynx-backup`, which survives
   the uninstall. It never overwrites a backup that's there.
2. Uninstall the app (not Mynx Dev); install the APK from the Files app.
3. In the release's fresh Debian:
   `bash /storage/emulated/0/mynx-backup/restore-root.sh`. It checks the
   backup, asks, restores `/root`, then `apt-get install`s the packages
   (naming any that fail).
4. Check `gh auth status` and `claude`, open a new tab, then delete the
   backup: it holds those logins, and other apps can read phone
   storage.

## Later: Google Play

Play takes an AAB and signs what it delivers with the key held in Play
App Signing. To keep people who installed from GitHub able to update
from Play, give Play this same key as the app signing key when the app
is first set up there (and make a separate upload key). Decide this when
the final name is chosen.
