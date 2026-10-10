# Privacy

**mynx collects nothing.** It has no accounts, no analytics, no ads
and no automatic crash reporting, and it never sends anything about
you or your phone anywhere. The one connection it makes on its own is
asking GitHub whether there's a new version (see "Updates"), which you
can turn off.

## What stays on your phone

Everything the app handles stays in its own storage on your phone:
the Debian system, your files, settings and tab history.

## Permissions

Each one is used only when you, or a program you run in Debian, ask
for it, and the result goes only to that program. Each phone feature
can be turned off in the settings (`android-*`, `sound-device`).

| Permission | Used for |
|---|---|
| Internet | programs in Debian (`apt`, `curl`, AI agents, …); the app itself only checks GitHub for updates and downloads the ones you accept |
| Install apps | `mynx update` opens Android's installer for the update it downloaded; Android asks you to allow it first |
| Notifications | the app's running notification, `mynx notify` and agent notifications |
| Location (while in use) | `mynx location` |
| Camera | `mynx camera`; the flashlight (`mynx torch`) needs none |
| Microphone | `mynx audio record`, and programs recording through the sound device |
| Physical activity | the step sensors in `mynx sensor` |
| Vibration, wakelock, foreground service | `mynx vibrate`, the `wakelock` setting, keeping tabs running |

Android asks you before the first use of location, camera, microphone
and physical activity, and you can withdraw any of them in Android's
settings.

## Updates

About once a day, mynx asks GitHub's API
(`api.github.com/repos/est4s/mynx/releases/latest`) for the latest
release. The request carries no data about you: only the app's
version, in its user agent. Like any website, GitHub sees your IP
address ([GitHub's privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement)).
A new version is only downloaded, from GitHub, when you say yes in
`mynx update`. `mynx set update-check off` stops the daily check.

## Bug reports

`mynx report` (menu → Settings → Help → Report a bug) sends nothing until you
say yes, and shows you the whole report first: what you wrote, the
app's version, the Android version, the phone model and the app's
last crash, if any (`--no-crash` leaves that out). It goes to GitHub
as an issue, which anyone can read: through `gh` from your own GitHub
account, or as a filled-in page in your browser that you submit
yourself.

## Programs you run

Debian programs, and AI agents you install (Claude Code, Codex,
Gemini CLI), are separate software with their own privacy terms. What
they send over the network is up to them and to you.

## Sharing

Files and text you share to mynx from other apps are saved in
`~/Shared`. Files you send with `mynx share` go only to the app you
pick in Android's share sheet.

## Contact

Questions: open an issue on
[GitHub](https://github.com/est4s/mynx/issues).
