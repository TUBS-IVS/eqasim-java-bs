# vrb-zone-fares test resources

`vrb_fare_model_2026.json` is the fare model written by the eqasim-bs exporter
`braunschweig.data.vrb.fare_model_export.build_fare_model(snapshot_date="2026-06-20",
assumptions=DEFAULT_ASSUMPTIONS)` (eqasim-bs branch feature/i430-vrb-zone-fare-model, commit 494e7f0).
`VrbFareModelTest.parsesTheModelWrittenByThePythonExporter` pins the cross-language contract.
Regenerate it with that call whenever the exporter's JSON contract changes.
