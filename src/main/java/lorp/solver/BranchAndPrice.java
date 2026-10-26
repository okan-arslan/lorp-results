package lorp.solver;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import lorp.branching.BranchSelector;
import lorp.branching.Decision;
import lorp.master.RestrictedMaster;
import lorp.model.Instance;
import lorp.model.Route;
import lorp.model.Settings;
import lorp.model.Solution;
import lorp.pricing.Pricer;

/** Custom tree: only certified full-node lower bounds enter the live frontier. */
public final class BranchAndPrice {
    public enum Status { IN_PROGRESS, OPTIMAL, INFEASIBLE, TIME_LIMIT, PRICING_INCOMPLETE, NUMERICAL_UNCERTIFIED }
    @FunctionalInterface
    public interface Observer { void accept(Result progress) throws Exception; }
    public static final double ABS_GAP = 1e-6, REL_GAP = 1e-8, INTEGER_TOLERANCE = 1e-7;
    private static final class Node {
        final long id;
        final List<Decision> decisions;
        final double bound;
        Node(long id, List<Decision> decisions, double bound) {
            this.id = id; this.decisions = List.copyOf(decisions); this.bound = bound;
        }
    }
    public static final class Result {
        public final Status status;
        public final Solution solution;
        public final double lowerBound, elapsedSeconds;
        public final Double rootBound;
        public final long nodes;
        public final int columns, lpIterations;
        public final Map<Decision.Kind, Integer> branches;
        Result(Status status, Solution solution, double lowerBound, Double rootBound, long nodes,
               int columns, int lpIterations, double elapsedSeconds, Map<Decision.Kind, Integer> branches) {
            this.status = status; this.solution = solution; this.lowerBound = lowerBound; this.rootBound = rootBound;
            this.nodes = nodes; this.columns = columns; this.lpIterations = lpIterations;
            this.elapsedSeconds = elapsedSeconds; this.branches = Map.copyOf(branches);
        }
    }

    public static Result initialState() {
        return new Result(Status.IN_PROGRESS, null, 0, null, 0, 0, 0, 0, Map.of());
    }

    public static Result solve(Instance instance, Settings settings, Pricer pricer,
                               Deadline deadline, boolean useHeuristic) throws Exception {
        return solve(instance, settings, pricer, deadline, useHeuristic, progress -> {});
    }

    public static Result solve(Instance instance, Settings settings, Pricer pricer,
                               Deadline deadline, boolean useHeuristic, Observer observer) throws Exception {
        Set<Route> pool = new LinkedHashSet<>();
        PriorityQueue<Node> frontier = new PriorityQueue<>(Comparator.comparingDouble((Node node) -> node.bound).thenComparingLong(node -> node.id));
        frontier.add(new Node(0, List.of(), 0)); // All permitted objective weights/costs are nonnegative.
        Solution incumbent = null;
        Double rootBound = null;
        double closedBound = Double.POSITIVE_INFINITY;
        long processed = 0, nextId = 1;
        int lpIterations = 0, lastHeuristicColumns = 0;
        EnumMap<Decision.Kind, Integer> branches = new EnumMap<>(Decision.Kind.class);
        Status stop = Status.TIME_LIMIT;
        for (int i = 0; i < instance.facilities; i++) for (int j = 0; j < instance.customers; j++) {
            int depot = instance.facilityNode(i);
            if (instance.demand(j) <= instance.vehicleCapacity
                    && instance.distance(depot, j) + instance.distance(j, depot) <= instance.routeLengthLimit) {
                pool.add(new Route(instance, i, new int[] {j}));
            }
        }
        observer.accept(new Result(Status.IN_PROGRESS, null, 0, null, 0, pool.size(), 0, deadline.elapsedSeconds(), branches));
        if (useHeuristic && !deadline.expired()) {
            incumbent = heuristic(instance, settings, pool, deadline, 10);
            lastHeuristicColumns = pool.size();
            if (incumbent != null && incumbent.objective == 0) {
                Result zero = new Result(Status.OPTIMAL, incumbent, 0, 0.0, 0, pool.size(), 0, deadline.elapsedSeconds(), branches);
                observer.accept(zero); return zero;
            }
        }
        while (!frontier.isEmpty() && !deadline.expired()) {
            observer.accept(new Result(Status.IN_PROGRESS, incumbent, Math.min(closedBound, frontier.peek().bound),
                    rootBound, processed, pool.size(), lpIterations, deadline.elapsedSeconds(), branches));
            Node node = frontier.poll();
            if (incumbent != null && node.bound >= incumbent.objective - gapTolerance(incumbent.objective)) {
                closedBound = Math.min(closedBound, node.bound); continue;
            }
            final Solution currentIncumbent = incumbent;
            final Double currentRoot = rootBound;
            final double otherBound = Math.min(closedBound, frontier.isEmpty() ? Double.POSITIVE_INFINITY : frontier.peek().bound);
            final long currentNodes = processed + 1;
            final int previousIterations = lpIterations;
            ColumnGeneration.Result lp = ColumnGeneration.solve(instance, settings, node.decisions, pool, pricer, deadline,
                    (currentBound, iterations) -> observer.accept(new Result(Status.IN_PROGRESS, currentIncumbent,
                            Math.min(otherBound, Math.max(node.bound, currentBound)), currentRoot, currentNodes,
                            pool.size(), previousIterations + iterations, deadline.elapsedSeconds(), branches)));
            processed++; lpIterations += lp.iterations;
            double bound = Math.max(node.bound, lp.bound);
            if (lp.status == ColumnGeneration.Status.INFEASIBLE) continue;
            if (incumbent != null && bound >= incumbent.objective - gapTolerance(incumbent.objective)) {
                if (node.id == 0 && lp.status == ColumnGeneration.Status.OPTIMAL) rootBound = bound;
                closedBound = Math.min(closedBound, bound); continue;
            }
            if (lp.status != ColumnGeneration.Status.OPTIMAL) {
                frontier.add(new Node(node.id, node.decisions, bound));
                stop = lp.status == ColumnGeneration.Status.NUMERICAL_UNCERTIFIED ? Status.NUMERICAL_UNCERTIFIED
                        : lp.status == ColumnGeneration.Status.PRICING_INCOMPLETE ? Status.PRICING_INCOMPLETE : Status.TIME_LIMIT;
                break;
            }
            if (node.id == 0) rootBound = bound;
            if (useHeuristic && !deadline.expired() && (node.id == 0 || pool.size() >= lastHeuristicColumns + 1000)) {
                Solution checked = heuristic(instance, settings, pool, deadline, 120);
                if (checked != null && (incumbent == null || checked.objective < incumbent.objective)) incumbent = checked;
                lastHeuristicColumns = pool.size();
            }
            if (incumbent != null && bound >= incumbent.objective - gapTolerance(incumbent.objective)) {
                closedBound = Math.min(closedBound, bound); continue;
            }
            BranchSelector.Choice choice = BranchSelector.select(instance, lp.solution, INTEGER_TOLERANCE);
            if (choice == null) {
                try {
                    Solution checked = BranchSelector.reconstruct(instance, settings, lp.solution, node.decisions);
                    if (Math.abs(checked.objective - lp.objective) > gapTolerance(checked.objective)
                            || checked.objective - bound > gapTolerance(checked.objective)) {
                        throw new IllegalArgumentException("Integral node has an uncertified objective gap");
                    }
                    if (incumbent == null || checked.objective < incumbent.objective) incumbent = checked;
                    closedBound = Math.min(closedBound, bound);
                } catch (IllegalArgumentException numericalFailure) {
                    frontier.add(new Node(node.id, node.decisions, bound)); stop = Status.NUMERICAL_UNCERTIFIED; break;
                }
            } else {
                branches.merge(choice.down.kind, 1, Integer::sum);
                List<Decision> down = new ArrayList<>(node.decisions); down.add(choice.down);
                List<Decision> up = new ArrayList<>(node.decisions); up.add(choice.up);
                // Both children are installed before checking the deadline, preserving the parent's bound.
                frontier.add(new Node(nextId++, down, bound)); frontier.add(new Node(nextId++, up, bound));
            }
        }
        if (frontier.isEmpty()) stop = incumbent == null ? Status.INFEASIBLE : Status.OPTIMAL;
        double bound = Math.min(closedBound, frontier.isEmpty() ? Double.POSITIVE_INFINITY : frontier.peek().bound);
        if (incumbent != null) bound = Math.min(bound, incumbent.objective);
        Result result = new Result(stop, incumbent, bound, rootBound, processed, pool.size(), lpIterations, deadline.elapsedSeconds(), branches);
        observer.accept(result); return result;
    }

    public static double gapTolerance(double objective) { return ABS_GAP + REL_GAP * Math.abs(objective); }

    private static Solution heuristic(Instance instance, Settings settings, Set<Route> pool,
                                      Deadline deadline, double seconds) throws Exception {
        try (RestrictedMaster heuristic = new RestrictedMaster(instance, settings, List.of())) {
            for (Route route : pool) heuristic.add(route);
            RestrictedMaster.Snapshot candidate = heuristic.integerHeuristic(deadline, seconds);
            if (candidate == null) return null;
            try {
                return BranchSelector.reconstruct(instance, settings, candidate, List.of());
            } catch (IllegalArgumentException numericalFailure) {
                // MIP feasibility tolerances can admit a candidate that fails strict physical checks.
                return null;
            }
        }
    }
}
