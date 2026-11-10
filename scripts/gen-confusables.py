#!/usr/bin/env python3
"""Makes app/src/main/resources/confusables.txt from Unicode's confusables list, for Portal's name checks.

  curl -O https://www.unicode.org/Public/security/latest/confusables.txt
  python3 scripts/gen-confusables.py confusables.txt

Keeps the lines where one character (not an ASCII letter or digit itself) is drawn like Latin letters or digits, as "HEX target" (the target in
lower case). Everything else in the list (look-alikes of punctuation, of other scripts) is not needed to catch a name that
passes for a Latin word. Data: Unicode Consortium, https://www.unicode.org/Public/security/latest/confusables.txt, used under
the Unicode License (https://www.unicode.org/terms_of_use.html).
"""
import re, sys, pathlib

src = pathlib.Path(sys.argv[1]).read_text(encoding="utf-8-sig")
version = re.search(r"# Date: (.*)", src)
rows = {}
for line in src.splitlines():
    if not line or line.startswith("#"):
        continue
    parts = [p.strip() for p in line.split(";")]
    if len(parts) < 2:
        continue
    source = [int(h, 16) for h in parts[0].split()]
    target = "".join(chr(int(h, 16)) for h in parts[1].split())
    if len(source) != 1:
        continue
    # A letter or digit of ASCII is not a look-alike of anything here (Portal reads i, l and 1 as one letter itself), but a
    # symbol that is drawn like a letter ("|" like l) is kept.
    if source[0] < 0x80 and chr(source[0]).isalnum():
        continue
    if not re.fullmatch(r"[A-Za-z0-9]{1,3}", target):
        continue
    rows[source[0]] = target.lower()

out = pathlib.Path(__file__).resolve().parent.parent / "app/src/main/resources/confusables.txt"
out.parent.mkdir(parents=True, exist_ok=True)
header = [
    "# Characters that look like Latin letters or digits, made from Unicode's confusables.txt by scripts/gen-confusables.py.",
    f"# Source: https://www.unicode.org/Public/security/latest/confusables.txt ({version.group(1).strip() if version else 'unknown date'}).",
    "# (c) Unicode, Inc. Used under the Unicode License: https://www.unicode.org/terms_of_use.html",
    "# Format: HEX_CODE_POINT target_in_lower_case",
]
out.write_text("\n".join(header + [f"{cp:X} {t}" for cp, t in sorted(rows.items())]) + "\n", encoding="utf-8")
print(f"{len(rows)} characters written to {out}")
