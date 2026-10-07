# The app's setup for interactive bash, loaded at login by
# /etc/profile.d/pc26.sh. Replaced on each app update; to undo
# something here, do it in ~/.bashrc (e.g. `unset -f claude`).

# AI agents typed by name run under their key bar (the agent's own, e.g.
# claude.conf, else the generic agent bar), like `pocket agent start`.
for _pc26_agent in claude codex gemini; do
    eval "$_pc26_agent() { _pc26_run_agent $_pc26_agent \"\$@\"; }"
done
unset _pc26_agent

_pc26_run_agent() {
    local name=$1
    shift
    if ! type -P "$name" >/dev/null; then
        echo "$name isn't installed; pocket agent install $name installs it" >&2
        return 127
    fi
    keybar "$name,agent" "$name" "$@"
}
