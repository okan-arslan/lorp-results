package lorp.branching;

import lorp.model.Route;

/** A branch is an explicit master row, including depot arcs in global node indices. */
public final class Decision {
    public enum Kind { X, V, ARC, Z }
    public enum Sense { LE, GE }
    public final Kind kind;
    public final Sense sense;
    public final int facility, customer, from, to, rhs;

    private Decision(Kind kind, Sense sense, int facility, int customer, int from, int to, int rhs) {
        this.kind = kind; this.sense = sense; this.facility = facility;
        this.customer = customer; this.from = from; this.to = to; this.rhs = rhs;
    }

    public static Decision variable(Kind kind, int facility, int customer, Sense sense, int rhs) {
        if (kind == Kind.ARC) throw new IllegalArgumentException("Use arc factory");
        return new Decision(kind, sense, facility, customer, -1, -1, rhs);
    }

    public static Decision arc(int facility, int from, int to, Sense sense, int rhs) {
        return new Decision(Kind.ARC, sense, facility, -1, from, to, rhs);
    }

    public double routeCoefficient(Route route, int depot) {
        if (kind != Kind.ARC || route.facility != facility) return 0;
        int previous = depot;
        for (int j : route.visits()) {
            if (previous == from && j == to) return 1;
            previous = j;
        }
        return previous == from && depot == to ? 1 : 0;
    }

    @Override
    public String toString() {
        return kind + " facility=" + facility + " customer=" + customer + " arc=" + from + "->" + to
                + " " + sense + " " + rhs;
    }
}
