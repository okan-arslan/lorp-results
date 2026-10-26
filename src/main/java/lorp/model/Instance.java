package lorp.model;

/** Customers occupy 0..n-1; facilities occupy n..n+m-1 in the distance matrix. */
public final class Instance {
    public final int customers;
    public final int facilities;
    public final double vehicleCapacity;
    public final double routeLengthLimit;
    private final int[] demands;
    private final double[] fixedCosts;
    private final double[] capacities;
    private final double[] ranges;
    private final double[][] distances;

    public Instance(int[] demands, double[] fixedCosts, double[] capacities,
                    double[] ranges, double[][] distances, double vehicleCapacity,
                    double routeLengthLimit) {
        customers = demands.length;
        facilities = fixedCosts.length;
        if (customers == 0 || facilities == 0 || capacities.length != facilities
                || ranges.length != facilities || distances.length != customers + facilities) {
            throw new IllegalArgumentException("Inconsistent instance dimensions");
        }
        nonnegativeFinite(vehicleCapacity, "vehicle capacity");
        if (vehicleCapacity == 0) {
            throw new IllegalArgumentException("Vehicle capacity must be positive");
        }
        nonnegativeFinite(routeLengthLimit, "route length limit");
        this.vehicleCapacity = vehicleCapacity;
        this.routeLengthLimit = routeLengthLimit;
        this.demands = demands.clone();
        this.fixedCosts = fixedCosts.clone();
        this.capacities = capacities.clone();
        this.ranges = ranges.clone();
        this.distances = new double[distances.length][];
        for (int q : demands) {
            if (q < 0) throw new IllegalArgumentException("Negative customer demand");
        }
        for (int i = 0; i < facilities; i++) {
            nonnegativeFinite(fixedCosts[i], "facility fixed cost");
            nonnegativeFinite(capacities[i], "facility capacity");
            nonnegativeFinite(ranges[i], "facility range");
        }
        for (int i = 0; i < distances.length; i++) {
            if (distances[i].length != distances.length) {
                throw new IllegalArgumentException("Distance matrix must be square");
            }
            this.distances[i] = distances[i].clone();
            for (double d : distances[i]) nonnegativeFinite(d, "distance");
            if (distances[i][i] != 0.0) {
                throw new IllegalArgumentException("Distance diagonal must be zero");
            }
        }
    }

    public int facilityNode(int facility) { return customers + facility; }
    public int demand(int customer) { return demands[customer]; }
    public double fixedCost(int facility) { return fixedCosts[facility]; }
    public double facilityCapacity(int facility) { return capacities[facility]; }
    public double coverageRange(int facility) { return ranges[facility]; }
    public double distance(int from, int to) { return distances[from][to]; }
    public boolean canCover(int facility, int customer) {
        return distance(facilityNode(facility), customer) <= coverageRange(facility);
    }

    public long totalDemand() {
        long total = 0;
        for (int demand : demands) total += demand;
        return total;
    }

    public static void nonnegativeFinite(double value, String name) {
        if (!Double.isFinite(value) || value < 0) {
            throw new IllegalArgumentException("Invalid " + name + ": " + value);
        }
    }
}

