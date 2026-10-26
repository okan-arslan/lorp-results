package lorp.branching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lorp.master.RestrictedMaster;
import lorp.model.Instance;
import lorp.model.Route;
import lorp.model.Settings;
import lorp.model.Solution;

/** Most-fractional x -> v -> facility-specific directed aggregate arc -> z. */
public final class BranchSelector {
    public static final class Arc implements Comparable<Arc> {
        public final int facility, from, to;
        public Arc(int facility, int from, int to) { this.facility = facility; this.from = from; this.to = to; }
        @Override public int compareTo(Arc other) {
            int order = Integer.compare(facility, other.facility);
            if (order == 0) order = Integer.compare(from, other.from);
            return order == 0 ? Integer.compare(to, other.to) : order;
        }
    }

    public static final class Choice {
        public final Decision down, up;
        public final double value;
        private Choice(Decision down, Decision up, double value) { this.down = down; this.up = up; this.value = value; }
    }

    public static Choice select(Instance instance, RestrictedMaster.Snapshot state, double tolerance) {
        Choice choice = variables(state.x, Decision.Kind.X, tolerance);
        if (choice != null) return choice;
        choice = variables(state.v, Decision.Kind.V, tolerance);
        if (choice != null) return choice;
        double best = tolerance;
        for (Map.Entry<Arc, Double> item : flows(instance, state).entrySet()) {
            double value = item.getValue(), score = fractionality(value);
            if (score > best) {
                Arc arc = item.getKey(); best = score;
                choice = new Choice(Decision.arc(arc.facility, arc.from, arc.to, Decision.Sense.LE, (int) Math.floor(value)),
                        Decision.arc(arc.facility, arc.from, arc.to, Decision.Sense.GE, (int) Math.ceil(value)), value);
            }
        }
        if (choice != null) return choice;
        best = tolerance;
        for (int i = 0; i < state.z.length; i++) for (int j = 0; j < state.z[i].length; j++) {
            double value = state.z[i][j], score = fractionality(value);
            if (score > best) { best = score; choice = variable(Decision.Kind.Z, i, j, value); }
        }
        return choice;
    }

    private static Choice variables(double[] values, Decision.Kind kind, double tolerance) {
        Choice choice = null;
        double best = tolerance;
        for (int i = 0; i < values.length; i++) if (fractionality(values[i]) > best) {
            best = fractionality(values[i]); choice = variable(kind, i, -1, values[i]);
        }
        return choice;
    }
    private static Choice variable(Decision.Kind kind, int i, int j, double value) {
        return new Choice(Decision.variable(kind, i, j, Decision.Sense.LE, (int) Math.floor(value)),
                Decision.variable(kind, i, j, Decision.Sense.GE, (int) Math.ceil(value)), value);
    }
    private static double fractionality(double value) { return Math.abs(value - Math.rint(value)); }

    public static Map<Arc, Double> flows(Instance instance, RestrictedMaster.Snapshot state) {
        Map<Arc, Double> result = new TreeMap<>();
        for (Map.Entry<Route, Double> column : state.routes.entrySet()) {
            if (column.getValue() == 0) continue;
            Route route = column.getKey(); int previous = instance.facilityNode(route.facility);
            for (int j : route.visits()) {
                result.merge(new Arc(route.facility, previous, j), column.getValue(), Double::sum); previous = j;
            }
            result.merge(new Arc(route.facility, previous, instance.facilityNode(route.facility)), column.getValue(), Double::sum);
        }
        return result;
    }

    /** Integral aggregate arcs identify routes even if the route-variable representation is fractional. */
    public static Solution reconstruct(Instance instance, Settings settings, RestrictedMaster.Snapshot state,
                                       List<Decision> decisions) {
        boolean[] open = new boolean[instance.facilities];
        int[] direct = new int[instance.customers]; Arrays.fill(direct, -1);
        for (int i = 0; i < open.length; i++) {
            open[i] = state.x[i] > 0.5;
            for (int j = 0; j < direct.length; j++) if (state.z[i][j] > 0.5) {
                if (direct[j] != -1) throw new IllegalArgumentException("Multiple direct allocations");
                direct[j] = i;
            }
        }
        Map<Arc, Double> flows = flows(instance, state);
        List<Route> routes = new ArrayList<>();
        for (int i = 0; i < open.length; i++) {
            int depot = instance.facilityNode(i);
            for (int first = 0; first < instance.customers; first++) {
                if (flows.getOrDefault(new Arc(i, depot, first), 0.0) <= 0.5) continue;
                List<Integer> path = new ArrayList<>(); boolean[] seen = new boolean[instance.customers];
                int current = first;
                while (current != depot) {
                    if (current < 0 || current >= instance.customers || seen[current]) {
                        throw new IllegalArgumentException("Invalid integral aggregate route");
                    }
                    seen[current] = true; path.add(current);
                    int next = -1;
                    for (int j = 0; j <= instance.customers; j++) {
                        int node = j == instance.customers ? depot : j;
                        if (flows.getOrDefault(new Arc(i, current, node), 0.0) > 0.5) {
                            if (next >= 0) throw new IllegalArgumentException("Multiple aggregate successors");
                            next = node;
                        }
                    }
                    if (next == -1) throw new IllegalArgumentException("Missing aggregate successor");
                    current = next;
                }
                routes.add(new Route(instance, i, path.stream().mapToInt(Integer::intValue).toArray()));
            }
        }
        Solution solution = new Solution(instance, settings, open, direct, routes);
        for (Decision decision : decisions) {
            double value = 0;
            if (decision.kind == Decision.Kind.X) value = open[decision.facility] ? 1 : 0;
            if (decision.kind == Decision.Kind.Z) value = direct[decision.customer] == decision.facility ? 1 : 0;
            for (Route route : routes) {
                if (decision.kind == Decision.Kind.V && route.facility == decision.facility) value++;
                if (decision.kind == Decision.Kind.ARC) value += decision.routeCoefficient(route, instance.facilityNode(decision.facility));
            }
            if ((decision.sense == Decision.Sense.LE && value > decision.rhs)
                    || (decision.sense == Decision.Sense.GE && value < decision.rhs)) {
                throw new IllegalArgumentException("Reconstructed solution violates branch");
            }
        }
        return solution;
    }
}
