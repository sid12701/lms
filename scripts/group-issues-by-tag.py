import json
from collections import defaultdict

with open("open-issues-labels.json", encoding="utf-8-sig") as f:
    issues = json.load(f)

META = {
    "needs-triage",
    "P0-blocker",
    "P1-high",
    "P2-medium",
    "ready-for-human",
    "ready-for-agent",
    "documentation",
}

by_tag: dict[str, list] = defaultdict(list)
no_tag: list = []

for issue in sorted(issues, key=lambda x: x["number"]):
    tags = [label["name"] for label in issue["labels"] if label["name"] not in META]
    if not tags:
        no_tag.append(issue)
    for tag in tags:
        by_tag[tag].append(issue)

for tag in sorted(by_tag.keys()):
    items = sorted(by_tag[tag], key=lambda x: x["number"])
    print(f"## {tag} ({len(items)})")
    for issue in items:
        print(f"  #{issue['number']} — {issue['title']}")
    print()

if no_tag:
    print(f"## (no descriptive tag) ({len(no_tag)})")
    for issue in no_tag:
        print(f"  #{issue['number']} — {issue['title']}")
