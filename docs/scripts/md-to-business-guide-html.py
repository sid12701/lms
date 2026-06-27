#!/usr/bin/env python3
"""Convert business-workflow-and-use-cases-guide.md to styled HTML for CloudConvert PDF export."""

from __future__ import annotations

import html
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MD_PATH = ROOT / "business-workflow-and-use-cases-guide.md"
OUT_PATH = ROOT / "business-workflow-and-use-cases-guide.html"

CSS = """
@page {
  size: A4;
  margin: 18mm 20mm 22mm 20mm;
}
* { box-sizing: border-box; }
body {
  font-family: "Segoe UI", Calibri, "Helvetica Neue", Arial, sans-serif;
  font-size: 10.5pt;
  line-height: 1.55;
  color: #1a2332;
  margin: 0;
  padding: 0;
}
.cover {
  min-height: 85vh;
  display: flex;
  flex-direction: column;
  justify-content: center;
  text-align: center;
  page-break-after: always;
  padding: 2rem 1rem;
  border-bottom: 4px solid #0f2d52;
}
.cover .brand {
  font-size: 9pt;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: #5a6a7e;
  margin-bottom: 1.5rem;
}
.cover h1 {
  font-size: 28pt;
  font-weight: 700;
  color: #0f2d52;
  margin: 0 0 0.4rem;
  line-height: 1.15;
  border: none;
}
.cover .subtitle {
  font-size: 14pt;
  color: #3d4f63;
  margin: 0 0 2rem;
  font-weight: 400;
}
.cover-meta {
  margin: 0 auto;
  max-width: 520px;
  text-align: left;
  border-collapse: collapse;
  font-size: 10pt;
}
.cover-meta td {
  padding: 0.45rem 0.75rem;
  border-bottom: 1px solid #e2e8f0;
}
.cover-meta td:first-child {
  font-weight: 600;
  color: #0f2d52;
  width: 32%;
}
.toc-page {
  page-break-after: always;
  padding-top: 0.5rem;
}
.toc-page h2 {
  margin-top: 0;
}
.toc-list {
  list-style: none;
  padding: 0;
  margin: 1rem 0 0;
  columns: 1;
}
.toc-list li {
  padding: 0.35rem 0;
  border-bottom: 1px dotted #d0d7de;
  font-size: 10.5pt;
}
.toc-list a {
  color: #1a2332;
  text-decoration: none;
}
.main {
  padding: 0;
}
h1.section {
  font-size: 18pt;
  color: #0f2d52;
  margin: 2rem 0 1rem;
  padding-bottom: 0.35rem;
  border-bottom: 2px solid #0f2d52;
  page-break-after: avoid;
}
h1.section:first-of-type { margin-top: 0; }
h3 {
  font-size: 11.5pt;
  color: #1e4a7a;
  margin: 1.4rem 0 0.6rem;
  page-break-after: avoid;
}
h4 {
  font-size: 10.5pt;
  color: #2d3f54;
  margin: 1rem 0 0.4rem;
}
p { margin: 0.55rem 0; }
ul { margin: 0.4rem 0 0.8rem 1.2rem; padding: 0; }
li { margin: 0.25rem 0; }
table {
  width: 100%;
  border-collapse: collapse;
  margin: 0.8rem 0 1.2rem;
  font-size: 9.5pt;
  page-break-inside: avoid;
}
thead th {
  background: #0f2d52;
  color: #fff;
  font-weight: 600;
  text-align: left;
  padding: 0.5rem 0.6rem;
}
tbody td, tbody th {
  border: 1px solid #d0d7de;
  padding: 0.45rem 0.6rem;
  vertical-align: top;
}
tbody tr:nth-child(even) { background: #f6f8fb; }
blockquote {
  margin: 0.9rem 0;
  padding: 0.7rem 1rem;
  border-left: 4px solid #1e5a9e;
  background: #eef4fb;
  color: #1a3050;
  page-break-inside: avoid;
}
blockquote p { margin: 0; }
pre.diagram {
  background: #f4f6f9;
  border: 1px solid #d8dee6;
  border-radius: 4px;
  padding: 0.85rem 1rem;
  font-family: Consolas, "Courier New", monospace;
  font-size: 8.5pt;
  line-height: 1.4;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
  page-break-inside: avoid;
}
pre.formula {
  background: #fafbfc;
  border-left: 3px solid #1e5a9e;
  padding: 0.6rem 1rem;
  font-family: Consolas, "Courier New", monospace;
  font-size: 9pt;
  white-space: pre-wrap;
}
em.note { color: #4a5568; font-style: italic; }
strong { font-weight: 600; }
.section-break { page-break-before: always; }
hr {
  border: none;
  border-top: 1px solid #c5d4e8;
  margin: 1.5rem 0;
}
.footer {
  margin-top: 2.5rem;
  padding-top: 1rem;
  border-top: 1px solid #c5d4e8;
  text-align: center;
  font-size: 9pt;
  color: #5a6a7e;
  font-style: italic;
}
@media print {
  a { color: inherit; text-decoration: none; }
}
"""


def slug(text: str) -> str:
    text = re.sub(r"^#+\s*", "", text)
    text = text.lower()
    text = re.sub(r"[^\w\s-]", "", text)
    return re.sub(r"\s+", "-", text).strip("-")


def parse_table(lines: list[str], start: int, cover_meta: bool = False) -> tuple[str, int]:
    rows: list[list[str]] = []
    i = start
    while i < len(lines) and lines[i].strip().startswith("|"):
        row = [c.strip() for c in lines[i].strip().strip("|").split("|")]
        rows.append(row)
        i += 1
    if len(rows) < 2:
        return "", start

    align_row = rows[1]
    if not all(re.match(r"^:?-+:?$", c.replace(" ", "")) for c in align_row):
        return "", start

    header = rows[0]
    body = rows[2:]
    out = ["<table>"]
    if cover_meta or not any(header):
        out.append("<tbody>")
        for row in body:
            cells = row + [""] * max(0, 2 - len(row))
            out.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in cells[:2]) + "</tr>")
        out.append("</tbody>")
    else:
        out.append("<thead><tr>" + "".join(f"<th>{html.escape(c)}</th>" for c in header) + "</tr></thead>")
        out.append("<tbody>")
        for row in body:
            cells = row + [""] * (len(header) - len(row))
            out.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in cells[: len(header)]) + "</tr>")
        out.append("</tbody>")
    out.append("</table>")
    return "\n".join(out), i


def inline(text: str) -> str:
    text = html.escape(text)
    text = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"\*(.+?)\*", r"<em>\1</em>", text)
    text = re.sub(r"`(.+?)`", r"<code>\1</code>", text)
    return text


def parse_md(content: str) -> str:
    lines = content.splitlines()
    parts: list[str] = []
    i = 0
    in_cover = True
    cover_done = False
    toc_done = False
    skip_toc_lines = False

    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if not stripped:
            i += 1
            continue

        if stripped == "---":
            i += 1
            continue

        if stripped.startswith("|"):
            table_html, i = parse_table(lines, i, cover_meta=in_cover and not cover_done)
            if table_html:
                if in_cover and not cover_done:
                    parts.append(table_html.replace("<table>", '<table class="cover-meta">', 1))
                else:
                    parts.append(table_html)
                continue

        if skip_toc_lines:
            if re.match(r"^\d+\.\s", stripped):
                i += 1
                continue
            skip_toc_lines = False

        if stripped.startswith("# "):
            title = stripped[2:].strip()
            sid = slug(title)
            if in_cover and not cover_done:
                parts.append(f'<div class="cover"><p class="brand">Bhawana Capital</p><h1>{inline(title)}</h1>')
                i += 1
                continue
            if title.startswith("1. ") or title == "1. What Is the LMS?":
                in_cover = False
                cover_done = True
            parts.append(f'<h1 class="section" id="{sid}">{inline(title)}</h1>')
            i += 1
            continue

        if stripped.startswith("## ") and in_cover and not cover_done:
            sub = stripped[3:].strip()
            if sub == "Complete Business Guide":
                parts.append(f'<p class="subtitle">{inline(sub)}</p>')
            elif sub == "Contents":
                cover_done = True
                parts.append('</div><div class="toc-page"><h2>Contents</h2><ol class="toc-list">')
                i += 1
                while i < len(lines):
                    item = lines[i].strip()
                    if not item:
                        i += 1
                        continue
                    m = re.match(r"^(\d+)\.\s*(.+)", item)
                    if not m:
                        break
                    num, text = m.group(1), m.group(2)
                    parts.append(
                        f'<li><a href="#{slug(f"{num}. {text}")}">{num}. {inline(text)}</a></li>'
                    )
                    i += 1
                parts.append('</ol></div><div class="main">')
                toc_done = True
                skip_toc_lines = True
                continue
            else:
                parts.append(f'<p class="subtitle">{inline(sub)}</p>')
            i += 1
            continue

        if stripped.startswith("### "):
            if in_cover and not cover_done:
                parts.append(f'<p class="subtitle">{inline(stripped[4:])}</p>')
            else:
                parts.append(f"<h3>{inline(stripped[4:])}</h3>")
            i += 1
            continue

        if stripped.startswith("#### "):
            parts.append(f"<h4>{inline(stripped[5:])}</h4>")
            i += 1
            continue

        if stripped.startswith("> "):
            quote_lines = []
            while i < len(lines) and lines[i].strip().startswith("> "):
                quote_lines.append(lines[i].strip()[2:])
                i += 1
            parts.append(f"<blockquote><p>{inline(' '.join(quote_lines))}</p></blockquote>")
            continue

        if stripped.startswith("- "):
            items = []
            while i < len(lines) and lines[i].strip().startswith("- "):
                items.append(f"<li>{inline(lines[i].strip()[2:])}</li>")
                i += 1
            parts.append("<ul>" + "".join(items) + "</ul>")
            continue

        if line.startswith("    ") and not stripped.startswith("|"):
            diagram_lines = []
            while i < len(lines) and (lines[i].startswith("    ") or lines[i].strip() == ""):
                if lines[i].strip():
                    diagram_lines.append(html.escape(lines[i].rstrip()[4:]))
                elif diagram_lines:
                    break
                i += 1
            cls = "formula" if len(diagram_lines) <= 3 and any("=" in l for l in diagram_lines) else "diagram"
            parts.append(f'<pre class="{cls}">' + "\n".join(diagram_lines) + "</pre>")
            continue

        if stripped.startswith("**") and stripped.endswith("**") and ":" in stripped:
            parts.append(f"<p><strong>{inline(stripped)}</strong></p>")
            i += 1
            continue

        if stripped.startswith("*") and stripped.endswith("*") and not stripped.startswith("**"):
            parts.append(f'<p class="note">{inline(stripped)}</p>')
            i += 1
            continue

        parts.append(f"<p>{inline(stripped)}</p>")
        i += 1

    if not cover_done:
        parts.insert(0, '<div class="cover">')
    return "\n".join(parts)


def main() -> None:
    md = MD_PATH.read_text(encoding="utf-8")
    body = parse_md(md)
    doc = f"""<!DOCTYPE html>
<html lang="en-GB">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Bhawana Loan Management System — Business Guide</title>
  <style>{CSS}</style>
</head>
<body>
{body}
</div>
<div class="footer">
  <p>Bhawana Capital — Loan Management System — Business Reference — June 2026</p>
  <p>Where a documented decision is approved but not yet fully implemented (e.g. processing fee deduction), both current and target behaviour are stated explicitly.</p>
</div>
</body>
</html>
"""
    OUT_PATH.write_text(doc, encoding="utf-8")
    print(f"Wrote {OUT_PATH} ({len(doc):,} bytes)")


if __name__ == "__main__":
    main()
