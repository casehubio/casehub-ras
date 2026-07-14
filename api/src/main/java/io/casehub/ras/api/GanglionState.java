package io.casehub.ras.api;

import java.util.OptionalLong;

public record GanglionState(double[] values, OptionalLong storeVersion) {}
