package lorp.io;

import java.nio.file.Path;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lorp.model.Instance;
import lorp.solver.BranchAndPrice;
import lorp.solver.Deadline;

/** Publishes immutable, conservative states; the writer thread never accesses a solver. */
public final class ProgressRecorder implements BranchAndPrice.Observer, AutoCloseable {
    private final AtomicReference<BranchAndPrice.Result> latest = new AtomicReference<>();
    private final AtomicReference<BranchAndPrice.Result> beforeSnapshot = new AtomicReference<>();
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private final ScheduledExecutorService writer = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "lorp-result-writer"); thread.setDaemon(true); return thread;
    });
    private final Path directory;
    private final Properties provenance;
    private final Instance instance;
    private final Deadline deadline;
    private final double snapshotSeconds;
    private int sequence;
    private boolean snapshotWritten;

    public ProgressRecorder(Path directory, Properties provenance, Instance instance, Deadline deadline,
                            double snapshotSeconds, double intervalSeconds) {
        if (!Double.isFinite(snapshotSeconds) || snapshotSeconds < 0
                || !Double.isFinite(intervalSeconds) || intervalSeconds <= 0) throw new IllegalArgumentException("Invalid progress schedule");
        this.directory = directory; this.provenance = new Properties(); this.provenance.putAll(provenance);
        this.instance = instance; this.deadline = deadline; this.snapshotSeconds = snapshotSeconds;
        // The zero bound is known before optimization, even if the first callback misses the cap.
        BranchAndPrice.Result initial = BranchAndPrice.initialState();
        latest.set(initial); beforeSnapshot.set(initial);
        writer.scheduleAtFixedRate(this::periodic, 0, Math.max(1, (long) (intervalSeconds * 1000)), TimeUnit.MILLISECONDS);
    }

    @Override public void accept(BranchAndPrice.Result state) throws Exception {
        if (failure.get() != null) throw failure.get();
        latest.set(state);
        // A delayed writer must never include post-cap information in the comparison snapshot.
        if (state.elapsedSeconds <= snapshotSeconds) beforeSnapshot.set(state);
    }

    private void periodic() {
        try {
            BranchAndPrice.Result state = latest.get();
            if (state != null) {
                write(directory.resolve(String.format(java.util.Locale.ROOT, "progress-%06d.xml", sequence++)), state, "progress", deadline.elapsedSeconds());
                if (!snapshotWritten && deadline.elapsedSeconds() >= snapshotSeconds) snapshot();
            }
        } catch (Exception exception) { failure.compareAndSet(null, exception); }
    }

    private void snapshot() throws Exception {
        BranchAndPrice.Result state = beforeSnapshot.get();
        if (state == null) return;
        write(directory.resolve("snapshot.xml"), state, "comparison_snapshot", deadline.elapsedSeconds());
        snapshotWritten = true;
    }

    private void write(Path path, BranchAndPrice.Result state, String kind, double capturedAt) throws Exception {
        Properties fields = new Properties(); fields.putAll(provenance);
        fields.setProperty("record_kind", kind);
        fields.setProperty("capture_elapsed_seconds", Double.toString(capturedAt));
        fields.setProperty("snapshot_seconds", Double.toString(snapshotSeconds));
        fields.setProperty("state_age_seconds", Double.toString(Math.max(0, capturedAt - state.elapsedSeconds)));
        PilotResultWriter.write(path, fields, instance, state);
    }

    @Override public void close() throws Exception {
        writer.shutdown();
        if (!writer.awaitTermination(60, TimeUnit.SECONDS)) throw new IllegalStateException("Progress writer did not finish");
        if (failure.get() != null) throw failure.get();
        BranchAndPrice.Result state = latest.get();
        if (state != null) {
            write(directory.resolve(String.format(java.util.Locale.ROOT, "progress-%06d.xml", sequence++)), state, "progress", deadline.elapsedSeconds());
            if (!snapshotWritten) snapshot();
        }
    }
}
