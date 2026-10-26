# Branch-and-Price and Pricing Implementation

## Origin and Scope

The solver was implemented independently of the prior Java B&P and pulse code.
Its code was generated using ChatGPT and AI-assisted tools, then reviewed and
tested. "Independent checks" means separate model/checker implementations, not
certification by an external research group. The maintainer is responsible for
the implementation and reported results.

The formulation and branch priorities follow Arslan (2021). The repaired
implementation bounds facility openings by one, prices feasibility at every
node, includes every active branch constraint in new columns, and maintains
conservative lower bounds when pricing is unfinished. These mechanisms are
described here without requiring a separate correction document.

## Pricing Models

Pricing searches for an elementary route starting and ending at one facility.
Its physical demand must not exceed vehicle capacity and its physical length
must not exceed the route-length limit. Physical resources are separate from
the objective and reduced costs.

The route reduced cost has the form
`offset + sum(customer terms) + sum(directed arc terms)`.
Every returned route is checked against the complete master-row coefficient
calculation, including facility-specific directed-arc branching rows.

The download includes the pulse search and a CPLEX pricing MILP. Optional
heuristic pricing can find columns quickly but does not certify that no
improving column exists.

The pulse method follows the general elementary resource-constrained path
search paradigm associated with Lozano, Duque and Medaglia (2016), *An exact
algorithm for the elementary shortest path problem with resource constraints*,
Transportation Science 50(1), 348-357. The implementation here is newly
generated; that reference is not a claim that the prior code was reused.

## Pulse Bounds and Dominance

For each unvisited customer, the customer contribution plus its cheapest
possible incoming arc underestimates its contribution to a continuation.
Negative contributions become rewards. A fractional-knapsack relaxation over
remaining vehicle capacity upper-bounds the available reward; zero-demand
rewards are included first. Combining this with a conservative return-arc
bound gives a lower bound on the cost of every continuation.

Shortest-physical-path relaxations give necessary reachability conditions.
They may revisit nodes, so they can weaken the bound but do not eliminate a
feasible elementary route. No triangle inequality is assumed for reduced costs.

At the same endpoint, one label can dominate another only if its visited set
is a subset and it has no greater cost, length or demand. Cost/length comparisons
alone are insufficient. The dominance-memory cap stops storing new labels,
not exploring otherwise valid states.

Batch returns and deadline exits retain an unfinished-search indicator and a
conservative pricing lower bound. Finding no route heuristically, or returning
a batch, is not treated as exhaustive pricing.

## Master Bounds and Branching

Every node has phase-I feasibility pricing. Facility openings are binary in
the integer model and have upper bound one in the LP. Branching prioritizes
facility openings, vehicle counts, facility-specific directed arc flows and
direct customer assignments. Every branch is an explicit master row; its
coefficient is supplied for every new route and its dual affects pricing.

The node lower bound is Lagrangian: row right-hand sides times dual values,
plus the minimum contribution of each finitely bounded explicit variable,
plus `n * min(0, minimum route reduced-cost lower bound)`. Exact service and
nonempty routes imply that the sum of route-variable values is at most `n`.
A restricted-master objective alone is not used as a full-node lower bound.

In phase I, the bound is applied to hypothetical original feasible solutions
with zero artificials. A positive bound on that zero-objective feasible set
proves node infeasibility. It need not bound every positive-artificial point.

Children inherit valid parent bounds. Unfinished nodes remain on the frontier.
A valid conservative bound may close a gap without exhaustive pricing, but an
unbounded heuristic estimate cannot do so.

## Numerical Limits

The reported results were checked during the computational study. This does
not constitute a universal formal proof or replay every large search tree.

Optimality uses absolute tolerance `1e-6` and relative tolerance `1e-8`.
The public CSV retains original termination reasons as well as the final
classification, including separately checked infeasibility outcomes.
