package io.casehub.ras.api;

import java.util.Map;

/**
 * SPI for contributing domain-specific data to a case at creation time.
 *
 * <p>Implementations are discovered via CDI and called by {@link DefaultCaseTrigger}
 * during {@code buildInputData()}. Each contributor's output is merged into the
 * case input map after static {@code baseCaseData} and correlation metadata.
 */
public interface CaseInputContributor {
    Map<String, Object> contribute(CaseTriggerConfig config, SituationContext context);
}
