package lorp.model;

import java.util.Arrays;

/** A validated elementary route, with an explicit facility index. */
public final class Route {
    public final int facility;
    public final double length;
    public final long demand;
    private final int[] visits;

    public Route(Instance instance, int facility, int[] visits) {
        if (facility < 0 || facility >= instance.facilities || visits.length == 0) {
            throw new IllegalArgumentException("Invalid origin or empty route");
        }
        this.facility = facility;
        this.visits = visits.clone();
        boolean[] seen = new boolean[instance.customers];
        long load = 0;
        double distance = 0;
        int previous = instance.facilityNode(facility);
        for (int customer : visits) {
            if (customer < 0 || customer >= seen.length || seen[customer]) {
                throw new IllegalArgumentException("Route is not elementary");
            }
            seen[customer] = true;
            load += instance.demand(customer);
            distance += instance.distance(previous, customer);
            previous = customer;
        }
        distance += instance.distance(previous, instance.facilityNode(facility));
        if (load > instance.vehicleCapacity || distance > instance.routeLengthLimit) {
            throw new IllegalArgumentException("Route exceeds Q or T");
        }
        demand = load;
        length = distance;
    }

    public int size() { return visits.length; }
    public int customer(int position) { return visits[position]; }
    public int[] visits() { return visits.clone(); }

    @Override
    public boolean equals(Object other) {
        if (!(other instanceof Route)) return false;
        Route route = (Route) other;
        return facility == route.facility && Arrays.equals(visits, route.visits);
    }

    @Override
    public int hashCode() { return 31 * facility + Arrays.hashCode(visits); }

    @Override
    public String toString() { return "facility=" + facility + " visits=" + Arrays.toString(visits); }
}

