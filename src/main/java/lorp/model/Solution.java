package lorp.model;

import java.util.List;
import lorp.verification.SolutionChecker;

/** Only independently checked integral solutions become incumbents. */
public final class Solution {
    private final boolean[] open;
    private final int[] direct;
    public final List<Route> routes;
    public final double objective;

    public Solution(Instance instance, Settings settings, boolean[] open, int[] direct, List<Route> routes) {
        this.open = open.clone(); this.direct = direct.clone(); this.routes = List.copyOf(routes);
        objective = SolutionChecker.check(instance, settings, this.open, this.direct, this.routes);
    }
    public boolean[] open() { return open.clone(); }
    public int[] direct() { return direct.clone(); }
}
