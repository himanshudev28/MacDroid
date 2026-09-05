#!/usr/bin/env python3
"""Second extraction pass: conditional expressions and object literals.

Pass 1 handled props and JSX text, both of which are unambiguous. This one
handles `cond ? "A" : "B"` and `label: "…"`, which are not — a Tailwind class
list is also a string in a ternary, and `className={t("bg-red-500")}`
**typechecks perfectly** while silently breaking the styling. TypeScript is no
safety net here, so the guards are.

Anything on a line mentioning className/style, or containing CSS punctuation, is
refused outright. Under-extracting leaves an English string; over-extracting
breaks the app in a way nothing catches.
"""
import re, sys, io, os

SRC = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src")

# Refuse the whole line if it is styling.
LINE_VETO = re.compile(r'className|style=|gradient|cva\(|clsx\(|\bcn\(')
# Refuse the string itself if it looks like CSS, a path, a wire value or a key.
STR_VETO = re.compile(r'--|var\(|rgba?\(|\bpx\b|[\[\]{}<>`$]|^\s|\s$|^/|\bbg-|\btext-\[|\bh-\d|\bw-\d')

def is_display(s: str) -> bool:
    if len(s) < 4 or STR_VETO.search(s):
        return False
    words = [w for w in s.split() if re.fullmatch(r"[A-Za-z][A-Za-z'’,.!?()—-]*", w)]
    # Two real words is the bar: it excludes identifiers, enum values, mime
    # types and single Tailwind tokens, and admits ordinary sentences.
    return len(words) >= 2

def wrap(code: str) -> tuple[str, int]:
    n = 0
    out = []
    for line in code.splitlines(keepends=True):
        if LINE_VETO.search(line):
            out.append(line)
            continue
        def repl(m):
            nonlocal n
            before, text = m.group(1), m.group(2)
            if not is_display(text) or re.search(r'\bt\(\s*$', before):
                return m.group(0)
            n += 1
            return f'{before}t("{text}")'
        # After `? `, `: `, or an object key like `label: `.
        line = re.sub(r'((?:\?|:|,)\s*)"((?:[^"\\]|\\.)*)"', repl, line)
        out.append(line)
    return "".join(out), n

def main(path: str) -> None:
    code = io.open(path, encoding="utf-8").read()
    new, n = wrap(code)
    if n == 0:
        return
    if not re.search(r'\bfrom "[^"]*lib/i18n"', new):
        rel = os.path.relpath(os.path.join(SRC, "lib", "i18n"), os.path.dirname(path))
        if not rel.startswith("."):
            rel = "./" + rel
        imports = list(re.finditer(r'^import .*?;\n', new, re.M))
        at = imports[-1].end() if imports else 0
        new = new[:at] + f'import {{ t }} from "{rel}";\n' + new[at:]
    io.open(path, "w", encoding="utf-8").write(new)
    print(f"{path}: wrapped {n}")

if __name__ == "__main__":
    for p in sys.argv[1:]:
        main(p)
