#!/usr/bin/env python3
"""Build the offline knowledge pack bundled with Pocket Scholar.

Source: Wikipedia's own "vital articles" curriculum — Level 3 (1,000 core
articles) plus Level 4 (10,000, split by topic). These are the articles
Wikipedia editors consider essential, which is exactly the shape a research
assistant needs: science, history, geography, technology, philosophy.

Output: one JSON object per line, each with "title", "description", "extract".

    python3 scripts/build_knowledge_pack.py app/src/main/assets/knowledge/pack.jsonl
"""
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://en.wikipedia.org/w/api.php"
UA = {"User-Agent": "PocketScholarBot/1.0 "
                    "(https://github.com/9xj89gzrtw-hue/pocket-scholar; "
                    "9xj89gzrtw-hue@users.noreply.github.com)"}
SKIP = re.compile(r"^(List of|Lists of|Timeline of|Index of|Outline of)", re.I)


def get_json(url: str, attempts: int = 6):
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(request, timeout=45) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as exc:
            if exc.code != 429 or attempt == attempts - 1:
                raise
            wait = 5 * (attempt + 1)
            print(f"429, sleeping {wait}s", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError("unreachable")


def api(params: dict):
    return get_json(API + "?" + urllib.parse.urlencode(params))


def page_articles(page: str) -> list[str]:
    data = api({"action": "parse", "page": page, "prop": "links",
                "format": "json", "formatversion": "2"})
    return [link["title"] for link in data["parse"]["links"] if link.get("ns") == 0]


def vital_titles() -> list[str]:
    titles: dict[str, None] = {}
    for title in page_articles("Wikipedia:Vital articles/Level 3"):
        if not SKIP.match(title):
            titles[title] = None
    index = api({"action": "parse", "page": "Wikipedia:Vital articles/Level 4",
                 "prop": "links", "format": "json", "formatversion": "2"})
    subpages = [link["title"] for link in index["parse"]["links"]
                if link["title"].startswith("Wikipedia:Vital articles/Level 4/")]
    for subpage in subpages:
        time.sleep(0.7)
        try:
            for title in page_articles(subpage):
                if not SKIP.match(title):
                    titles[title] = None
            print(f"{subpage.split('/')[-1]}: {len(titles)} total", file=sys.stderr)
        except Exception as exc:
            print(f"skip {subpage}: {exc}", file=sys.stderr)
    return list(titles)


def summaries(titles: list[str]):
    for start in range(0, len(titles), 50):
        batch = titles[start:start + 50]
        params = urllib.parse.urlencode({
            "action": "query", "format": "json", "formatversion": "2",
            "prop": "extracts", "exintro": "1", "explaintext": "1", "redirects": "1",
            "titles": "|".join(batch),
        })
        try:
            data = get_json(f"{API}?{params}")
        except Exception as exc:
            print(f"batch {start}: giving up: {exc}", file=sys.stderr)
            time.sleep(10)
            continue
        for page in data.get("query", {}).get("pages", []):
            extract = (page.get("extract") or "").strip()
            if page.get("missing") or len(extract) < 80:
                continue
            # disambiguation pages are noise in a research corpus
            if extract.lower().startswith("may refer to") or "can refer to" in extract[:120].lower():
                continue
            yield {"title": page.get("title", ""),
                   "description": "",
                   "extract": extract[:1400]}
        time.sleep(1.2)


def main() -> None:
    out_path = sys.argv[1] if len(sys.argv) > 1 else None
    titles = vital_titles()
    print(f"titles: {len(titles)}", file=sys.stderr)
    stream = open(out_path, "w", encoding="utf-8") if out_path else sys.stdout
    written = 0
    try:
        for item in summaries(titles):
            stream.write(json.dumps(item, ensure_ascii=False) + "\n")
            stream.flush()
            written += 1
    finally:
        if out_path:
            stream.close()
    print(f"articles: {written}", file=sys.stderr)


if __name__ == "__main__":
    main()
