# The app's setup for interactive bash, loaded at login by
# /etc/profile.d/mynx.sh. Replaced on each app update; to undo
# something here, do it in ~/.bashrc (e.g. `unset -f claude`).

# AI agents typed by name start through `mynx agent start`: under their
# key bar (the agent's own, e.g. claude.conf, else the generic agent
# bar), and Claude Code with what it needs under proot.
for _mynx_agent in claude codex gemini; do
    eval "$_mynx_agent() { _mynx_run_agent $_mynx_agent \"\$@\"; }"
done
unset _mynx_agent

_mynx_run_agent() {
    local name=$1
    shift
    if ! type -P "$name" >/dev/null; then
        echo "$name isn't installed; mynx agent install $name installs it" >&2
        return 127
    fi
    mynx agent start "$name" "$@"
}

# After an app update, the first interactive shell shows what's new
# (mynx notes new) once. mynx only starts when the version changed.
_mynx_whats_new() {
    local version seen tools=${MYNX_TOOLS:-${BASH_SOURCE[0]%/*}}
    local state=${XDG_STATE_HOME:-$HOME/.local/state}/mynx/notes-seen
    { read -r version <"$tools/.version"; } 2>/dev/null || return 0
    [[ $version == *-*-?* ]] || return 0   # BUILD-INSTALLTIME-NAME
    { read -r seen <"$state"; } 2>/dev/null || seen=
    [[ ${version#*-*-} == "$seen" ]] && return 0
    mynx notes new 2>/dev/null
    mynx notes seen >/dev/null 2>&1
}
if [[ $- == *i* ]]; then _mynx_whats_new; fi
