# ~/.bashrc: runs in every interactive bash. Yours to edit.

case $- in *i*) ;; *) return ;; esac

export LANG=C.UTF-8
export PATH="$HOME/.local/bin:$PATH"
export EDITOR=nano VISUAL=nano

# eza: ls with Nerd Font icons.
if command -v eza >/dev/null; then
    alias ls='eza --icons=auto --group-directories-first'
    alias ll='eza -l --icons=auto --group-directories-first --git'
    alias la='eza -la --icons=auto --group-directories-first --git'
    alias tree='eza --tree --icons=auto'
fi

# Names the tab after the current folder.
mynx_set_title() {
    local title=${PWD##*/}
    [[ $PWD == "$HOME" ]] && title='~'
    printf '\e]0;%s\a' "${title:-/}"
}

# Prompt: starship (~/.config/starship.toml). Both branches keep any
# PROMPT_COMMAND already set; the app uses it to remember each tab's folder.
if command -v starship >/dev/null; then
    starship_precmd_user_func=mynx_set_title
    eval "$(starship init bash)"
else
    PROMPT_COMMAND="mynx_set_title${PROMPT_COMMAND:+; $PROMPT_COMMAND}"
fi

# Launcher menu (/usr/local/bin/menu). Its Exit item returns 10: close
# the tab. The app sets MYNX_MENU in the first tab of a fresh start.
menu() {
    keybar menu menu "$@"
    local rc=$?
    ((rc == 10)) && exit 0
    return $rc
}
if [[ -n ${MYNX_MENU-} ]]; then
    unset MYNX_MENU
    menu --boot
fi
