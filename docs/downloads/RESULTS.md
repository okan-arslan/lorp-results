# Results, Saved Solutions and Infeasibility Evidence

## Downloads

- `instances.csv`: one concise row per setting, including inputs, objectives,
  lower bounds, gaps, runtimes, search statistics and the final classification.
- `solutions.zip`: separate three-hour and final incumbent solution JSON files
  for every setting.
- `records.zip`: separate three-hour and final detailed solver report JSON
  files for every setting.
- `infeasibility-evidence.zip`: a separate evidence JSON file for each of the
  2,904 settings classified as infeasible, plus a summary and reading instructions.

The results cover 14,364 settings. The overall totals are 7,541 optimal,
2,904 infeasible and 3,919 not proven optimal.

## Concise CSV

The CSV uses 30 columns. `final_outcome` gives the final classification;
`status_3h` and `status_5h` preserve the original solver statuses.

| Columns | Meaning |
| --- | --- |
| `index, run_id` | Setting identifiers; use `index` to join the downloads. |
| `experiment, network, n, m` | Experiment, network, customer count and facility count. |
| `capacitated, p, R, T` | Capacity mode, facility-count limit, coverage radius and route-length limit. |
| `wF, wR, wA` | Facility, routing and allocation objective weights. |
| `status_3h, status_5h` | Original solver statuses at the comparison snapshot and termination. |
| `objective_3h, objective_5h` | Best saved feasible objective values at those times; these are upper bounds unless optimality is proved. |
| `lower_bound_3h, lower_bound_5h` | Retained numerical lower bounds at those times. |
| `gap_percent_5h` | `100 * max(0, objective_5h - lower_bound_5h) / max(1, abs(objective_5h))`, when an incumbent exists. |
| `elapsed_seconds` | Actual solver runtime, including runs that stopped early. |
| `nodes, columns, lp_iterations, root_bound` | Search effort and the root-node lower bound. |
| `branches.Z, branches.X, branches.V, branches.ARC` | Counts of assignment, opening, vehicle-count and directed-arc branches. |
| `final_outcome` | Classification after all checks, including the separate infeasibility proofs. |

An empty `p` means no facility-count limit. Blank objectives or gaps indicate
unavailable values. A network name alone does not identify a setting.

Use `final_outcome` to reproduce the infeasible total. For 82 settings,
`status_5h` remains `TIME_LIMIT` but `final_outcome` is `INFEASIBLE` because
separate exhaustive checks establish infeasibility. Original solver statuses
have not been changed.

"Not proven optimal" combines 3,917 time-limited cases and two early numerical
terminations. The latter have checked feasible incumbents but are not optima
or timeouts. The solver's actual termination reason remains in the CSV.

## Incumbent Solutions

Unzip `solutions.zip` to obtain one `solutions` directory with 14,364 numbered
setting folders. Folder names match the CSV `index`, padded to five digits.
For example, setting 247 has:

```text
solutions/00247/3h.json
solutions/00247/5h.json
```

Each file is an ordinary formatted JSON document. It identifies the setting,
parameters, report label, original solver status, actual elapsed seconds,
incumbent objective, lower bound, objective components and solution. The
solution contains opened facilities, direct assignments, ordered routes and
facility loads. There are 28,728 JSON files in this ZIP, including files for
settings without an incumbent.

The `3h.json` solution comes from the actual saved three-hour report. It is
not a copy of the final solution. `5h.json` contains the final incumbent; its
contents can differ from the three-hour incumbent. If no incumbent exists at
that report time, `has_incumbent` is false and `solution` is null.

Facility and customer ordinals are zero-based, separate index spaces.
`direct_facility[j] = -1` means routed; `p = null` means no facility limit.
Raw benchmark node IDs are one-based, with customers followed by facilities.
For a solver-infeasible report, `lower_bound` is null and `lower_bound_kind`
is `POSITIVE_INFINITY`; these are not missing finite bounds.

### Solution Field Guide

| Field | Meaning |
| --- | --- |
| `parameters.capacitated` | Whether facility capacities are enforced. Vehicle capacity is enforced even when this is false. |
| `parameters.p` | Maximum number of open facilities, or null when unrestricted. |
| `solution.open[i]` | Whether facility ordinal `i` is open. |
| `solution.direct_facility[j]` | Facility serving customer `j` directly, or -1 when that customer is served on a route. -1 does not mean unserved. |
| `solution.routes[k].facility` | Origin and return facility for route `k`. |
| `solution.routes[k].customers` | Ordered customer ordinals visited between departure from and return to that facility. The facility is not repeated in this list. |
| `solution.routes[k].demand` | Total customer demand on the route. |
| `solution.routes[k].length` | Total scaled route length, including both facility legs. |
| `solution.facility_loads[i]` | Combined direct and routed customer demand assigned to facility `i`. |
| `objective_components` | Unweighted fixed, routing and allocation cost components; objective = `wF*fixed + wR*routing + wA*allocation`. |

For example, `facility: 0, customers: [2, 5]` means facility 0 to customer 2
to customer 5 and back to facility 0. It does not refer to the raw benchmark
node numbered 0. A null solution means no incumbent was saved, not a zero-cost
solution or a proof of infeasibility.

## Detailed Solver Reports

Unzip `records.zip` to obtain the same numbered setting-folder layout under
`records`. For example:

```text
records/00247/3h.json
records/00247/5h.json
```

It contains two reports for each run, hence 28,728 JSON files:

- A three-hour report saves the status, lower bound and best feasible solution
  available at the comparison time.
- A final report saves those values when the run ends, either at the five-hour
  limit or earlier.

These are two observations of the same computation, not two independent runs
or additional instances. A run that ends before three hours can have two reports
with the same outcome and an earlier elapsed time.

`3h.json` names the comparison report and `5h.json` names the final report.
These names do not imply that an early-finished run lasted three or five hours;
read the actual `elapsed_seconds` in the file. Reports can be identical when
the run finished early.

Each report has `{index, kind, fields}`. `kind` is `snapshot` or `final`;
`fields` retains the original detailed solver values, including technical
provenance omitted from the concise CSV. These reports are useful for auditing
how a bound or incumbent changed between three hours and termination. They are
not complete console logs or full search-tree proofs.

The original `fields` values are strings, as in Java Properties. In these raw
reports `p: "-1"` means no facility-count limit, and `solution.direct.j: "-1"`
means customer `j` is routed. `solution.route.k.visit.q` is the customer at
position `q` on route `k`. The string `"Infinity"` denotes the positive-infinite
bound of a solver-infeasible report; it is not a finite objective. The solution
ZIP translates these fields into structured JSON and explicit null values.

## Infeasibility Evidence for All 2,904 Settings

The archive covers every setting whose `final_outcome` is `INFEASIBLE`.
Each entry identifies its setting, original solver status and evidence basis.
Extract it to obtain:

```text
infeasibility-evidence/README.txt
infeasibility-evidence/summary.json
infeasibility-evidence/<five-digit-index>/evidence.json
```

There are 2,904 numbered folders, not 14,364: feasible and unresolved settings
are not included. `summary.json` has counts and limitations; each setting's
`evidence.json` retains its original final solver report and, for 82 settings,
its separate integer certificate. There is one evidence file per setting,
not a three-hour/final pair. No solver status is rewritten by this packaging.

- `SOLVER_REPORTED_INFEASIBLE`: 2,822 entries preserve the final solver report
  declaring infeasibility. Their settings and certificate metadata are checked,
  but the entire search-tree proof is not independently replayed.
- `INDEPENDENT_INTEGER_CERTIFICATE`: 82 entries also contain a separate
  exhaustive infeasibility proof. Their original solver runs had timed out
  without an incumbent.

For a separate proof, every permitted choice of open facilities is ruled out
by a specific reason: either a customer cannot be served, or a group of
customers requires more capacity than its possible facilities provide.
The machine-readable proof calls each such reason a witness. The 82 complete
proofs contain 2,382 witnesses in total; these are nested checks, not additional
instances.

All 2,904 settings therefore have evidence entries, but only the 82 separately
checked settings have the exhaustive integer-arithmetic certificates.
The solver reports must not be described as independently replayed formal proofs.

## Opening the Downloads

Extract any of the three ZIPs with a standard ZIP utility. Find the
setting's `index` in the CSV, open its numbered folder, and open `3h.json` or
`5h.json` for reports/solutions, or `evidence.json` for infeasibility evidence,
with a JSON or text editor. No 159 MB JSON Lines file needs to be opened.
Each ZIP extracts many small files, so extraction can take some time.

## Scope and Limits

For the standard LoRP studied in Arslan (2021) and Haghi et al. (2023), please
use the corrected per-instance results released here. These should be used in
place of the B&P reference results and associated optimality claims reported
in Tables A.6-A.12 of the online appendix to Haghi et al. (2023). The partial
and decaying coverage experiments in that paper are not reassessed here.

Independent checks cover benchmark identities, saved settings, feasible
solutions, objectives, metadata, consistency and the 82 separate infeasibility
proofs. They do not replay every B&P tree or numerical LP-dual certificate.
Optimality uses absolute tolerance `1e-6` and relative tolerance `1e-8`.
Recomputed route lengths and objective components use comparison tolerance
`rel_tol=1e-12, abs_tol=1e-9`; discrete solution data must match exactly.

The solver and webpage were generated using ChatGPT and AI-assisted tools,
then reviewed and tested. The maintainer is responsible for the reported
results. Software is MIT-licensed; new results and text are CC BY 4.0.
Third-party rights are retained.
