package io.casehub.ras.runtime;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

public record FeatureLikelihood(
        List<String> values,
        double[][] likelihoods
) {
    public FeatureLikelihood {
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        values = List.copyOf(values);
        Objects.requireNonNull(likelihoods, "likelihoods");
        for (int i = 0; i < likelihoods.length; i++) {
            if (likelihoods[i].length != values.size()) {
                throw new IllegalArgumentException(
                        "likelihoods row " + i + " length (" + likelihoods[i].length
                        + ") must match values size (" + values.size() + ")");
            }
            for (int j = 0; j < likelihoods[i].length; j++) {
                if (Double.isNaN(likelihoods[i][j]) || likelihoods[i][j] <= 0.0) {
                    throw new IllegalArgumentException(
                            "likelihoods[" + i + "][" + j + "] must be > 0.0 and not NaN, got: "
                            + likelihoods[i][j]
                            + " — apply Laplace smoothing to avoid zero probabilities");
                }
            }
        }
        likelihoods = Arrays.stream(likelihoods)
                .map(row -> Arrays.copyOf(row, row.length))
                .toArray(double[][]::new);
    }
}
