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
  -dname "CN=mynx"
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

## mynx dev beside the release

Debug builds are a separate app, **mynx dev**, with the application ID
`io.github.est4s.terminal.dev` (`applicationIdSuffix`), so they install
beside the release (`io.github.est4s.terminal`), each with its own
Debian. The release keeps the ID it can never change.

## Moving to a new phone (or a reinstall)

Uninstalling deletes the app's Debian, so move `/root` with the two
scripts:

1. In the old Debian: `scripts/backup-root.sh`. It saves `/root`
   (without caches, which rebuild themselves), the packages installed
   by hand and `restore-root.sh` to `Download/mynx-backup`, and never
   overwrites a backup that's there.
2. Get that folder onto the new phone (or keep it through the
   reinstall), install mynx, then in the Files app select its four
   files and **Share** them to mynx: they land in `~/Shared` (a new
   app can't read files the old one left in Download).
3. In the new Debian: `bash ~/Shared/restore-root.sh`. It checks the
   backup, asks, restores `/root`, then `apt-get install`s the
   packages (naming any that fail).
4. Check `gh auth status` and `claude`, open a new tab, then delete the
   backup's files, here and in Download: they hold those logins.

## Later: Google Play

Play takes an AAB and signs what it delivers with the key held in Play
App Signing. To keep people who installed from GitHub able to update
from Play, give Play this same key as the app signing key when the app
is first set up there (and make a separate upload key). Decide this when
the final name is chosen.
