#!/usr/bin/env python3
"""Wrap user-facing literals in t(), conservatively.

Only touches shapes that are unambiguously display text:

  * JSX text nodes      >Some words<
  * known string props  title= label= hint= placeholder= aria-label= body= title=
  * toast/say calls     toast("ok", "Some words")   say("Some words")

Everything else is left alone on purpose. className, key, id, invoke() command
names, event channel names, Tailwind classes and type literals are all string
literals too, and wrapping any of them would break the app in a way tsc cannot
see. Under-extracting is a missing translation; over-extracting is a bug.
"""
import re, sys, io, os

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src")

# A word-bearing display string: starts with a letter or digit, contains a
# lowercase run somewhere (excludes SCREAMING_CASE constants and single glyphs),
# and no template/JSX interpolation.
DISPLAY = re.compile(r'^(?=.*[a-z]{2})[^{}<>\\`]*$')

PROPS = ("title", "label", "hint", "placeholder", "aria-label", "body", "subtitle", "confirmLabel")

def is_display(text: str) -> bool:
    t = text.strip()
    if len(t) < 2 or not DISPLAY.match(t):
        return False
    # Tailwind-ish and path-ish strings sneak past the letter test.
    if re.fullmatch(r'[a-z0-9:_\-/\. ]+', t) and (' ' not in t or '/' in t):
        return False
    return any(c.isalpha() for c in t)

def wrap_props(code: str) -> tuple[str, int]:
    n = 0
    def repl(m):
        nonlocal n
        prop, quote, text = m.group(1), m.group(2), m.group(3)
        if not is_display(text):
            return m.group(0)
        n += 1
        return f'{prop}={{t("{text}")}}'
    pattern = re.compile(r'\b(' + "|".join(re.escape(p) for p in PROPS) + r')=(")((?:[^"\\]|\\.)*)"')
    return pattern.sub(repl, code), n

def wrap_jsx_text(code: str) -> tuple[str, int]:
    n = 0
    def repl(m):
        nonlocal n
        lead, text, trail = m.group(1), m.group(2), m.group(3)
        if not is_display(text):
            return m.group(0)
        n += 1
        return f'{lead}{{t("{text.strip()}")}}{trail}'
    # >text< with nothing but plain words between the tags.
    pattern = re.compile(r'(>)(\s*[A-Za-z][^<>{}"\n]*?)(\s*</)')
    return pattern.sub(repl, code), n

def wrap_calls(code: str) -> tuple[str, int]:
    n = 0
    def repl(m):
        nonlocal n
        head, text = m.group(1), m.group(2)
        if not is_display(text):
            return m.group(0)
        n += 1
        return f'{head}t("{text}")'
    pattern = re.compile(r'((?:toast|say|onToast\??\.?)\(\s*(?:"(?:ok|bad|info)"\s*,\s*)?)"((?:[^"\\]|\\.)*)"')
    return pattern.sub(repl, code), n

def add_import(path: str, code: str) -> str:
    """Import `t` if the file now uses it and doesn't already have it."""
    if re.search(r'\bfrom "[^"]*lib/i18n"', code):
        return code
    import os
    rel = os.path.relpath(os.path.join(SRC, "lib", "i18n"), os.path.dirname(path))
    if not rel.startswith("."):
        rel = "./" + rel
    line = f'import {{ t }} from "{rel}";\n'
    # After the last top-of-file import, so the block stays contiguous.
    imports = list(re.finditer(r'^import .*?;\n', code, re.M))
    if not imports:
        return line + code
    at = imports[-1].end()
    return code[:at] + line + code[at:]


def main(path: str) -> None:
    code = io.open(path, encoding="utf-8").read()
    original = code
    total = 0
    for fn in (wrap_props, wrap_jsx_text, wrap_calls):
        code, n = fn(code)
        total += n
    if code == original:
        print(f"{path}: nothing to wrap")
        return
    code = add_import(path, code)
    io.open(path, "w", encoding="utf-8").write(code)
    print(f"{path}: wrapped {total}")

if __name__ == "__main__":
    for p in sys.argv[1:]:
        main(p)
