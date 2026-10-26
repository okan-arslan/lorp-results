package lorp.master;

import ilog.concert.IloColumn;
import ilog.concert.IloLinearNumExpr;
import ilog.concert.IloLinearNumExprIterator;
import ilog.concert.IloNumVar;
import ilog.concert.IloNumVarType;
import ilog.concert.IloObjective;
import ilog.concert.IloRange;
import ilog.cplex.IloCplex;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;
import lorp.branching.Decision;
import lorp.model.Instance;
import lorp.model.Route;
import lorp.model.Settings;
import lorp.pricing.PricingCosts;
import lorp.solver.Deadline;

/** Every row, including a branch row, participates in phase I and route pricing. */
public final class RestrictedMaster implements AutoCloseable {
    private enum Sense { EQ, GE, LE }
    private enum Kind { SERVICE, CAPACITY, LINK, COUNT, ARC, OTHER }
    private static final class Explicit {
        final IloNumVar var;
        final double cost, upper;
        Explicit(IloNumVar var, double cost, double upper) { this.var = var; this.cost = cost; this.upper = upper; }
    }
    private static final class Row {
        final IloRange range;
        final Sense sense;
        final Kind kind;
        final double rhs;
        final int facility, customer;
        final Decision branch;
        final Map<Integer, Double> explicit;
        final ToDoubleFunction<Route> route;
        Row(IloRange range, Sense sense, Kind kind, double rhs, int facility, int customer,
            Decision branch, Map<Integer, Double> explicit, ToDoubleFunction<Route> route) {
            this.range = range; this.sense = sense; this.kind = kind; this.rhs = rhs;
            this.facility = facility; this.customer = customer; this.branch = branch;
            this.explicit = Map.copyOf(explicit); this.route = route;
        }
    }

    public final Instance instance;
    public final Settings settings;
    private final IloCplex model;
    private final IloObjective objective;
    private final IloNumVar[] x, v;
    private final IloNumVar[][] z;
    private final List<Explicit> explicit = new ArrayList<>();
    private final Map<IloNumVar, Integer> indices = new IdentityHashMap<>();
    private final List<Row> rows = new ArrayList<>();
    private final List<IloNumVar> artificial = new ArrayList<>();
    private final Map<Route, IloNumVar> columns = new LinkedHashMap<>();
    private boolean phaseOne = true;
    private double[] dual;

    public RestrictedMaster(Instance instance, Settings settings, List<Decision> decisions) throws Exception {
        this.instance = instance; this.settings = settings;
        model = new IloCplex();
        try {
            model.setOut(null);
            model.setParam(IloCplex.Param.Threads, 1);
            model.setParam(IloCplex.Param.Simplex.Tolerances.Feasibility, 1e-9);
            model.setParam(IloCplex.Param.Simplex.Tolerances.Optimality, 1e-9);
            objective = model.addMinimize(model.linearNumExpr());
            x = new IloNumVar[instance.facilities];
            v = new IloNumVar[instance.facilities];
            z = new IloNumVar[instance.facilities][instance.customers];
            for (int i = 0; i < instance.facilities; i++) {
                x[i] = variable(1, settings.fixedWeight * instance.fixedCost(i), "x_" + i);
                v[i] = variable(instance.customers, 0, "v_" + i);
                for (int j = 0; j < instance.customers; j++) if (instance.canCover(i, j)) {
                    z[i][j] = variable(1, settings.allocationWeight * instance.distance(instance.facilityNode(i), j), "z_" + i + "_" + j);
                }
            }
            long uncoverable = 0;
            for (int j = 0; j < instance.customers; j++) {
                final int customer = j;
                IloLinearNumExpr service = model.linearNumExpr();
                boolean covered = false;
                for (int i = 0; i < instance.facilities; i++) if (z[i][j] != null) {
                    service.addTerm(1, z[i][j]); covered = true;
                }
                addRow(service, Sense.EQ, 1, Kind.SERVICE, -1, j, null,
                        route -> contains(route, customer) ? 1 : 0);
                if (!covered) uncoverable += instance.demand(j);
            }
            IloLinearNumExpr vehicles = model.linearNumExpr(), facilities = model.linearNumExpr();
            for (int i = 0; i < instance.facilities; i++) {
                final int facility = i;
                IloLinearNumExpr capacity = model.linearNumExpr();
                capacity.addTerm(instance.facilityCapacity(i), x[i]);
                for (int j = 0; j < instance.customers; j++) {
                    final int customer = j;
                    if (z[i][j] != null) {
                        capacity.addTerm(-instance.demand(j), z[i][j]);
                        IloLinearNumExpr allocation = model.linearNumExpr();
                        allocation.addTerm(1, x[i]); allocation.addTerm(-1, z[i][j]);
                        addRow(allocation, Sense.GE, 0, Kind.OTHER, i, j, null, route -> 0);
                    }
                    IloLinearNumExpr link = model.linearNumExpr();
                    link.addTerm(1, x[i]);
                    addRow(link, Sense.GE, 0, Kind.LINK, i, j, null,
                            route -> route.facility == facility && contains(route, customer) ? -1 : 0);
                }
                if (settings.capacitated) addRow(capacity, Sense.GE, 0, Kind.CAPACITY, i, -1, null,
                        route -> route.facility == facility ? -route.demand : 0);
                IloLinearNumExpr count = model.linearNumExpr();
                count.addTerm(-1, v[i]);
                addRow(count, Sense.EQ, 0, Kind.COUNT, i, -1, null, route -> route.facility == facility ? 1 : 0);
                vehicles.addTerm(1, v[i]); facilities.addTerm(1, x[i]);
            }
            addRow(vehicles, Sense.GE, Math.ceil(uncoverable / instance.vehicleCapacity), Kind.OTHER, -1, -1, null, route -> 0);
            if (settings.facilityLimit >= 0) addRow(facilities, Sense.LE, settings.facilityLimit,
                    Kind.OTHER, -1, -1, null, route -> 0);
            for (Decision branch : decisions) {
                IloLinearNumExpr expr = model.linearNumExpr();
                if (branch.kind == Decision.Kind.X) expr.addTerm(1, x[branch.facility]);
                if (branch.kind == Decision.Kind.V) expr.addTerm(1, v[branch.facility]);
                if (branch.kind == Decision.Kind.Z && z[branch.facility][branch.customer] != null) expr.addTerm(1, z[branch.facility][branch.customer]);
                addRow(expr, branch.sense == Decision.Sense.LE ? Sense.LE : Sense.GE, branch.rhs,
                        branch.kind == Decision.Kind.ARC ? Kind.ARC : Kind.OTHER, branch.facility,
                        branch.customer, branch, route -> branch.routeCoefficient(route, instance.facilityNode(branch.facility)));
            }
            for (Row row : rows) {
                if (row.sense != Sense.LE) artificial.add(artificial(row, 1));
                if (row.sense != Sense.GE) artificial.add(artificial(row, -1));
            }
        } catch (Exception | Error failure) {
            model.end();
            throw failure;
        }
    }

    private IloNumVar variable(double upper, double cost, String name) throws Exception {
        IloNumVar var = model.numVar(0, upper, name);
        indices.put(var, explicit.size()); explicit.add(new Explicit(var, cost, upper));
        return var;
    }

    private void addRow(IloLinearNumExpr expr, Sense sense, double rhs, Kind kind, int facility,
                        int customer, Decision branch, ToDoubleFunction<Route> coefficient) throws Exception {
        IloRange range = sense == Sense.EQ ? model.addEq(expr, rhs)
                : sense == Sense.GE ? model.addGe(expr, rhs) : model.addLe(expr, rhs);
        Map<Integer, Double> coefficients = new LinkedHashMap<>();
        for (IloLinearNumExprIterator iterator = expr.linearIterator(); iterator.hasNext();) {
            IloNumVar var = iterator.nextNumVar();
            coefficients.merge(indices.get(var), iterator.getValue(), Double::sum);
        }
        rows.add(new Row(range, sense, kind, rhs, facility, customer, branch, coefficients, coefficient));
    }

    private IloNumVar artificial(Row row, double sign) throws Exception {
        return model.numVar(model.column(objective, 1).and(model.column(row.range, sign)),
                0, Double.POSITIVE_INFINITY);
    }

    public void add(Route route) throws Exception {
        if (columns.containsKey(route)) return;
        IloColumn column = model.column(objective, phaseOne ? 0 : settings.routingWeight * route.length);
        for (Row row : rows) {
            double coefficient = row.route.applyAsDouble(route);
            if (coefficient != 0) column = column.and(model.column(row.range, coefficient));
        }
        columns.put(route, model.numVar(column, 0, Double.POSITIVE_INFINITY, "y_" + columns.size()));
    }

    public boolean solve(Deadline deadline) throws Exception {
        if (deadline.expired()) return false;
        model.setParam(IloCplex.Param.TimeLimit, deadline.remainingSeconds());
        if (!model.solve() || model.getStatus() != IloCplex.Status.Optimal) return false;
        dual = new double[rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r);
            double value = model.getDual(row.range);
            value = row.sense == Sense.GE ? Math.max(0, value) : row.sense == Sense.LE ? Math.min(0, value) : value;
            // These additional phase-I bounds make both artificial reduced costs nonnegative.
            dual[r] = phaseOne ? Math.max(-1, Math.min(1, value)) : value;
        }
        return true;
    }

    public void startPhaseTwo() throws Exception {
        phaseOne = false;
        for (IloNumVar var : artificial) var.setUB(0);
        for (Explicit var : explicit) model.setLinearCoef(objective, var.var, var.cost);
        for (Map.Entry<Route, IloNumVar> column : columns.entrySet()) {
            model.setLinearCoef(objective, column.getValue(), settings.routingWeight * column.getKey().length);
        }
    }

    public double objective() throws Exception { return model.getObjValue(); }

    public PricingCosts pricingCosts(int facility) {
        double offset = 0;
        double[] vertex = new double[instance.customers];
        int size = instance.customers + instance.facilities;
        double[][] arc = new double[size][size];
        for (int a = 0; a < size; a++) for (int b = 0; b < size; b++) {
            arc[a][b] = (phaseOne ? 0 : settings.routingWeight) * instance.distance(a, b);
        }
        for (int r = 0; r < rows.size(); r++) {
            Row row = rows.get(r); double lambda = dual[r];
            if (row.kind == Kind.SERVICE) vertex[row.customer] -= lambda;
            if (row.facility != facility) continue;
            if (row.kind == Kind.LINK) vertex[row.customer] += lambda;
            if (row.kind == Kind.CAPACITY) for (int j = 0; j < vertex.length; j++) vertex[j] += instance.demand(j) * lambda;
            if (row.kind == Kind.COUNT) offset -= lambda;
            if (row.kind == Kind.ARC) arc[row.branch.from][row.branch.to] -= lambda;
        }
        return new PricingCosts(facility, offset, vertex, arc);
    }

    public double reducedCost(Route route) {
        double cost = phaseOne ? 0 : settings.routingWeight * route.length;
        for (int r = 0; r < rows.size(); r++) cost -= dual[r] * rows.get(r).route.applyAsDouble(route);
        return cost;
    }

    /** Lagrangian bound, including finite explicit bounds and sum(route values) <= n. */
    public double lowerBound(double minimumRouteReducedCostBound) {
        if (!Double.isFinite(minimumRouteReducedCostBound) && minimumRouteReducedCostBound < 0) return Double.NEGATIVE_INFINITY;
        double bound = 0;
        for (int r = 0; r < rows.size(); r++) bound += dual[r] * rows.get(r).rhs;
        double[] reduced = new double[explicit.size()];
        for (int k = 0; k < explicit.size(); k++) reduced[k] = phaseOne ? 0 : explicit.get(k).cost;
        for (int r = 0; r < rows.size(); r++) for (Map.Entry<Integer, Double> term : rows.get(r).explicit.entrySet()) {
            reduced[term.getKey()] -= dual[r] * term.getValue();
        }
        for (int k = 0; k < explicit.size(); k++) bound += Math.min(0, reduced[k]) * explicit.get(k).upper;
        if (phaseOne) {
            // Artificial columns have infinite upper bounds; any negative RC invalidates this bound.
            for (int r = 0; r < rows.size(); r++) {
                Row row = rows.get(r);
                if ((row.sense != Sense.LE && 1 - dual[r] < 0)
                        || (row.sense != Sense.GE && 1 + dual[r] < 0)) return Double.NEGATIVE_INFINITY;
            }
        }
        return bound + instance.customers * Math.min(0, minimumRouteReducedCostBound);
    }

    public Snapshot snapshot() throws Exception {
        double[] opened = model.getValues(x), vehicles = model.getValues(v);
        double[][] allocation = new double[instance.facilities][instance.customers];
        for (int i = 0; i < instance.facilities; i++) for (int j = 0; j < instance.customers; j++) {
            if (z[i][j] != null) allocation[i][j] = model.getValue(z[i][j]);
        }
        Map<Route, Double> routes = new LinkedHashMap<>();
        for (Map.Entry<Route, IloNumVar> column : columns.entrySet()) routes.put(column.getKey(), model.getValue(column.getValue()));
        return new Snapshot(opened, vehicles, allocation, routes);
    }

    /** Restricted-MILP heuristic only; infeasibility here is not a full-problem proof. */
    public Snapshot integerHeuristic(Deadline deadline, double seconds) throws Exception {
        if (deadline.expired()) return null;
        if (phaseOne) startPhaseTwo();
        model.add(model.conversion(x, IloNumVarType.Bool));
        model.add(model.conversion(v, IloNumVarType.Int));
        for (IloNumVar[] row : z) for (IloNumVar var : row) if (var != null) model.add(model.conversion(var, IloNumVarType.Bool));
        for (IloNumVar var : columns.values()) {
            var.setUB(1);
            model.add(model.conversion(var, IloNumVarType.Bool));
        }
        model.setParam(IloCplex.Param.TimeLimit, Math.min(seconds, deadline.remainingSeconds()));
        model.setParam(IloCplex.Param.MIP.Tolerances.Integrality, 1e-9);
        return model.solve() ? snapshot() : null;
    }

    public static final class Snapshot {
        public final double[] x, v;
        public final double[][] z;
        public final Map<Route, Double> routes;
        Snapshot(double[] x, double[] v, double[][] z, Map<Route, Double> routes) {
            this.x = x; this.v = v; this.z = z; this.routes = routes;
        }
    }

    private static boolean contains(Route route, int customer) {
        for (int j : route.visits()) if (j == customer) return true;
        return false;
    }

    @Override public void close() { model.end(); }
}
