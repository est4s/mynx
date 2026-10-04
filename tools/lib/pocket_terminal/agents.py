"""AI agent CLIs: how to install them with their official installers, and
the hook entries that make them call `pocket hook NAME`.

Whether an agent notifies is only stored in the agent's own config (the
hook entries are there or not), so the two can't disagree.
"""
import json
import os
import shutil
from collections import namedtuple

from .client import TOOLS, Failure

# events: the agent's hook event -> what it means for `pocket hook`
# downloads: where its installer downloads silently (None: it shows progress)
# needs: commands it runs that a fresh Debian may lack -> their package
Agent = namedtuple("Agent", "name title command config events installer downloads needs",
                   defaults=[None, {}])

AGENTS = {
    "claude": Agent("claude", "Claude Code", "claude", "~/.claude/settings.json",
                    {"UserPromptSubmit": "start", "Stop": "stop", "Notification": "attention"},
                    "curl -fsSL https://claude.ai/install.sh | bash", "~/.claude/downloads"),
    "codex": Agent("codex", "Codex", "codex", "~/.codex/hooks.json",
                   {"UserPromptSubmit": "start", "Stop": "stop", "PermissionRequest": "attention"},
                   "curl -fsSL https://chatgpt.com/codex/install.sh | sh", None,
                   {"ps": "procps"}),  # to track its background server
    "gemini": Agent("gemini", "Gemini CLI", "gemini", "~/.gemini/settings.json",
                    {"BeforeAgent": "start", "AfterAgent": "stop", "Notification": "attention"},
                    "npm install -g @google/gemini-cli"),
}

GET_NODE = "apt-get update && apt-get install -y nodejs npm"
MIN_NODE = 20  # Gemini CLI's minimum


def apt_install(packages):
    return [f"apt-get update && apt-get install -y {' '.join(packages)}"] if packages else []


def needed_packages(agent, ps):
    """Packages for the commands in [agent].needs that are missing ([ps]:
    whether ps is there; the only one so far)."""
    have = {"ps": ps}
    return [package for command, package in agent.needs.items() if not have.get(command, True)]


def missing_steps(agent, ps):
    """Shell commands that get what an installed [agent] still needs."""
    return apt_install(needed_packages(agent, ps))


def install_steps(agent, curl, node, ps=True):
    """Shell commands that install [agent]. [curl], [ps]: whether those
    commands are there; [node]: Node.js's major version, or None."""
    if agent.installer.startswith("npm "):
        node_steps = [] if node is not None and node >= MIN_NODE else [GET_NODE]
        return node_steps + missing_steps(agent, ps) + [agent.installer]
    packages = ([] if curl else ["curl", "ca-certificates"]) + needed_packages(agent, ps)
    return apt_install(packages) + [agent.installer]


def download_progress(title, before, size, showing):
    """What to write after the download folder went from [before] to [size]
    bytes, and whether a progress line shows then. The line is cleared as
    soon as the download stops growing, before the installer talks again."""
    if size > before:
        return f"\r\x1b[K  Downloading {title}: {size // 1_000_000} MB", True
    return ("\r\x1b[K" if showing else ""), False


def hook_command(agent):
    # The full path: an agent may run hooks without the app's PATH.
    return f"{os.path.join(TOOLS, 'bin', 'pocket')} hook {agent.name}"


def is_ours(handler, agent):
    command = handler.get("command") if isinstance(handler, dict) else None
    return isinstance(command, str) and (command == f"pocket hook {agent.name}"
                                         or command.endswith(f"/pocket hook {agent.name}"))


def load(text, agent):
    if text is None or not text.strip():
        return {}
    try:
        config = json.loads(text)
    except ValueError as e:
        raise Failure(f"{agent.config} isn't valid JSON ({e}); fix it first")
    hooks = config.get("hooks", {}) if isinstance(config, dict) else None
    if not isinstance(hooks, dict) or not all(isinstance(groups, list) for groups in hooks.values()):
        raise Failure(f"{agent.config} isn't laid out as expected; fix it first")
    return config


def dump(config):
    return json.dumps(config, indent=2, ensure_ascii=False) + "\n"


def has_hooks(text, agent):
    """Whether every event of [agent] has our hook in config [text]."""
    hooks = load(text, agent).get("hooks", {})
    return all(any(is_ours(h, agent) for group in hooks.get(event, []) if isinstance(group, dict)
                   for h in group.get("hooks", []))
               for event in agent.events)


def add_hooks(text, agent):
    """Config [text] (None: no file yet) with our hook on each event."""
    config = load(text, agent)
    hooks = config.setdefault("hooks", {})
    for event in agent.events:
        groups = hooks.setdefault(event, [])
        if not any(is_ours(h, agent) for group in groups if isinstance(group, dict)
                   for h in group.get("hooks", [])):
            groups.append({"hooks": [{"type": "command", "command": hook_command(agent)}]})
    return dump(config)


def remove_hooks(text, agent):
    """Config [text] without our hooks; groups, events and "hooks" left
    empty by that go too."""
    config = load(text, agent)
    hooks = config.get("hooks", {})
    for event in list(hooks):
        kept = []
        for group in hooks[event]:
            if isinstance(group, dict) and isinstance(group.get("hooks"), list):
                handlers = [h for h in group["hooks"] if not is_ours(h, agent)]
                if not handlers and group["hooks"]:
                    continue
                group = {**group, "hooks": handlers}
            kept.append(group)
        if kept:
            hooks[event] = kept
        else:
            del hooks[event]
    if "hooks" in config and not hooks:
        del config["hooks"]
    return dump(config)


def config_path(agent):
    return os.path.expanduser(agent.config)


def read_config(agent):
    try:
        with open(config_path(agent)) as f:
            return f.read()
    except FileNotFoundError:
        return None


def status(agent):
    """What `pocket agent list` shows about [agent]."""
    installed = bool(shutil.which(agent.command)) or os.path.exists(
        os.path.expanduser(f"~/.local/bin/{agent.command}"))
    try:
        notify = has_hooks(read_config(agent), agent)
    except Failure:
        notify = False
    return {"name": agent.name, "title": agent.title, "installed": installed, "notify": notify,
            "config": agent.config}


def set_notify(agent, on):
    """Adds or removes our hooks in the agent's config."""
    text = read_config(agent)
    if text is None and not on:
        return
    new = add_hooks(text, agent) if on else remove_hooks(text, agent)
    path = config_path(agent)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path + ".tmp", "w") as f:
        f.write(new)
    os.replace(path + ".tmp", path)
