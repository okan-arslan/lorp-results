package lorp.pricing;

import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloNumVar;
import ilog.cplex.IloCplex;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lorp.model.Instance;
import lorp.model.Route;
import lorp.solver.Deadline;

/** Exact optional-node elementary tour model, with MTZ order even for zero-demand nodes. */
public final class MilpPricer implements Pricer {
    @Override
    public Result price(Instance instance, PricingCosts costs, Set<Route> existing,
                        double tolerance, Deadline deadline) throws Exception {
        if (deadline.expired()) return new Result(Double.NEGATIVE_INFINITY, false, List.of());
        int n = instance.customers, depot = n;
        List<Integer> eligible = new ArrayList<>();
        for (int j = 0; j < n; j++) if (instance.demand(j) <= instance.vehicleCapacity) eligible.add(j);
        if (eligible.isEmpty()) return new Result(Double.POSITIVE_INFINITY, true, List.of());
        IloCplex model = new IloCplex();
        try {
            model.setOut(null);
            model.setParam(IloCplex.Param.Threads, 1);
            model.setParam(IloCplex.Param.MIP.Tolerances.MIPGap, 0.0);
            model.setParam(IloCplex.Param.MIP.Tolerances.AbsMIPGap, 0.0);
            model.setParam(IloCplex.Param.MIP.Tolerances.Integrality, 1e-9);
            model.setParam(IloCplex.Param.Simplex.Tolerances.Feasibility, 1e-9);
            IloNumVar[] visit = new IloNumVar[n], order = new IloNumVar[n];
            IloNumVar[][] edge = new IloNumVar[n + 1][n + 1];
            IloLinearNumExpr objective = model.linearNumExpr();
            objective.setConstant(costs.offset);
            IloLinearNumExpr load = model.linearNumExpr(), length = model.linearNumExpr();
            for (int j : eligible) {
                visit[j] = model.boolVar("visit_" + j);
                order[j] = model.numVar(0, n, "order_" + j);
                model.addGe(order[j], visit[j]);
                model.addLe(order[j], model.prod(n, visit[j]));
                objective.addTerm(costs.vertex[j], visit[j]);
                load.addTerm(instance.demand(j), visit[j]);
            }
            List<Integer> nodes = new ArrayList<>(eligible);
            nodes.add(depot);
            for (int a : nodes) for (int b : nodes) if (a != b) {
                edge[a][b] = model.boolVar("edge_" + a + "_" + b);
                int from = a == depot ? instance.facilityNode(costs.facility) : a;
                int to = b == depot ? instance.facilityNode(costs.facility) : b;
                objective.addTerm(costs.arc[from][to], edge[a][b]);
                length.addTerm(instance.distance(from, to), edge[a][b]);
                if (a != depot && b != depot) {
                    // n+1 is necessary for optional nodes: a visited node may have order n, b may be absent.
                    IloLinearNumExpr mtz = model.linearNumExpr();
                    mtz.addTerm(1, order[b]); mtz.addTerm(-1, order[a]);
                    mtz.addTerm(-(n + 1), edge[a][b]);
                    model.addGe(mtz, -n);
                }
            }
            for (int j : nodes) {
                IloLinearNumExpr incoming = model.linearNumExpr(), outgoing = model.linearNumExpr();
                for (int k : nodes) if (k != j) { incoming.addTerm(1, edge[k][j]); outgoing.addTerm(1, edge[j][k]); }
                if (j == depot) { model.addEq(incoming, 1); model.addEq(outgoing, 1); }
                else { model.addEq(incoming, visit[j]); model.addEq(outgoing, visit[j]); }
            }
            model.addLe(load, instance.vehicleCapacity);
            model.addLe(length, instance.routeLengthLimit);
            model.addMinimize(objective);
            if (deadline.expired()) return new Result(Double.NEGATIVE_INFINITY, false, List.of());
            model.setParam(IloCplex.Param.TimeLimit, deadline.remainingSeconds());
            boolean solved = model.solve();
            if (!solved && model.getStatus() == IloCplex.Status.Infeasible) {
                return new Result(Double.POSITIVE_INFINITY, true, List.of());
            }
            double bound = model.getBestObjValue();
            boolean complete = model.getStatus() == IloCplex.Status.Optimal;
            if (!Double.isFinite(bound) || Math.abs(bound) > 1e70) {
                bound = Double.NEGATIVE_INFINITY;
                complete = false;
            }
            List<Route> improving = new ArrayList<>();
            if (solved) {
                List<Integer> path = new ArrayList<>();
                boolean[] seen = new boolean[n];
                int current = depot;
                do {
                    int next = -1;
                    for (int j : nodes) if (j != current && model.getValue(edge[current][j]) > 0.5) {
                        if (next >= 0) throw new IllegalStateException("Nonunique pricing successor");
                        next = j;
                    }
                    if (next == -1) throw new IllegalStateException("Missing pricing successor");
                    if (next == depot) break;
                    if (seen[next]) throw new IllegalStateException("Pricing contains a customer cycle");
                    seen[next] = true; path.add(next); current = next;
                } while (path.size() <= n);
                Route route;
                try {
                    route = new Route(instance, costs.facility, path.stream().mapToInt(Integer::intValue).toArray());
                } catch (IllegalArgumentException numericalResourceViolation) {
                    // The bound remains useful, but an approximately feasible route must never be inserted.
                    return new Result(bound, false, List.of());
                }
                double reduced = costs.cost(instance, route);
                if (Math.abs(reduced - model.getObjValue()) > 1e-7 * Math.max(1, Math.abs(reduced))) {
                    throw new IllegalStateException("Pricing MILP objective differs from reconstructed route");
                }
                if (reduced < -tolerance && !existing.contains(route)) improving.add(route);
            }
            return new Result(bound, complete, improving);
        } finally {
            model.end();
        }
    }
}
