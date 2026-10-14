package com.navercorp.cubridqa.builder.docker;

import com.navercorp.cubridqa.builder.logging.RequestIds;

import java.util.List;

/**
 * Labels on every container Builder-Tester starts. Cleanup selects containers by label, so a
 * cancel or a new build only touches its own request's containers, never those of other
 * requests or of another Builder/Tester sharing the Docker daemon.
 */
public final class DockerLabels {

    /** Request whose test or build the container runs. */
    public static final String REQUEST = "builder_tester.request";
    /** Failed-test container kept alive for debugging; removed by the next build request. */
    public static final String KEEP = "builder_tester.keep";

    private DockerLabels() {}

    /** Adds {@code --label builder_tester.request=<id>} to a docker run command (no-op for invalid IDs). */
    public static void addRequestLabel(List<String> dockerRunCommand, String requestId) {
        if (RequestIds.isValid(requestId)) {
            dockerRunCommand.add("--label");
            dockerRunCommand.add(REQUEST + "=" + requestId);
        }
    }

    /** {@code docker ps} filter selecting one request's containers. */
    public static String requestFilter(String requestId) {
        return "label=" + REQUEST + "=" + RequestIds.require(requestId);
    }
}
