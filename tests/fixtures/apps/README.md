# App fixtures

Folders of `~/apps` for core's tests (`WebAppsTest.kt`) and the menu's
bats tests, so both read the same rules (`core/.../WebApps.kt`). Copy
the whole `tests/fixtures/` folder as a home (keeping links): `apps/`
then sits beside `secret.txt`, which `escape/outside.txt` points at.

| Folder | What it is | Label |
|---|---|---|
| `hello` | static: `index.html` with a `<title>` over several lines and `&amp;`, `app.js`, `sub/index.html` | Hello & Welcome |
| `notes` | static with `app.conf`: `name`, `icon` (`icon.png`); its `<title>` loses to `name` | My Notes |
| `served` | served: `app.conf` with `url` and `service` (`served-backend`), no `index.html` | Served |
| `escape` | static, no `<title>`; `outside.txt` is a relative link out of the folder (to `../../secret.txt`): a 404 | Escape |
| `Bad_Name` | `index.html` but a bad name: not an app, `mynx check` says why | - |
| `plain` | a folder with neither `index.html` nor `app.conf`: not an app, nothing said | - |
