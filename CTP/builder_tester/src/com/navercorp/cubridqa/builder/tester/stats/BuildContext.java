package com.navercorp.cubridqa.builder.tester.stats;

import java.util.Objects;

/**
 * Immutable context describing a build configuration used for prediction.
 *
 * <p>Predictions may vary based on commit and baseline since different code
 * paths may exercise different resource demands. This context is used as
 * input to the predictor alongside test key and hardware.</p>
 */
public final class BuildContext {

    private final String commit;
    private final String baseline;

    public BuildContext(String commit, String baseline) {
        this.commit = Objects.requireNonNull(commit, "commit");
        this.baseline = Objects.requireNonNull(baseline, "baseline");
    }

    public String getCommit() {
        return commit;
    }

    public String getBaseline() {
        return baseline;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BuildContext)) return false;
        BuildContext that = (BuildContext) o;
        return commit.equals(that.commit) && baseline.equals(that.baseline);
    }

    @Override
    public int hashCode() {
        return Objects.hash(commit, baseline);
    }

    @Override
    public String toString() {
        return "BuildContext{commit=" + commit + ", baseline=" + baseline + "}";
    }
}
