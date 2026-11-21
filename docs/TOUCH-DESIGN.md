# Touch mode: chat first, with a drawer

The design for touch view's next step (maintainer's decision,
2026-11-11; roadmap "Touch mode", work log "Next" 4). The pictures
are mock-ups from a design study, drawn in a browser at 390 × 844 dp
(2× pixels): sample data, not the app. Where this text and a picture
differ, the text wins.

| Pixel style | First pass |
|---|---|
| ![Chat home, pixel style](images/touch/chat-home-pixel.png) | ![Chat home, first pass](images/touch/chat-home-first-pass.png) |
| ![Drawer, pixel style](images/touch/drawer-pixel.png) | ![Drawer, first pass](images/touch/drawer-first-pass.png) |
| ![A chat, pixel style](images/touch/chat-pixel.png) | The chat screen as built today (CI's `screenshots`) |

## Screens

**Chat home** (touch view's start screen, replacing Home's list):
- Top row: ☰ (opens the drawer), the title art, and the services
  count (`● N`, as the strip's `servicesMark()`), which opens the
  Services list. Nothing when no service runs.
- A heading ("What do you want to make?") and one line on what the
  agent can do, pushed to the bottom half so the thumb reaches it.
- **Continue:** live chats only, `ChatHub`'s rows with a
  `ChatRowState` of `WORKING` (cyan mark, "working") or `WAITING`
  (pink mark, "waiting for you"); idle and past chats stay in the
  history. At most three; none, no heading.
- The prompt box as the chat screen's (+ for context, the mic,
  Send); the mock-ups leave the mic out, keep it. Sending starts a
  new chat (`ChatHub.newChat()`) and shows it.
- A line under it: agent, mode and folder ("claude code · auto · ~/").
- Claude Code not installed: the box is replaced by an Install
  button (`menu --act agent:claude`), as Home's "(install)" item.

**Drawer** (over the chat home, about 82% of the width, a dimmed
scrim over the rest; tap ☰ to open, tap the scrim or Back to close;
no edge swipe, which is Android's Back):
- The title art, then **New chat** (the main action).
- **Chats:** the latest few from `chatHistory()` (live ones first,
  marked as on the chat home), then **All chats**, which opens the
  history screen (`ChatHistoryView`, unchanged).
- **Linux:** Home's main list, `HomeMenus.items(HOME_MAIN)`, in
  `menu.conf`'s order and with its actions: Shell, AI agents,
  Services (with the count), Apps, Terminal apps, Games, Files,
  Settings by default. The mock-ups show a subset; show them all.
  Items that open a list (`HomeAction.Menu`: Apps, Games, …) get a
  › and open Home's list screen at that list (`HomeView.showMenu()`),
  whose Back returns to the chat home; the others act as on Home.
- At the bottom: **Terminal**, which shows the tabs, as Home's
  Terminal button does today (the `view` setting stays `touch`).

**A chat:** the screen as built (← back, title, mode, the usage ring,
⋮; the + sheet; artifact cards). ← goes to the chat home, not to
Home's list. Only the style changes, if the pixel style is chosen.

**Back** (`MainActivity.goBack()`): a chat → the chat home; the
drawer → closed; history → the chat home; a Home list or the
terminal → the chat home; the chat home → leaves the app, as Home's
main list does today.

## Where the logic goes

- `core`: what the chat home and the drawer list. A small model
  (e.g. `ChatHomeModel`) built from `HomeMenus` and `chatHistory()`:
  the Continue rows, the drawer's chats (how many, which first), the
  Linux items. Test-first; reuse `tests/fixtures/menu.conf`.
- `app`: `ChatHomeView` and `DrawerView`, thin, drawing that model;
  `MainActivity` shows the chat home where it showed `HomeView` in
  touch view. The service keeps which screen shows (`chatPlace`), so
  a recreated activity comes back to it.
- Screenshots: add the chat home and the drawer to
  `app/src/test/.../Screenshots.kt` at the usual sizes (412 and 360
  dp wide, landscape).

## Style (not decided: ask the maintainer)

Both versions use the same layout. The pixel style matched the logo
better in the study; the first pass is closer to today's screens.

**Pixel style**, taken from `docs/images/mynx.svg`:
- Colours (Neon's; another terminal theme gives its own, worked
  out by `pixelPalette()`: maintainer, 2026-11-11): background
  `#0D0221`; cards and fields `#170838`; lines
  `#3D2A70`; text `#F2ECFF`, secondary text `#B3A6DC`; cyan `#00F3FF`
  (actions, "working", focus); pink `#FF0BBB` (the shadow, section
  labels, "waiting for you"; `#FF7FDC` for pink text on dark).
- Shapes: square corners; 2 dp borders; the logo's hard drop shadow,
  4 dp right and down in pink, only on the main action of a screen
  (the prompt box, New chat, an artifact card).
- Icons drawn from blocks on a 20-unit grid, filled, no curves.
- Type: the mock-ups use Pixelify Sans (labels, buttons, headings)
  and JetBrains Mono (text). The app bundles only JetBrains Mono
  Nerd Font Mono; JetBrains Mono everywhere was the agent's
  suggestion. A second font would need fetching like the first
  (`scripts/fetch-font.sh`) and crediting in `mynx about`.

**First pass:** the theme's colours (Neon's `colors.properties`),
rounded cards (14-28 dp), Inter for text and JetBrains Mono for the
heading; Inter isn't bundled either.

Either way: the logo is `mynx.svg` as it is (never redrawn or
recoloured); touch targets at least 44 dp; text at least 4.5:1
against its background.

## Files (touch mode's file manager)

The maintainer's decisions (2026-11-21): touch mode's **Files**
opens this screen in place of nnn (nnn stays, as "Files (nnn)" in
Terminal apps and in the terminal's menu); Delete is permanent, after
a confirmation; it starts at `~`, with the phone's storage among the
places.

**The screen** (`FilesView`, over the terminal as Home is):
- Top row: ← (Back), the folder's path as crumbs (`~ / src / app`,
  each one taps to that folder), ⋮ (Show hidden files, New folder).
- Places, a row of chips under it: Home, Download, Phone, Debian
  (`FILE_PLACES`).
- The list: folders first, then files, by name ignoring case; each
  row an icon (folder, picture, file, link), the name, and size ·
  date. Hidden (dot) names only when shown. An empty folder says so;
  one that can't be read says why.
- Tap a folder: opens it. Tap a picture: `ImageViewActivity`. Tap
  another file: Android's "open with" (`ACTION_VIEW` through
  `ShareProvider`), else Share.
- Long-press selects; then taps toggle, and the top row becomes
  "N selected" with Copy, Cut, Rename (one only), Delete, Share, ✕.
- Copy/Cut keep a clip (paths and the op) across folders; a bar at
  the bottom says "N to paste" with **Paste here** and ✕.
- Bottom actions, always: **Chat here** (a new chat whose agent runs
  in this folder: `ChatHub.newChat(cwd)`), **Terminal here** (a new
  tab with a shell in it: `TerminalService.newSession(cwd = …)`).
- Back: leaves the selection, else goes up a folder, else (at one
  of the places, `filesBack()`) to the chat home, so it never climbs
  into `/storage/emulated`, which Android won't list.

**Every change runs in Debian**, never through `java.io.File`: proot
fakes hard links (`--link2symlink`, `.l2s.*` files with host paths),
so a rename or delete from the app's side breaks them, and
`deleteRecursively()` follows links. The app runs each job's `argv`
in a proot of its own (`TerminalService.runInDebian`) and shows its
output on screen if it fails. Listing reads through `WebApps`
(`host()`, `resolve()`), read-only.

**core** (`FileBrowser.kt`, tested):
```kotlin
data class FileEntry(val name: String, val path: String, val folder: Boolean,
                     val link: Boolean, val size: Long, val modified: Long)
fun listFolder(apps: WebApps, folder: String, showHidden: Boolean): Result<List<FileEntry>>
fun folderCrumbs(path: String, home: String): List<Pair<String, String>>  // label to path
fun parentFolder(path: String): String?                                   // null at "/"
val FILE_PLACES: List<Pair<String, String>>                               // label to path
fun fileSizeText(bytes: Long): String                                     // "0 B", "12 KB", "3.4 MB"
fun isPictureName(name: String): Boolean                                  // as chatImageFile()
fun fileNameProblem(name: String): String?                                // null when fine
fun pasteName(name: String, taken: Set<String>): String                   // "a (2).txt"
enum class ClipOp { COPY, MOVE }
data class FileClip(val op: ClipOp, val paths: List<String>)
sealed interface FileJob {
    data class Run(val argv: List<String>, val title: String) : FileJob
    data class Refused(val reason: String) : FileJob
}
fun pasteJob(clip: FileClip, dest: String, taken: Set<String>): FileJob
fun deleteJob(paths: List<String>): FileJob
fun renameJob(path: String, newName: String, taken: Set<String>): FileJob
fun newFolderJob(parent: String, name: String, taken: Set<String>): FileJob
```
`HomeAction.Files(folder)` is the Files item's action (the main
list's `files`). `ChatHub.newChat(cwd: String = this.cwd)`.

**app:** `FilesView` (thin, drawing core's results), `MainActivity`
showing it (`filesShown`, `goBack()`), `TerminalService.runInDebian(argv,
done)` and `newSession(command, cwd)`; a `files` screenshot.

Limits: a chat started in another folder is listed while it's live;
its past sessions are in that folder's history, which the history
screen (root's home) doesn't show yet. The phone's storage shows only
what Android lets the app read without a storage permission.
