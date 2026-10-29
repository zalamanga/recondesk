# Synthetic CSV examples

All names, references, dates, and amounts in this directory are invented for local training. They do not represent a customer, employer, bank, or payment provider.

Import `internal.csv` and `partner.csv` together. Each file has 11 data rows. Grouping their references creates 10 cases: 2 matched cases and 8 open exceptions.

| Reference | Expected classification | Reason |
| --- | --- | --- |
| DEMO-MATCH-IDR | MATCHED | Same date, amount, and currency |
| DEMO-MATCH-USD | MATCHED | Same date, amount, and currency |
| DEMO-DIFF-AMOUNT | DIFFERENT | AMOUNT |
| DEMO-DIFF-DATE | DIFFERENT | DATE |
| DEMO-DIFF-CURRENCY | DIFFERENT | CURRENCY |
| DEMO-INTERNAL-ONLY | INTERNAL_ONLY | No partner row |
| DEMO-PARTNER-ONLY | PARTNER_ONLY | No internal row |
| DEMO-DUP-INTERNAL | DUPLICATE | Two internal rows |
| DEMO-DUP-PARTNER | DUPLICATE | Two partner rows |
| DEMO-DUP-BOTH | DUPLICATE | Two rows in each source |

Duplicate references remain separate source rows. ReconDesk does not select a preferred duplicate or calculate a misleading single amount for it.

To demonstrate validation, replace either valid source with one file from `invalid/`. Each file deliberately breaks one rule, identified by its filename. The other source should remain valid. The application must reject the whole import without creating a partial run.

The `invalid/` set covers an incorrect header, a negative amount, excess decimal precision, an impossible calendar date, an unsupported currency, and an extra column. This set is illustrative; the automated parser tests cover more cases.
