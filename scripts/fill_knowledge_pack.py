#!/usr/bin/env python3
"""Fill gaps in an existing knowledge pack without refetching what is there.

    python3 scripts/fill_knowledge_pack.py app/src/main/assets/knowledge/pack.jsonl

Reads the titles already present, recomputes the Vital Articles list, and fetches
only the missing ones, appending as it goes.
"""
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

API = "https://en.wikipedia.org/w/api.php"
UA = {"User-Agent": "PocketScholarBot/1.0 "
                    "(https://github.com/9xj89gzrtw-hue/pocket-scholar; "
                    "9xj89gzrtw-hue@users.noreply.github.com)"}


def get_json(url: str, attempts: int = 10):
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(request, timeout=60) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as exc:
            if exc.code != 429 or attempt == attempts - 1:
                raise
            wait = min(60, 5 * (attempt + 1))
            print(f"429, sleeping {wait}s", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError("unreachable")


def api(params: dict):
    return get_json(API + "?" + urllib.parse.urlencode(params))


def page_articles(page: str):
    data = api({"action": "parse", "page": page, "prop": "links",
                "format": "json", "formatversion": "2"})
    return [link["title"] for link in data["parse"]["links"] if link.get("ns") == 0]


def vital_titles():
    titles = []
    titles += page_articles("Wikipedia:Vital articles/Level 3")
    index = api({"action": "parse", "page": "Wikipedia:Vital articles/Level 4",
                 "prop": "links", "format": "json", "formatversion": "2"})
    for link in index["parse"]["links"]:
        if not link["title"].startswith("Wikipedia:Vital articles/Level 4/"):
            continue
        time.sleep(1.0)
        try:
            titles += page_articles(link["title"])
        except Exception as exc:
            print(f"skip {link['title']}: {exc}", file=sys.stderr)
    return list(dict.fromkeys(titles))


def main() -> None:
    path = sys.argv[1]
    have = set()
    with open(path, encoding="utf-8") as stream:
        for line in stream:
            try:
                have.add(json.loads(line)["title"])
            except Exception:
                pass
    wanted = vital_titles()
    missing = [t for t in wanted if t not in have]
    print(f"have {len(have)}, want {len(wanted)}, missing {len(missing)}", file=sys.stderr)

    added = 0
    with open(path, "a", encoding="utf-8") as out:
        for start in range(0, len(missing), 50):
            batch = missing[start:start + 50]
            params = urllib.parse.urlencode({
                "action": "query", "format": "json", "formatversion": "2",
                "prop": "extracts", "exintro": "1", "explaintext": "1", "redirects": "1",
                "titles": "|".join(batch),
            })
            try:
                data = get_json(f"{API}?{params}")
            except Exception as exc:
                print(f"batch {start}: {exc}", file=sys.stderr)
                time.sleep(15)
                continue
            for page in data.get("query", {}).get("pages", []):
                extract = (page.get("extract") or "").strip()
                if page.get("missing") or len(extract) < 80:
                    continue
                if extract.lower().startswith("may refer to") or "can refer to" in extract[:120].lower():
                    continue
                out.write(json.dumps({"title": page.get("title", ""), "description": "",
                                      "extract": extract[:1400]}, ensure_ascii=False) + "\n")
                added += 1
            out.flush()
            time.sleep(2.0)
    print(f"added {added}", file=sys.stderr)


if __name__ == "__main__":
    main()
