package lorp;

import ilog.cplex.IloCplex;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.stream.Stream;
import lorp.io.LegacyBenchmarkReader;
import lorp.io.PilotResultWriter;
import lorp.io.ProgressRecorder;
import lorp.model.Instance;
import lorp.model.Settings;
import lorp.pricing.HeuristicThenExactPricer;
import lorp.pricing.MilpPricer;
import lorp.pricing.Pricer;
import lorp.pricing.PulsePricer;
import lorp.solver.BranchAndPrice;
import lorp.solver.Deadline;

/** Shared run entry point; production and workflow tests have explicit, distinct scopes. */
public final class RunMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: RunMain scope case.properties output.xml");
        run(new String[] {args[1], args[2]}, args[0]);
    }

    public static void run(String[] args, String scope) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected case.properties output.xml");
        if (!List.of("pilot", "workflow-test", "production").contains(scope)) throw new IllegalArgumentException("Unknown run scope");
        Path config = Path.of(args[0]).toAbsolutePath(), output = Path.of(args[1]);
        if (Files.exists(output)) throw new IllegalArgumentException("Attempt output already exists");
        Properties input = new Properties();
        try (InputStream stream = Files.newInputStream(config)) { input.load(stream); }
        Path benchmark = config.getParent().resolve(required(input, "benchmark.path")).normalize();
        double budget = Double.parseDouble(required(input, "time_limit_seconds"));
        if (!Double.isFinite(budget) || budget < 0 || (scope.equals("production") ? budget != 18000 : budget > 600)) {
            throw new IllegalArgumentException("Production budget must be 18000; pilot/test budget must be in 0..600 seconds");
        }
        double snapshotSeconds = scope.equals("pilot") ? 0 : number(input, "snapshot_seconds");
        if (!scope.equals("pilot") && (!Double.isFinite(snapshotSeconds) || snapshotSeconds <= 0 || snapshotSeconds > budget
                || scope.equals("production") && snapshotSeconds != 10800)) throw new IllegalArgumentException("Invalid snapshot cap");
        boolean capacitated = bool(input, "capacitated"), heuristics = bool(input, "heuristics");
        Settings settings = new Settings(capacitated, Integer.parseInt(required(input, "p")),
                number(input, "wF"), number(input, "wR"), number(input, "wA"));
        Instance instance = LegacyBenchmarkReader.read(benchmark, number(input, "R"), number(input, "T"));
        Properties provenance = new Properties(); provenance.putAll(input);
        provenance.setProperty("scope", scope);
        provenance.setProperty("benchmark.path", benchmark.toString());
        provenance.setProperty("data_sha256", sha256(benchmark)); provenance.setProperty("config_sha256", sha256(config));
        if (input.containsKey("expected_data_sha256") && !input.getProperty("expected_data_sha256").equals(provenance.getProperty("data_sha256"))) {
            throw new IllegalArgumentException("Benchmark checksum does not match manifest");
        }
        provenance.setProperty("build_sha256", buildHash());
        provenance.setProperty("java_version", System.getProperty("java.version"));
        provenance.setProperty("hostname", InetAddress.getLocalHost().getHostName());
        provenance.setProperty("solver_threads", "1");
        for (String key : List.of("LORP_RELEASE_ID", "LORP_RELEASE_SHA256")) {
            if (System.getenv(key) != null) provenance.setProperty(key, System.getenv(key));
        }
        for (String key : List.of("SLURM_JOB_ID", "SLURM_ARRAY_TASK_ID", "SLURM_CPUS_PER_TASK")) {
            if (System.getenv(key) != null) provenance.setProperty(key, System.getenv(key));
        }
        IloCplex version = new IloCplex();
        try { provenance.setProperty("cplex_version", version.getVersion()); } finally { version.end(); }
        String pricing = input.getProperty("pricing", "milp");
        if (!pricing.equals("milp") && !pricing.equals("pulse")) throw new IllegalArgumentException("Unknown pricing algorithm");
        Pricer pricer = pricing.equals("pulse") ? new PulsePricer() : new MilpPricer();
        provenance.setProperty("pricing", pricing);
        if (heuristics) pricer = new HeuristicThenExactPricer(pricer);
        Deadline deadline = new Deadline(budget);
        BranchAndPrice.Result result;
        if (scope.equals("pilot")) {
            result = BranchAndPrice.solve(instance, settings, pricer, deadline, heuristics);
        } else {
            double intervalSeconds = scope.equals("production") ? 900 : 0.1;
            provenance.setProperty("progress_interval_seconds", Double.toString(intervalSeconds));
            try (ProgressRecorder recorder = new ProgressRecorder(output.toAbsolutePath().getParent(), provenance, instance,
                    deadline, snapshotSeconds, intervalSeconds)) {
                result = BranchAndPrice.solve(instance, settings, pricer, deadline, heuristics, recorder);
            }
        }
        provenance.setProperty("record_kind", "final");
        PilotResultWriter.write(output, provenance, instance, result);
        System.out.println(result.status + " objective=" + (result.solution == null ? "unavailable" : result.solution.objective)
                + " bound=" + result.lowerBound + " nodes=" + result.nodes + " columns=" + result.columns);
    }

    private static String required(Properties input, String key) {
        String value = input.getProperty(key);
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Missing case field: " + key);
        return value;
    }
    private static double number(Properties input, String key) { return Double.parseDouble(required(input, key)); }
    private static boolean bool(Properties input, String key) {
        String value = required(input, key);
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("Invalid boolean: " + key);
        return Boolean.parseBoolean(value);
    }
    private static String sha256(Path path) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
    private static String buildHash() throws Exception {
        Path classes = Path.of(RunMain.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        List<Path> files = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(classes)) { walk.filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".class")).forEach(files::add); }
        files.sort(Comparator.comparing(path -> classes.relativize(path).toString()));
        for (Path file : files) {
            digest.update(classes.relativize(file).toString().getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0);
            digest.update(Files.readAllBytes(file)); digest.update((byte) 0);
        }
        return hex(digest.digest());
    }
    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255));
        return result.toString();
    }
}
