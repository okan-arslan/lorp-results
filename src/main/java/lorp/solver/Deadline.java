package lorp.solver;

/** Wall-clock budget measured with a monotonic clock, shared by all node work. */
public final class Deadline {
    private final long start = System.nanoTime();
    private final double budget;

    public Deadline(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) throw new IllegalArgumentException("Invalid time budget");
        budget = seconds;
    }

    public double elapsedSeconds() { return (System.nanoTime() - start) / 1e9; }
    public double remainingSeconds() { return Math.max(0, budget - elapsedSeconds()); }
    public boolean expired() { return remainingSeconds() == 0; }
}
