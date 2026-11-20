# Background services

Part of the guide to this Debian, `/opt/mynx/AGENTS.md`.

| Command | Does |
|---|---|
| `mynx service [list]` | the background services: state, uptime, restarts |
| `mynx service add NAME [OPTIONS] -- COMMAND…` | add a service to `services.conf`; `--autostart`, `--restart no\|on-failure\|always`, `--cwd DIR`, `--restart-limit N`, `--restart-delay S`, `--stop-timeout S` |
| `mynx service start\|stop\|restart NAME` | start, stop (TERM, then force after `stop-timeout`) or restart a service |
| `mynx service log NAME [-n N]` | the last N lines (100) of a service's terminal |
| `mynx service open NAME` | show a service as a tab (the app must be on screen); closing the tab hides it again |
| `mynx service remove NAME` | stop a service and remove it from `services.conf` |
| `mynx service autostart NAME on\|off` | whether a service starts when the app starts (its `autostart` line in `services.conf`); it doesn't start or stop it now |
| `mynx service adopt NAME` | turn this tab into the unsaved service NAME, as it runs; `mynx service save NAME [--command CMD]` saves it to `services.conf` |

Servers and daemons (a web server, `sshd`, a database, a bot) run as
services: each in a hidden terminal of its own, kept running by the
app with no tab open, restarted when it fails, and started again
when the app starts. The menu's **Services** lists them.

They're defined in `~/.config/mynx/services.conf`, one `[name]`
block each (names: `a-z`, `0-9` and `-`, up to 20 characters):

```
[web]
command = python3 -m http.server 8080
cwd = ~/site
autostart = yes
restart = on-failure
```

| Key | Means |
|---|---|
| `command` | what to run, in bash (required); the login PATH (with `~/.local/bin`) and `~/.profile` apply |
| `cwd` | the folder it runs in (default `~`) |
| `autostart` | `yes`: start it when the app starts (default `no`; not at phone boot) |
| `restart` | `on-failure` (default): restart it when it exits with a code other than 0; `always`; `no` |
| `restart-limit` | quick failures in a row before giving up, with a phone notification (default 5; 0 never gives up) |
| `restart-delay` | seconds before the first restart (default 1), doubling up to 60 |
| `stop-timeout` | seconds `stop` waits after TERM before forcing it (default 10) |

`mynx service add web --autostart --cwd ~/site -- python3 -m http.server 8080`
writes that block (one argument after `--` is kept as shell text,
several are quoted as needed), `mynx service remove web` stops it and
takes its block out; the rest of the file and its comments stay.
`mynx service autostart web on|off` (or the menu's Services → web →
Autostart) changes its `autostart` line. Edit the file by hand, then
`mynx check`. `mynx service start|stop|restart NAME` control one; a
service stopped this way isn't restarted. **What starts with the app:**
the `autostart = yes` services, and after Android closed the app, also
those that were running then; a service you stopped stays stopped. The
notification's Exit stops them all, and the next start brings back only
the `autostart` ones.
`mynx service` lists them: `running`, `stopping`, `stopped` or
`failed` (it gave up), with the last exit code, uptime and restarts.
`mynx service log NAME -n 50` prints the end of its terminal.
`mynx service open NAME` (or the menu's Services → NAME → Open)
shows it as a tab, titled `● NAME` (`○ NAME: exit 1`, `restarting`,
`failed` or `stopped` while it isn't running), with the `service`
key bar (Esc, Ctrl, arrows, Tab, Ctrl+C, Enter, Ctrl+D) unless its
program asks for another: type into it, Ctrl+C it. Its × only hides
it; long-pressing the tab offers **Hide**, **Restart** and **Stop**
(**Start** when it isn't running). When it restarts, the tab shows
the new run. Service tabs aren't brought back after Android closes
the app: the services come back, hidden.

**Seeing them from anywhere:** while any service runs, the tab strip
shows `● N` (how many) right of **+**; tapping it opens a tab with the
menu's Services list (`menu services` does the same in a shell), or
Home's Services list when `view` is `touch`. The
launcher menu's Services item shows the count too (`Services ● 2`),
and the app's notification has a **Services** button.

**A tab into a service:** long-press a tab and pick **Run as
service** (it offers the name of the program running in it), or type
`mynx service adopt NAME` in the tab: the tab, with what runs in it,
becomes the service NAME without restarting anything, and stays open
as its tab. It isn't saved (marked `*` in the list) and ends if
Android closes the app; `mynx service save NAME [--command COMMAND]`
writes it to `services.conf`, with the command running in it then
(read from its terminal's foreground program) and its folder.

**How a service runs:** the app runs `/opt/mynx/lib/service-run
NAME`, which runs the command in a process group of its own that has
the terminal, and writes the group's id to
`/tmp/.mynx/services/NAME.pid`. Stop sends TERM to that group (so
databases shut down cleanly), then forces it after `stop-timeout`.
The exit code goes to `/tmp/.mynx/services/NAME.exit`: the command's
own, or 128+N when signal N ended it (143 for TERM, 130 for Ctrl+C);
2 when it couldn't start (no such service, no command, no such
folder). Ctrl+Z is off in a service's terminal. Programs in a service
see `MYNX_SERVICE=NAME`.

- **Ports:** nothing below 1024 (proot's root is fake): run `sshd -p
  8022`, a web server on 8080.
- **Wakelock:** with `wakelock = auto` (the default), the app keeps
  the CPU awake while any service runs, so servers answer with the
  screen off.
