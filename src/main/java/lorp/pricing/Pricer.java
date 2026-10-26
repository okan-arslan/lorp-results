package lorp.pricing;

import java.util.List;
import java.util.Set;
import lorp.model.Instance;
import lorp.model.Route;
import lorp.solver.Deadline;

public interface Pricer {
    Result price(Instance instance, PricingCosts costs, Set<Route> existing,
                 double tolerance, Deadline deadline) throws Exception;

    final class Result {
        public final double lowerBound;
        public final boolean complete;
        public final List<Route> improving;
        public Result(double lowerBound, boolean complete, List<Route> improving) {
            this.lowerBound = lowerBound;
            this.complete = complete;
            this.improving = List.copyOf(improving);
        }
    }
}
