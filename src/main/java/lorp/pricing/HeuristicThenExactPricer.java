package lorp.pricing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lorp.model.Instance;
import lorp.model.Route;
import lorp.solver.Deadline;

/** Bounded beam search discovers columns; only the delegated exact pricer certifies. */
public final class HeuristicThenExactPricer implements Pricer {
    private static final int WIDTH = 12, MAX_DEPTH = 24, BATCH = 64;
    private final Pricer exact;
    public HeuristicThenExactPricer(Pricer exact) { this.exact = exact; }
    private static final class State {
        final int[] path;
        final int last;
        final long demand;
        final double length, cost;
        State(int[] path, int last, long demand, double length, double cost) {
            this.path = path; this.last = last; this.demand = demand; this.length = length; this.cost = cost;
        }
    }

    @Override
    public Result price(Instance instance, PricingCosts costs, Set<Route> existing,
                        double tolerance, Deadline deadline) throws Exception {
        int depot = instance.facilityNode(costs.facility);
        boolean nonnegative = costs.offset >= 0;
        for (double value : costs.vertex) nonnegative &= value >= 0;
        for (int a = 0; a <= instance.customers; a++) for (int b = 0; b <= instance.customers; b++) {
            int from = a == instance.customers ? depot : a, to = b == instance.customers ? depot : b;
            nonnegative &= costs.arc[from][to] >= 0;
        }
        if (nonnegative) return new Result(costs.offset, true, List.of());
        List<State> beam = List.of(new State(new int[0], depot, 0, 0, costs.offset));
        Set<Route> improving = new LinkedHashSet<>();
        for (int depth = 0; depth < Math.min(MAX_DEPTH, instance.customers) && !beam.isEmpty(); depth++) {
            List<State> next = new ArrayList<>();
            for (State state : beam) for (int j = 0; j < instance.customers; j++) {
                if (deadline.expired()) return new Result(Double.NEGATIVE_INFINITY, false, List.copyOf(improving));
                boolean seen = false;
                for (int visited : state.path) seen |= visited == j;
                if (seen) continue;
                long demand = state.demand + instance.demand(j);
                double length = state.length + instance.distance(state.last, j);
                if (demand > instance.vehicleCapacity || length > instance.routeLengthLimit) continue;
                int[] path = Arrays.copyOf(state.path, state.path.length + 1); path[path.length - 1] = j;
                State candidate = new State(path, j, demand, length, state.cost + costs.vertex[j] + costs.arc[state.last][j]);
                next.add(candidate);
                if (length + instance.distance(j, depot) <= instance.routeLengthLimit
                        && candidate.cost + costs.arc[j][depot] < -tolerance) {
                    Route route = new Route(instance, costs.facility, path);
                    if (!existing.contains(route) && costs.cost(instance, route) < -tolerance) improving.add(route);
                    if (improving.size() >= BATCH) return new Result(Double.NEGATIVE_INFINITY, false, List.copyOf(improving));
                }
            }
            next.sort(Comparator.comparingDouble((State state) -> state.cost).thenComparingDouble(state -> state.length));
            beam = next.size() > WIDTH ? next.subList(0, WIDTH) : next;
        }
        if (!improving.isEmpty()) return new Result(Double.NEGATIVE_INFINITY, false, List.copyOf(improving));
        return exact.price(instance, costs, existing, tolerance, deadline);
    }
}
