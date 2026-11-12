#!/usr/bin/env python3
"""Checks that the new settings (app/src/io/freemytv/Set*.java) cover what the classic view does.

    python3 tools/settings_coverage.py

The classic view (SettingsActivity.SettingsView) stays in the app as the fallback, so it is the reference:
  1. every call of it that changes something (Prefs.set*, HomeMode.*, ModeJob.schedule, SleepTimer.*,
     SleepOverlay.update, settingsChanged, Trakt.*, Photos.*, ImmichPhotos.*) is also made by the new code (Set*.java, or the activity around it), except
     the ones in ALLOWED, each with its reason;
  2. every Capabilities.NEED_* the classic view asks about is asked about by the new pages;
  3. every page id SetPages documents is built by one of the page files.
Exits 1 with the list of what is missing.
"""
import glob
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "app", "src", "io", "freemytv")

CALLS = re.compile(
    r"\b(Prefs\.set\w+|HomeMode\.\w+(?=\s*\()|ModeJob\.schedule|SleepTimer\.\w+(?=\s*\()|SleepOverlay\.update"
    r"|settingsChanged|Trakt\.\w+(?=\s*\()|Photos\.\w+(?=\s*\()|ImmichPhotos\.\w+(?=\s*\())")
NEED = re.compile(r"\bCapabilities\.(NEED_\w+)")

# Calls of the classic view the new code has no use for: {call: why}.
ALLOWED = {
    "HomeMode.phone": "Capabilities.missing(NEED_SWITCH) asks it and gives the reason (SPEC3 3.5-9)",
    "HomeMode.phoneText": "the same: the root row's reason comes from Capabilities",
}


def read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


def code(text):
    """The text without comments (string literals stay: an id is one)."""
    text = re.sub(r"/\*.*?\*/", " ", text, flags=re.S)
    return re.sub(r"(?m)^\s*//.*$|(?<=[;{}\s])//[^\n\"]*$", " ", text)


def main():
    activity = read(os.path.join(SRC, "SettingsActivity.java"))
    at = activity.find("static final class SettingsView ")
    if at < 0:
        print("settings_coverage: SettingsActivity.SettingsView not found", file=sys.stderr)
        return 1
    legacy = code(activity[at:])
    host = code(activity[:at])   # the activity around either screen (it imports the photos for the new one)
    files = sorted(glob.glob(os.path.join(SRC, "Set*.java")))
    files = [f for f in files if os.path.basename(f) != "SettingsActivity.java"]
    # A method reference changes something as a call does: Prefs::setPowerOn reads as Prefs.setPowerOn(
    new = {os.path.basename(f): re.sub(r"\b(\w+)::(\w+)", r"\1.\2(", code(read(f))) for f in files}
    everything = "\n".join(new.values()) + "\n" + host
    errors = []

    made = set(CALLS.findall(everything))
    for call in sorted(set(CALLS.findall(legacy))):
        if call not in made and call not in ALLOWED:
            errors.append("the classic view calls %s; no Set*.java does" % call)
    for call in sorted(ALLOWED):
        if call in made:
            errors.append("%s is in ALLOWED but the new code calls it: remove it from the list" % call)

    asked = set(NEED.findall(everything))
    for need in sorted(set(NEED.findall(legacy))):
        if need not in asked:
            errors.append("the classic view asks Capabilities.%s; no Set*.java does" % need)

    pages = read(os.path.join(SRC, "SetPages.java"))
    doc = re.search(r"/\*\*(.*?)\*/\s*static SetPage build", pages, flags=re.S)
    ids = []
    if doc:
        text = re.sub(r"\([^)]*\)", " ", doc.group(1).split("Ids:", 1)[-1])
        ids = re.findall(r"[A-Za-z][\w.]*", text.replace("*", " "))
    if len(ids) < 20:
        errors.append("SetPages.java: the documented id list wasn't found (found %d ids)" % len(ids))
    builders = "\n".join(text for name, text in new.items() if name not in ("SetPages.java", "SetView.java"))
    for page in ids:
        if '"%s"' % page not in builders:
            errors.append('page "%s" is documented in SetPages but no page file builds it' % page)

    for e in errors:
        print("settings_coverage: " + e, file=sys.stderr)
    if errors:
        return 1
    print("settings_coverage: OK (%d calls, %d needs, %d pages)"
          % (len(set(CALLS.findall(legacy))), len(set(NEED.findall(legacy))), len(ids)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
