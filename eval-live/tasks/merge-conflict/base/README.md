# spend

A personal spending ledger. `spend.core` reads the ledger file (`date|category|amount|note`, one expense per line),
`spend.export` holds the export formats and their registry, `spend.cli` is the command line:

    python -m spend.cli.main export examples/march.ledger --format markdown
    python -m spend.cli.main export examples/march.ledger --output march.txt
    python -m spend.cli.main formats

Tests: `python -m unittest discover -s tests`.
