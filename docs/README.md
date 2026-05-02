# Docs

Project documentation lives here, organised by intent rather than alphabetically. When adding a new doc, pick the subdirectory whose description fits — if nothing fits, that's a signal to discuss before creating a new top-level category.

- **`reference/`** — project-specific reference: what the system *is*. The architecture, the domain model / glossary. Describes the world as it stands; no procedures.
- **`process/`** — how-tos: step-by-step procedures for working with the project. Cutting a release, working with the container image, future runbooks. Describes things you *do*.
- **`reports/`** — generated reports and their supporting data (e.g. test coverage and its history JSONL). Auto-modified by tooling — don't hand-edit unless you mean to. The skill / script that owns each report is identified at the top of the report.
- **`research/`** — imported / external writeups that informed design decisions. Editorially separate from `reference/`: research is verbatim or near-verbatim; reference is the team's authoritative description of the project.
