package lorp.model;

public final class Settings {
    public final boolean capacitated;
    /** -1 permits all facilities; 0 permits none. */
    public final int facilityLimit;
    public final double fixedWeight;
    public final double routingWeight;
    public final double allocationWeight;

    public Settings(boolean capacitated, int facilityLimit, double fixedWeight,
                    double routingWeight, double allocationWeight) {
        if (facilityLimit < -1) throw new IllegalArgumentException("Invalid facility limit");
        Instance.nonnegativeFinite(fixedWeight, "fixed weight");
        Instance.nonnegativeFinite(routingWeight, "routing weight");
        Instance.nonnegativeFinite(allocationWeight, "allocation weight");
        this.capacitated = capacitated;
        this.facilityLimit = facilityLimit;
        this.fixedWeight = fixedWeight;
        this.routingWeight = routingWeight;
        this.allocationWeight = allocationWeight;
    }
}

