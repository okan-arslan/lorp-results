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

/** Elementary depth-first pricing with a fractional-knapsack bound and subset dominance. */
public final class PulsePricer implements Pricer {
    private static final int MAX_LABELS = 200000, BATCH = 64;
    private static final double BOUND_MARGIN = 1e-9;

    @Override
    public Result price(Instance instance, PricingCosts costs, Set<Route> existing,
                        double tolerance, Deadline deadline) {
        return new Search(instance, costs, existing, tolerance, deadline).run();
    }

    private static final class Label {
        final long[] visited;
        final long load;
        final double length, cost;
        Label(long[] visited, long load, double length, double cost) {
            this.visited = visited.clone(); this.load = load; this.length = length; this.cost = cost;
        }
    }
    private static final class Search {
        final Instance instance;
        final PricingCosts costs;
        final Set<Route> existing;
        final double tolerance;
        final Deadline deadline;
        final int n, depot;
        final long[] visited;
        final int[] path;
        final double[][] shortest;
        final double[] profit;
        final Integer[] profitOrder;
        final int[][] successors;
        final List<List<Label>> labels;
        final Set<Route> improving = new LinkedHashSet<>();
        double best = Double.POSITIVE_INFINITY, minimumReturn = Double.POSITIVE_INFINITY;
        int labelCount;
        boolean stopped;

        Search(Instance instance, PricingCosts costs, Set<Route> existing, double tolerance, Deadline deadline) {
            this.instance = instance; this.costs = costs; this.existing = existing;
            this.tolerance = tolerance; this.deadline = deadline;
            n = instance.customers; depot = n;
            visited = new long[(n + 63) / 64]; path = new int[n];
            shortest = new double[n + 1][n + 1];
            for (int a = 0; a <= n; a++) for (int b = 0; b <= n; b++) shortest[a][b] = instance.distance(node(a), node(b));
            // A relaxation may traverse already visited customers or the depot internally.
            for (int k = 0; k <= n; k++) for (int a = 0; a <= n; a++) for (int b = 0; b <= n; b++) {
                shortest[a][b] = Math.min(shortest[a][b], shortest[a][k] + shortest[k][b]);
            }
            profit = new double[n]; profitOrder = new Integer[n];
            for (int j = 0; j < n; j++) {
                double incoming = Double.POSITIVE_INFINITY;
                for (int a = 0; a <= n; a++) if (a != j) incoming = Math.min(incoming, costs.arc[node(a)][j]);
                profit[j] = Math.max(0, -(costs.vertex[j] + incoming)); profitOrder[j] = j;
                if (instance.demand(j) <= instance.vehicleCapacity) minimumReturn = Math.min(minimumReturn, costs.arc[j][node(depot)]);
            }
            Arrays.sort(profitOrder, Comparator.comparingDouble((Integer j) -> density(j)).reversed().thenComparingInt(j -> j));
            successors = new int[n + 1][n];
            for (int a = 0; a <= n; a++) {
                final int last = a;
                Integer[] order = new Integer[n];
                for (int j = 0; j < n; j++) order[j] = j;
                Arrays.sort(order, Comparator.comparingDouble((Integer j) -> costs.vertex[j] + costs.arc[node(last)][j]).thenComparingInt(j -> j));
                for (int j = 0; j < n; j++) successors[a][j] = order[j];
            }
            labels = new ArrayList<>();
            for (int j = 0; j <= n; j++) labels.add(new ArrayList<>());
        }

        int node(int local) { return local == depot ? instance.facilityNode(costs.facility) : local; }
        double density(int j) {
            if (profit[j] == 0) return 0;
            return instance.demand(j) == 0 ? Double.POSITIVE_INFINITY : profit[j] / instance.demand(j);
        }
        boolean seen(int j) { return (visited[j >>> 6] & (1L << (j & 63))) != 0; }
        void mark(int j) { visited[j >>> 6] |= 1L << (j & 63); }
        void unmark(int j) { visited[j >>> 6] &= ~(1L << (j & 63)); }

        Result run() {
            if (minimumReturn == Double.POSITIVE_INFINITY) return new Result(Double.POSITIVE_INFINITY, true, List.of());
            double rootBound = lowerBound(depot, 0, 0, costs.offset);
            pulse(depot, 0, 0, 0, costs.offset);
            return new Result(stopped ? rootBound : best, !stopped, List.copyOf(improving));
        }

        double lowerBound(int last, long load, double length, double cost) {
            double capacity = instance.vehicleCapacity - load, reward = 0;
            // Fractional knapsack upper-bounds the total reward of any feasible unvisited subset.
            for (int j : profitOrder) {
                if (profit[j] == 0) break;
                if (seen(j) || instance.demand(j) > instance.vehicleCapacity - load
                        || length + shortest[last][j] + shortest[j][depot] > instance.routeLengthLimit + BOUND_MARGIN) continue;
                int demand = instance.demand(j);
                if (demand <= capacity) { reward += profit[j]; capacity -= demand; }
                else { reward += profit[j] * capacity / demand; break; }
            }
            return cost - reward + minimumReturn;
        }

        void pulse(int last, int depth, long load, double length, double cost) {
            if (stopped) return;
            if (deadline.expired()) { stopped = true; return; }
            if (length + shortest[last][depot] > instance.routeLengthLimit + BOUND_MARGIN) return;
            if (depth > 0 && length + instance.distance(node(last), node(depot)) <= instance.routeLengthLimit) {
                Route route = new Route(instance, costs.facility, Arrays.copyOf(path, depth));
                double reduced = costs.cost(instance, route); best = Math.min(best, reduced);
                if (reduced < -tolerance && !existing.contains(route)) improving.add(route);
                if (improving.size() >= BATCH) { stopped = true; return; }
            }
            if (lowerBound(last, load, length, cost) > best + BOUND_MARGIN || dominated(last, load, length, cost)) return;
            for (int j : successors[last]) {
                if (seen(j)) continue;
                long nextLoad = load + instance.demand(j);
                double nextLength = length + instance.distance(node(last), j);
                if (nextLoad > instance.vehicleCapacity || nextLength > instance.routeLengthLimit) continue;
                mark(j); path[depth] = j;
                pulse(j, depth + 1, nextLoad, nextLength, cost + (costs.vertex[j] + costs.arc[node(last)][j]));
                unmark(j);
                if (stopped) return;
            }
        }

        boolean dominated(int last, long load, double length, double cost) {
            List<Label> atNode = labels.get(last);
            for (Label label : atNode) {
                if (label.load <= load && label.length <= length && label.cost <= cost && subset(label.visited, visited)) return true;
            }
            for (int k = atNode.size() - 1; k >= 0; k--) {
                Label label = atNode.get(k);
                if (load <= label.load && length <= label.length && cost <= label.cost && subset(visited, label.visited)) {
                    atNode.remove(k); labelCount--;
                }
            }
            // A memory cap only disables new dominance labels; it never drops search states.
            if (labelCount < MAX_LABELS) { atNode.add(new Label(visited, load, length, cost)); labelCount++; }
            return false;
        }
        static boolean subset(long[] left, long[] right) {
            for (int k = 0; k < left.length; k++) if ((left[k] & ~right[k]) != 0) return false;
            return true;
        }
    }
}
