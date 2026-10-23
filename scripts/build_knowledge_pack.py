#!/usr/bin/env python3
"""Build the offline knowledge pack bundled with Pocket Scholar.

Source: Wikipedia pageview top-1000 lists for the last 12 months (what people
actually look up), then intro extracts in batches of 50.

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

MONTHS = [(2025, 10), (2025, 11), (2025, 12)] + [(2026, m) for m in range(1, 10)]
SKIP = re.compile(r"^(Main_Page|Special:|Wikipedia:|Portal:|File:|Help:|Category:|"
                  r"Template:|Talk:|Draft:|Module:|MediaWiki:|Deaths_in_)", re.I)
UA = {"User-Agent": "PocketScholarBot/1.0 "
                    "(https://github.com/9xj89gzrtw-hue/pocket-scholar; "
                    "9xj89gzrtw-hue@users.noreply.github.com)"}


def get_json(url: str, attempts: int = 6):
    for attempt in range(attempts):
        try:
            request = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(request, timeout=40) as response:
                return json.loads(response.read())
        except urllib.error.HTTPError as exc:
            if exc.code != 429 or attempt == attempts - 1:
                raise
            wait = 5 * (attempt + 1)
            print(f"429, sleeping {wait}s", file=sys.stderr)
            time.sleep(wait)
    raise RuntimeError("unreachable")


def top_titles() -> list[str]:
    seen, out = set(), []
    for year, month in MONTHS:
        url = (f"https://wikimedia.org/api/rest_v1/metrics/pageviews/top/"
               f"en.wikipedia/all-access/{year}/{month:02d}/all-days")
        try:
            for article in get_json(url)["items"][0]["articles"]:
                title = article["article"].replace("_", " ")
                if SKIP.match(article["article"]) or title in seen:
                    continue
                if title.endswith(".xxx") or len(title) < 2:
                    continue
                seen.add(title)
                out.append(title)
        except Exception as exc:
            print(f"skip {year}-{month:02d}: {exc}", file=sys.stderr)
        time.sleep(0.5)
    return out


def summaries(titles: list[str]):
    for start in range(0, len(titles), 50):
        batch = titles[start:start + 50]
        params = urllib.parse.urlencode({
            "action": "query", "format": "json", "formatversion": "2",
            "prop": "extracts", "exintro": "1", "explaintext": "1", "redirects": "1",
            "titles": "|".join(batch),
        })
        try:
            data = get_json("https://en.wikipedia.org/w/api.php?" + params)
        except Exception as exc:
            print(f"batch {start}: giving up: {exc}", file=sys.stderr)
            time.sleep(10)
            continue
        for page in data.get("query", {}).get("pages", []):
            extract = (page.get("extract") or "").strip()
            if page.get("missing") or len(extract) < 80:
                continue
            yield {"title": page.get("title", ""),
                   "description": "",
                   "extract": extract[:1400]}
        time.sleep(1.2)


def main() -> None:
    out_path = sys.argv[1] if len(sys.argv) > 1 else None
    titles = top_titles()
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
