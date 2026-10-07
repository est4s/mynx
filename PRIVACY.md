# Privacy

**PC-26 collects nothing.** It has no accounts, no analytics, no ads
and no crash reporting, and it never sends anything about you or your
phone anywhere.

## What stays on your phone

Everything the app handles stays in its own storage on your phone:
the Debian system, your files, settings and tab history.

## Permissions

Each one is used only when you, or a program you run in Debian, ask
for it, and the result goes only to that program. Each phone feature
can be turned off in the settings (`android-*`, `sound-device`).

| Permission | Used for |
|---|---|
| Internet | programs in Debian (`apt`, `curl`, AI agents, …); the app itself connects to nothing |
| Notifications | the app's running notification, `pocket notify` and agent notifications |
| Location (while in use) | `pocket location` |
| Camera | `pocket camera`; the flashlight (`pocket torch`) needs none |
| Microphone | `pocket audio record`, and programs recording through the sound device |
| Physical activity | the step sensors in `pocket sensor` |
| Vibration, wakelock, foreground service | `pocket vibrate`, the `wakelock` setting, keeping tabs running |

Android asks you before the first use of location, camera, microphone
and physical activity, and you can withdraw any of them in Android's
settings.

## Programs you run

Debian programs, and AI agents you install (Claude Code, Codex,
Gemini CLI), are separate software with their own privacy terms. What
they send over the network is up to them and to you.

## Sharing

Files and text you share to PC-26 from other apps are saved in
`~/Shared`. Files you send with `pocket share` go only to the app you
pick in Android's share sheet.

## Contact

Questions: open an issue on
[GitHub](https://github.com/est4s/PC-26/issues).
