#!/usr/bin/env bash
# The new settings (Set*.java): SetLayout's geometry on the plain JDK, what the classic view does that the new pages
# must do too, and the habits that keep a fault from reaching the home screen's watchdog.
set -euo pipefail
cd "$(dirname "$0")/.."
src=app/src/io/freemytv

# 1. SetLayout is pure Java: rows, scroll, fades and hit-testing, without a TV.
if grep -nE "^import (android|org\.json)\." "$src/SetLayout.java"; then
  echo "SetLayout.java must not use android.* or org.json" >&2
  exit 1
fi
OUT="${SETTINGS_TEST_OUT:-out/settings-tests}"
mkdir -p "$OUT"
"${JAVA_HOME:+$JAVA_HOME/bin/}javac" -encoding UTF-8 -d "$OUT" "$src/SetLayout.java" tests/SetLayoutTest.java
"${JAVA_HOME:+$JAVA_HOME/bin/}java" -cp "$OUT" io.freemytv.SetLayoutTest

# 2. Every setting, capability and page of the classic view has its place in the new one.
python3 tools/settings_coverage.py

# 3. Hygiene. The new files (SettingsActivity.java, the host and the classic view, is not one of them).
new=()
for f in "$src"/Set*.java; do
  if [ "$(basename "$f")" != SettingsActivity.java ]; then new+=("$f"); fi
done
# No stock dialogs or toasts (SetSheet draws them), no exception text on screen or in the log (a key or a URL may be
# in it), no bare threads (Safe.IO, wrapped). Comment lines may name them.
if grep -nE 'AlertDialog|Toast\.makeText|getMessage|printStackTrace|new Thread' "${new[@]}" \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|/?\*)'; then
  echo "Set*.java: a stock dialog or toast, an exception's text, or a bare thread (above)." >&2
  exit 1
fi
# Nothing allocated, translated or measured while drawing: SetView's draw code sits between two marker comments.
if [ "$(grep -c 'settings-draw-begin' "$src/SetView.java")" != 1 ] || [ "$(grep -c 'settings-draw-end' "$src/SetView.java")" != 1 ]; then
  echo "SetView.java: the settings-draw-begin / settings-draw-end markers are missing." >&2
  exit 1
fi
if awk '/settings-draw-begin/{on=1} on{print FILENAME ":" FNR ":" $0} /settings-draw-end/{on=0}' "$src/SetView.java" \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|/?\*)' \
    | grep -E '(^|[^[:alnum:]_])new[[:space:]]|Ui\.(tr|trf|plural)\(|measureText|ellipsize'; then
  echo "SetView.java: the draw code allocates, translates or measures (above)." >&2
  exit 1
fi
# The app's name comes from Ui.APP_NAME / Ui.APP_SHORT, never from a literal in the new files.
if grep -nE '"[^"]*Free My TV[^"]*"' "${new[@]}" | grep -vE 'Ui\.(tr|trf|plural)\("' \
    | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|/?\*)'; then
  echo "Set*.java: the app's name as a literal (above): use Ui.APP_NAME or Ui.APP_SHORT." >&2
  exit 1
fi

# 4. Every text of the new files has its Brazilian Portuguese (translate.py --check covers all 25 catalogues).
python3 - "${new[@]}" <<'PY'
import json, os, sys
sys.dont_write_bytecode = True
sys.path.insert(0, "tools")
import translate as T
pt = T.load("pt")
wanted = set(os.path.abspath(p) for p in sys.argv[1:])
missing, n = [], 0
for path, line, kind, args in T.java_calls():
    if os.path.abspath(path) not in wanted or args[0] is None:
        continue
    n += 1
    if args[0] not in pt:
        missing.append("%s:%s: %s" % (path, line, args[0]))
    elif "Free My TV" in pt[args[0]]:
        missing.append("%s:%s: pt names the app \"Free My TV\": %s" % (path, line, args[0]))
for m in missing:
    print("pt.json lacks " + m, file=sys.stderr)
if missing or n == 0:
    sys.exit("test-settings: %d texts of Set*.java checked, %d without Portuguese" % (n, len(missing)))
print("Settings texts: %d, all in pt.json" % n)
PY
echo "Settings tests passed."
