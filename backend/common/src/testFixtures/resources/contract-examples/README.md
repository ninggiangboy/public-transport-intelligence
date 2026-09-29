Contract examples (DOC-44 §9.1). Every file in `valid/` must map to the WriteSet in its `.expected.json`; every file in
`invalid/` must be rejected with the stage and rule in its `.expected.json`. The etl contract test
`MessageContractTest` checks both; run it with `-Dpti.contract.write-expected=true` to write a missing expectation.
