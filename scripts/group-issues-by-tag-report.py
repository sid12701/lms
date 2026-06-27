import json
import sys
from collections import defaultdict

sys.stdout.reconfigure(encoding="utf-8")

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

tag_desc: dict[str, str] = {}
by_tag: dict[str, list] = defaultdict(list)
no_tag: list = []

for issue in sorted(issues, key=lambda x: x["number"]):
    tags = []
    for label in issue["labels"]:
        name = label["name"]
        if name not in META:
            tags.append(name)
            if name not in tag_desc and label.get("description"):
                tag_desc[name] = label["description"]
    if not tags:
        no_tag.append(issue)
    for tag in tags:
        by_tag[tag].append(issue)

print(f"Total open issues: {len(issues)}\n")
for tag in sorted(by_tag.keys()):
    items = sorted(by_tag[tag], key=lambda x: x["number"])
    desc = tag_desc.get(tag, "")
    print(f"## {tag} ({len(items)})")
    if desc:
        print(f"   {desc}")
    for issue in items:
        print(f"  #{issue['number']} — {issue['title']}")
    print()

if no_tag:
    print(f"## (no descriptive tag) ({len(no_tag)})")
    print("   Issues with only workflow/priority labels")
    for issue in no_tag:
        wf = [label["name"] for label in issue["labels"]]
        print(f"  #{issue['number']} — {issue['title']}  [{', '.join(wf)}]")
