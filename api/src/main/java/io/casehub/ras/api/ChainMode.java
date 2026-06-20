package io.casehub.ras.api;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public sealed interface ChainMode {

    record And(Set<String> requiredGanglia) implements ChainMode {
        public And {
            if (requiredGanglia == null || requiredGanglia.isEmpty()) {
                throw new IllegalArgumentException("requiredGanglia must not be empty");
            }
            requiredGanglia = Set.copyOf(requiredGanglia);
        }
    }

    record Or(Set<String> ganglia) implements ChainMode {
        public Or {
            if (ganglia == null || ganglia.isEmpty()) {
                throw new IllegalArgumentException("ganglia must not be empty");
            }
            ganglia = Set.copyOf(ganglia);
        }
    }

    record Threshold(Set<String> ganglia, double minConfidence) implements ChainMode {
        public Threshold {
            if (ganglia == null || ganglia.isEmpty()) {
                throw new IllegalArgumentException("ganglia must not be empty");
            }
            ganglia = Set.copyOf(ganglia);
            if (minConfidence <= 0.0) {
                throw new IllegalArgumentException(
                        "minConfidence must be > 0.0, got: " + minConfidence);
            }
        }
    }

    record Sequence(List<String> orderedGanglia) implements ChainMode {
        public Sequence {
            if (orderedGanglia == null || orderedGanglia.isEmpty()) {
                throw new IllegalArgumentException("orderedGanglia must not be empty");
            }
            orderedGanglia = List.copyOf(orderedGanglia);
        }
    }

    record Count(String ganglionId, int requiredCount) implements ChainMode {
        public Count {
            Objects.requireNonNull(ganglionId, "ganglionId");
            if (requiredCount < 1) {
                throw new IllegalArgumentException(
                        "requiredCount must be >= 1, got: " + requiredCount);
            }
        }
    }
}
