package com.solstice.cost;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public class CostRecorder {

    private static final Logger log = LoggerFactory.getLogger(CostRecorder.class);
    private static final Duration IMPLAUSIBLE_UPPER_BOUND = Duration.ofHours(2);

    private final Map<String, Instant> suspendStartedAt = new ConcurrentHashMap<>();
    private final Map<String, Instant> resumeStartedAt = new ConcurrentHashMap<>();

    public void suspendRequested(String jobName, Instant at) {
        suspendStartedAt.put(jobName, at);
    }

    public void resumeRequested(String jobName, Instant at) {
        resumeStartedAt.put(jobName, at);
    }

    public Optional<CostObservation> suspendObserved(String jobName, Instant at, JobFeatures features) {
        Instant started = suspendStartedAt.remove(jobName);
        if (started == null) {
            return Optional.empty();
        }
        Duration taken = Duration.between(started, at);
        if (!isPlausible(taken)) {
            log.debug("Discarding implausible savepoint duration {}s for {}", taken.toSeconds(), jobName);
            return Optional.empty();
        }
        return Optional.of(CostObservation.savepoint(jobName, at, taken, features));
    }

    public Optional<CostObservation> resumeObserved(String jobName, Instant at, JobFeatures features) {
        Instant started = resumeStartedAt.remove(jobName);
        if (started == null) {
            return Optional.empty();
        }
        Duration taken = Duration.between(started, at);
        if (!isPlausible(taken)) {
            log.debug("Discarding implausible restart duration {}s for {}", taken.toSeconds(), jobName);
            return Optional.empty();
        }
        return Optional.of(CostObservation.restart(jobName, at, taken, features));
    }

    public boolean isAwaitingSuspend(String jobName) {
        return suspendStartedAt.containsKey(jobName);
    }

    public boolean isAwaitingResume(String jobName) {
        return resumeStartedAt.containsKey(jobName);
    }

    public void forget(String jobName) {
        suspendStartedAt.remove(jobName);
        resumeStartedAt.remove(jobName);
    }

    private boolean isPlausible(Duration taken) {
        return !taken.isNegative()
                && !taken.isZero()
                && taken.compareTo(IMPLAUSIBLE_UPPER_BOUND) < 0;
    }
}
