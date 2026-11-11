import re

import nh3

_WORD_RE = re.compile(r"\w+(?:['’-]\w+)*")
_TAG_RE = re.compile(r"<[^>]+>")

_ATTRIBUTES = {tag: set(values) for tag, values in nh3.ALLOWED_ATTRIBUTES.items()}
_ATTRIBUTES.setdefault("*", set()).update({"align", "dir", "lang", "title"})


def clean_html(html: str | None) -> str:
    if not html:
        return ""
    return nh3.clean(html, attributes=_ATTRIBUTES)


def count_words(html: str | None) -> int:
    if not html:
        return 0
    return len(_WORD_RE.findall(_TAG_RE.sub(" ", html)))
