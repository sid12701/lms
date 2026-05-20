## graphify

This project has a graphify knowledge graph at graphify-out/.

Rules:
- Treat graphify as the primary map of this repository.
- Before answering architecture, implementation, review, debugging, or codebase-navigation questions, read graphify-out/GRAPH_REPORT.md first.
- Prefer graph-derived structure, communities, god nodes, hyperedges, and graph-backed query results before searching raw files.
- Use raw file reads only to verify or deepen an answer after the graph has identified the relevant area.
- If graphify-out/wiki/index.md exists, navigate it instead of broad raw-file exploration.
- If graphify-out/graph.json is missing or stale relative to recent code changes, refresh it before deep repo analysis.
- After modifying code files in this session, run `graphify update .` to keep the graph current (AST-only, no API cost).
