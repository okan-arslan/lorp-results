package lorp.io;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import lorp.model.Instance;

/** Read the published LRP source format; legacy vehicle/demand costs are deliberately excluded. */
public final class LegacyBenchmarkReader {
    public static Instance read(Path path, double coverageRange, double routeLimit) throws Exception {
        List<String> lines = new ArrayList<>();
        for (String line : Files.readAllLines(path)) if (!line.trim().isEmpty()) lines.add(line);
        if (lines.size() < 3) throw new IllegalArgumentException("Invalid benchmark header");
        int n, m, q, mode;
        try (Scanner header = scan(lines.get(0)); Scanner metadata = scan(lines.get(1))) {
            n = header.nextInt(); m = header.nextInt(); q = header.nextInt();
            metadata.nextDouble(); metadata.nextDouble(); mode = metadata.nextInt();
        }
        if (n <= 0 || m <= 0 || q <= 0 || mode < 0 || mode > 2 || lines.size() != n + m + 2) {
            throw new IllegalArgumentException("Invalid benchmark dimensions or distance mode");
        }
        double[] px = new double[n + m], py = new double[n + m];
        int[] demand = new int[n];
        double[] cost = new double[m], capacity = new double[m], range = new double[m];
        for (int node = 0; node < n + m; node++) {
            try (Scanner fields = scan(lines.get(node + 2))) {
                if (fields.nextInt() != node + 1) throw new IllegalArgumentException("Invalid node index");
                px[node] = fields.nextDouble(); py[node] = fields.nextDouble();
                if (!Double.isFinite(px[node]) || !Double.isFinite(py[node])) throw new IllegalArgumentException("Nonfinite coordinates");
                double value = fields.nextDouble();
                if (node < n) {
                    if (!Double.isFinite(value) || value < 0 || value != Math.rint(value) || value > Integer.MAX_VALUE) {
                        throw new IllegalArgumentException("Invalid customer demand");
                    }
                    demand[node] = (int) value;
                } else {
                    cost[node - n] = value; capacity[node - n] = fields.nextDouble(); range[node - n] = coverageRange;
                }
            }
        }
        double[][] distance = new double[n + m][n + m];
        double maximum = 0;
        for (int i = 0; i < distance.length; i++) for (int j = i + 1; j < distance.length; j++) {
            double raw = Math.hypot(px[i] - px[j], py[i] - py[j]);
            if (mode == 1) raw = Math.ceil(raw);
            if (mode == 2) raw = Math.floor(raw + 0.5);
            distance[i][j] = distance[j][i] = raw; maximum = Math.max(maximum, raw);
        }
        if (!Double.isFinite(maximum) || maximum <= 0) throw new IllegalArgumentException("Cannot normalize distances");
        for (double[] row : distance) for (int j = 0; j < row.length; j++) row[j] = row[j] * 100 / maximum;
        return new Instance(demand, cost, capacity, range, distance, q, routeLimit);
    }
    private static Scanner scan(String line) { return new Scanner(line).useLocale(Locale.ROOT); }
}
