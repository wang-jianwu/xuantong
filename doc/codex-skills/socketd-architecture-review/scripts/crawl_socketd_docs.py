#!/usr/bin/env python3
"""Crawl the official Socket.D site and build a verifiable Markdown corpus."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
import time
from collections import deque
from html import unescape
from html.parser import HTMLParser
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urljoin, urlsplit, urlunsplit
from urllib.request import Request, urlopen


USER_AGENT = "socketd-architecture-review/1.0 (+https://socketd.noear.org)"
DEFAULT_SEEDS = ("/", "/article/about-qa", "/article/learn-start", "/article/cases")


class LinkParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.links: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag.lower() != "a":
            return
        href = dict(attrs).get("href")
        if href:
            self.links.append(href)


def fetch(url: str, retries: int, timeout: float) -> str:
    last_error: Exception | None = None
    for attempt in range(retries + 1):
        try:
            request = Request(url, headers={"User-Agent": USER_AGENT})
            with urlopen(request, timeout=timeout) as response:
                charset = response.headers.get_content_charset() or "utf-8"
                return response.read().decode(charset, errors="replace")
        except (HTTPError, URLError, TimeoutError) as exc:
            last_error = exc
            if attempt < retries:
                time.sleep(min(2**attempt, 4))
    raise RuntimeError(f"failed to fetch {url}: {last_error}")


def normalize_article_url(base: str, href: str) -> str | None:
    absolute = urljoin(base, href)
    parts = urlsplit(absolute)
    base_parts = urlsplit(base)
    if parts.scheme not in {"http", "https"} or parts.netloc != base_parts.netloc:
        return None
    if not parts.path.startswith("/article/"):
        return None
    path = re.sub(r"/+", "/", parts.path).rstrip("/")
    if not path or path == "/article":
        return None
    return urlunsplit((base_parts.scheme, base_parts.netloc, path, "", ""))


def safe_name(url: str) -> str:
    slug = urlsplit(url).path.removeprefix("/article/")
    slug = re.sub(r"[^a-zA-Z0-9._-]+", "-", slug).strip("-") or "index"
    return f"{slug}.md"


def title_from_markdown(markdown: str, fallback: str) -> str:
    frontmatter = re.search(r'^---\s*\n.*?^title:\s*["\']?(.*?)["\']?\s*$.*?^---\s*$', markdown, re.M | re.S)
    if frontmatter:
        return frontmatter.group(1).strip()
    heading = re.search(r"^#{1,6}\s+(.+?)\s*$", markdown, re.M)
    return heading.group(1).strip() if heading else fallback


def title_from_html(html: str) -> str | None:
    match = re.search(r"<article\b.*?<header\b.*?<h1\b[^>]*>(.*?)</h1>", html, re.I | re.S)
    if match is None:
        return None
    title = re.sub(r"<[^>]+>", "", match.group(1))
    title = re.sub(r"\s+", " ", unescape(title)).strip()
    return title or None


def write_markdown_index(output: Path, result: dict) -> None:
    lines = [
        "# Socket.D official documentation crawl index",
        "",
        f"- Base: {result['base']}",
        f"- HTML pages visited: {result['html_pages_visited']}",
        f"- Articles discovered: {result['articles_discovered']}",
        f"- Articles downloaded: {result['articles_downloaded']}",
        f"- Failures: {len(result['failures'])}",
        "",
        "| # | Title | Official page | Characters | SHA-256 |",
        "|---:|---|---|---:|---|",
    ]
    for idx, record in enumerate(result["records"], 1):
        title = str(record["title"]).replace("|", "\\|")
        lines.append(
            f"| {idx} | {title} | [{record['url']}]({record['url']}) | "
            f"{record['characters']} | `{record['sha256']}` |"
        )
    if result["failures"]:
        lines.extend(["", "## Failures", ""])
        for failure in result["failures"]:
            lines.append(f"- {failure['url']}: {failure['error']}")
    (output / "index.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def crawl(base: str, output: Path, retries: int, timeout: float, max_pages: int) -> dict:
    base = base.rstrip("/") + "/"
    queue = deque(urljoin(base, seed.lstrip("/")) for seed in DEFAULT_SEEDS)
    seen_html: set[str] = set()
    article_urls: set[str] = set()
    article_titles: dict[str, str] = {}
    failures: list[dict[str, str]] = []

    while queue:
        page_url = queue.popleft()
        if page_url in seen_html:
            continue
        seen_html.add(page_url)
        try:
            html = fetch(page_url, retries, timeout)
        except RuntimeError as exc:
            failures.append({"url": page_url, "error": str(exc)})
            continue

        parser = LinkParser()
        parser.feed(html)
        for href in parser.links:
            article_url = normalize_article_url(base, href)
            if article_url is None:
                continue
            if article_url not in article_urls:
                article_urls.add(article_url)
                if len(article_urls) > max_pages:
                    raise RuntimeError(f"article limit exceeded ({max_pages}); inspect the site before increasing it")
            if article_url not in seen_html:
                queue.append(article_url)

        current_article_url = normalize_article_url(base, page_url)
        if current_article_url is not None:
            page_title = title_from_html(html)
            if page_title:
                article_titles[current_article_url] = page_title

    output.mkdir(parents=True, exist_ok=True)
    pages_dir = output / "pages"
    pages_dir.mkdir(exist_ok=True)
    records: list[dict[str, object]] = []

    for article_url in sorted(article_urls):
        markdown_url = f"{article_url}?format=md"
        try:
            markdown = fetch(markdown_url, retries, timeout).strip() + "\n"
        except RuntimeError as exc:
            failures.append({"url": markdown_url, "error": str(exc)})
            continue
        filename = safe_name(article_url)
        (pages_dir / filename).write_text(markdown, encoding="utf-8")
        records.append(
            {
                "url": article_url,
                "markdown_url": markdown_url,
                "file": f"pages/{filename}",
                "title": article_titles.get(
                    article_url, title_from_markdown(markdown, urlsplit(article_url).path)
                ),
                "characters": len(markdown),
                "sha256": hashlib.sha256(markdown.encode("utf-8")).hexdigest(),
            }
        )

    result = {
        "base": base,
        "seed_urls": [urljoin(base, seed.lstrip("/")) for seed in DEFAULT_SEEDS],
        "html_pages_visited": len(seen_html),
        "articles_discovered": len(article_urls),
        "articles_downloaded": len(records),
        "records": records,
        "failures": failures,
    }
    (output / "index.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    write_markdown_index(output, result)
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base", default="https://socketd.noear.org/")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--retries", type=int, default=2)
    parser.add_argument("--timeout", type=float, default=20.0)
    parser.add_argument("--max-pages", type=int, default=500)
    args = parser.parse_args()

    try:
        result = crawl(args.base, args.output, args.retries, args.timeout, args.max_pages)
    except Exception as exc:  # surface a concise deterministic failure to callers
        print(str(exc), file=sys.stderr)
        return 1

    print(json.dumps({k: result[k] for k in ("html_pages_visited", "articles_discovered", "articles_downloaded", "failures")}, ensure_ascii=False, indent=2))
    return 0 if not result["failures"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
