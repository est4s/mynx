# The app's setup for interactive bash, loaded at login by
# /etc/profile.d/mynx.sh. Replaced on each app update; to undo
# something here, do it in ~/.bashrc (e.g. `unset -f claude`).

# AI agents typed by name run under their key bar (the agent's own, e.g.
# claude.conf, else the generic agent bar), like `mynx agent start`.
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
    keybar "$name,agent" "$name" "$@"
}
