package lorp.verification;

import java.util.List;
import lorp.model.Instance;
import lorp.model.Route;
import lorp.model.Settings;

/** Recompute feasibility and cost from customer visits, not route/master caches. */
public final class SolutionChecker {
    public static double check(Instance instance, Settings settings, boolean[] open,
                               int[] directFacility, List<Route> selectedRoutes) {
        if (open.length != instance.facilities || directFacility.length != instance.customers) {
            throw new IllegalArgumentException("Solution dimension mismatch");
        }
        int[] service = new int[instance.customers];
        long[] facilityLoad = new long[instance.facilities];
        int opened = 0;
        double objective = 0;
        for (int i = 0; i < instance.facilities; i++) {
            if (open[i]) {
                opened++;
                objective += settings.fixedWeight * instance.fixedCost(i);
            }
        }
        if (settings.facilityLimit >= 0 && opened > settings.facilityLimit) {
            throw new IllegalArgumentException("Facility count exceeds p");
        }
        for (int j = 0; j < instance.customers; j++) {
            int i = directFacility[j];
            if (i == -1) continue;
            if (i < 0 || i >= instance.facilities || !open[i]
                    || instance.distance(instance.facilityNode(i), j) > instance.coverageRange(i)) {
                throw new IllegalArgumentException("Invalid direct allocation");
            }
            service[j]++;
            facilityLoad[i] += instance.demand(j);
            objective += settings.allocationWeight * instance.distance(instance.facilityNode(i), j);
        }
        for (Route route : selectedRoutes) {
            int origin = route.facility;
            if (origin < 0 || origin >= instance.facilities || !open[origin]) {
                throw new IllegalArgumentException("Route starts at a closed facility");
            }
            boolean[] seen = new boolean[instance.customers];
            long demand = 0;
            double length = 0;
            int previous = instance.facilityNode(origin);
            for (int j : route.visits()) {
                if (j < 0 || j >= instance.customers || seen[j]) {
                    throw new IllegalArgumentException("Non-elementary route");
                }
                seen[j] = true;
                service[j]++;
                demand += instance.demand(j);
                length += instance.distance(previous, j);
                previous = j;
            }
            if (route.size() == 0) throw new IllegalArgumentException("Empty route");
            length += instance.distance(previous, instance.facilityNode(origin));
            if (demand > instance.vehicleCapacity || length > instance.routeLengthLimit) {
                throw new IllegalArgumentException("Route exceeds Q or T");
            }
            facilityLoad[origin] += demand;
            objective += settings.routingWeight * length;
        }
        for (int count : service) {
            if (count != 1) throw new IllegalArgumentException("Customer service is not exactly one");
        }
        if (settings.capacitated) {
            for (int i = 0; i < instance.facilities; i++) {
                if (facilityLoad[i] > instance.facilityCapacity(i)) {
                    throw new IllegalArgumentException("Facility capacity exceeded");
                }
            }
        }
        return objective;
    }
}

