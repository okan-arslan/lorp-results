package lorp.pricing;

import lorp.model.Instance;
import lorp.model.Route;

/** Reduced cost = offset + visited-node terms + directed arc terms. */
public final class PricingCosts {
    public final int facility;
    public final double offset;
    public final double[] vertex;
    public final double[][] arc;

    public PricingCosts(int facility, double offset, double[] vertex, double[][] arc) {
        this.facility = facility;
        this.offset = offset;
        this.vertex = vertex.clone();
        this.arc = new double[arc.length][];
        for (int i = 0; i < arc.length; i++) this.arc[i] = arc[i].clone();
    }

    public double cost(Instance instance, Route route) {
        if (facility != route.facility) throw new IllegalArgumentException("Wrong pricing origin");
        double result = offset;
        int previous = instance.facilityNode(facility);
        for (int j : route.visits()) {
            result += vertex[j] + arc[previous][j];
            previous = j;
        }
        return result + arc[previous][instance.facilityNode(facility)];
    }
}
