package lorp.io;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import lorp.branching.Decision;
import lorp.model.Instance;
import lorp.model.Route;
import lorp.model.Solution;
import lorp.solver.BranchAndPrice;

/** Standard Java Properties XML interchange; Python publishes JSON after a second check. */
public final class PilotResultWriter {
    public static void write(Path output, Properties provenance, Instance instance,
                             BranchAndPrice.Result result) throws Exception {
        if (Files.exists(output)) throw new IllegalArgumentException("Refusing to overwrite an existing attempt");
        Properties fields = new Properties(); fields.putAll(provenance);
        fields.setProperty("schema_version", "1"); fields.setProperty("scope", provenance.getProperty("scope", "pilot"));
        fields.setProperty("status", result.status.name());
        fields.setProperty("certificate_kind", result.status == BranchAndPrice.Status.OPTIMAL
                ? result.solution.objective == 0 && result.nodes == 0 ? "NONNEGATIVE_OBJECTIVE" : "BRANCH_AND_PRICE_TOLERANCE"
                : result.status == BranchAndPrice.Status.INFEASIBLE ? "INFEASIBILITY" : "INCOMPLETE_SEARCH");
        fields.setProperty("lower_bound", Double.toString(result.lowerBound));
        fields.setProperty("elapsed_seconds", Double.toString(result.elapsedSeconds));
        fields.setProperty("nodes", Long.toString(result.nodes)); fields.setProperty("columns", Integer.toString(result.columns));
        fields.setProperty("lp_iterations", Integer.toString(result.lpIterations));
        fields.setProperty("absolute_gap_tolerance", Double.toString(BranchAndPrice.ABS_GAP));
        fields.setProperty("relative_gap_tolerance", Double.toString(BranchAndPrice.REL_GAP));
        fields.setProperty("integrality_tolerance", Double.toString(BranchAndPrice.INTEGER_TOLERANCE));
        fields.setProperty("pricing_tolerance", "1.0E-8");
        fields.setProperty("customers", Integer.toString(instance.customers));
        fields.setProperty("facilities", Integer.toString(instance.facilities));
        if (result.rootBound != null) fields.setProperty("root_bound", result.rootBound.toString());
        for (Decision.Kind kind : Decision.Kind.values()) fields.setProperty("branches." + kind, Integer.toString(result.branches.getOrDefault(kind, 0)));
        Solution solution = result.solution;
        fields.setProperty("solution.checked_java", Boolean.toString(solution != null));
        if (solution != null) {
            fields.setProperty("objective", Double.toString(solution.objective));
            boolean[] open = solution.open(); int[] direct = solution.direct();
            for (int i = 0; i < open.length; i++) fields.setProperty("solution.open." + i, Boolean.toString(open[i]));
            for (int j = 0; j < direct.length; j++) fields.setProperty("solution.direct." + j, Integer.toString(direct[j]));
            fields.setProperty("solution.route_count", Integer.toString(solution.routes.size()));
            List<Route> routes = solution.routes;
            for (int p = 0; p < routes.size(); p++) {
                Route route = routes.get(p); String key = "solution.route." + p + ".";
                fields.setProperty(key + "facility", Integer.toString(route.facility));
                fields.setProperty(key + "size", Integer.toString(route.size()));
                for (int k = 0; k < route.size(); k++) fields.setProperty(key + "visit." + k, Integer.toString(route.customer(k)));
            }
        }
        output = output.toAbsolutePath(); Files.createDirectories(output.getParent());
        Path temporary = Files.createTempFile(output.getParent(), ".pilot-", ".tmp");
        try {
            try (OutputStream stream = Files.newOutputStream(temporary)) { fields.storeToXML(stream, "LoRP pilot result", "UTF-8"); }
            Files.createLink(output, temporary);
        } finally { Files.deleteIfExists(temporary); }
    }
}
