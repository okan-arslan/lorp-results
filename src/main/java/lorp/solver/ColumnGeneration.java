package lorp.solver;

import java.util.List;
import java.util.Set;
import lorp.branching.Decision;
import lorp.master.RestrictedMaster;
import lorp.model.Instance;
import lorp.model.Route;
import lorp.model.Settings;
import lorp.pricing.Pricer;
import lorp.pricing.PricingCosts;

public final class ColumnGeneration {
    @FunctionalInterface
    public interface BoundListener { void accept(double bound, int iterations) throws Exception; }
    public enum Status { OPTIMAL, INFEASIBLE, INTERRUPTED, PRICING_INCOMPLETE, NUMERICAL_UNCERTIFIED }
    public static final class Result {
        public final Status status;
        public final double bound;
        public final Double objective;
        public final RestrictedMaster.Snapshot solution;
        public final int iterations;
        Result(Status status, double bound, Double objective, RestrictedMaster.Snapshot solution, int iterations) {
            this.status = status; this.bound = bound; this.objective = objective;
            this.solution = solution; this.iterations = iterations;
        }
    }

    public static Result solve(Instance instance, Settings settings, List<Decision> decisions,
                               Set<Route> pool, Pricer pricer, Deadline deadline) throws Exception {
        return solve(instance, settings, decisions, pool, pricer, deadline, (bound, iterations) -> {});
    }

    public static Result solve(Instance instance, Settings settings, List<Decision> decisions,
                               Set<Route> pool, Pricer pricer, Deadline deadline, BoundListener progress) throws Exception {
        final double pricingTolerance = 1e-8;
        final double phaseTolerance = 1e-8;
        int iterations = 0;
        double certifiedBound = Double.NEGATIVE_INFINITY;
        boolean phaseOne = true;
        try (RestrictedMaster master = new RestrictedMaster(instance, settings, decisions)) {
            for (Route route : pool) master.add(route);
            while (!deadline.expired()) {
                if (!master.solve(deadline)) return new Result(Status.INTERRUPTED, certifiedBound, null, null, iterations);
                iterations++;
                if (phaseOne && master.objective() <= phaseTolerance) {
                    master.startPhaseTwo(); phaseOne = false; continue;
                }
                double minimum = Double.POSITIVE_INFINITY;
                boolean complete = true;
                int added = 0;
                for (int i = 0; i < instance.facilities; i++) {
                    PricingCosts costs = master.pricingCosts(i);
                    Pricer.Result priced = pricer.price(instance, costs, pool, pricingTolerance, deadline);
                    minimum = Math.min(minimum, priced.lowerBound);
                    complete &= priced.complete;
                    for (Route route : priced.improving) {
                        double reduced = costs.cost(instance, route);
                        if (Math.abs(reduced - master.reducedCost(route)) > 1e-7 * Math.max(1, Math.abs(reduced))) {
                            throw new IllegalStateException("Pricing/master coefficient mismatch");
                        }
                        if (reduced < -pricingTolerance && pool.add(route)) { master.add(route); added++; }
                    }
                }
                // Re-evaluate after adding columns: the dual still refers to the preceding solved LP.
                double bound = master.lowerBound(minimum);
                if (!phaseOne) {
                    certifiedBound = Math.max(certifiedBound, bound);
                    progress.accept(certifiedBound, iterations);
                }
                if (added > 0) continue;
                if (phaseOne && bound > phaseTolerance) return new Result(Status.INFEASIBLE,
                        Double.POSITIVE_INFINITY, null, null, iterations);
                if (!complete && minimum < -pricingTolerance) return new Result(deadline.expired() ? Status.INTERRUPTED : Status.PRICING_INCOMPLETE,
                        certifiedBound, null, null, iterations);
                if (minimum < -pricingTolerance || phaseOne) return new Result(Status.NUMERICAL_UNCERTIFIED,
                        certifiedBound, null, null, iterations);
                return new Result(Status.OPTIMAL, certifiedBound, master.objective(), master.snapshot(), iterations);
            }
            return new Result(Status.INTERRUPTED, certifiedBound, null, null, iterations);
        }
    }
}
