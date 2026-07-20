package io.casehub.ras.runtime;

import io.casehub.platform.api.expression.CompiledExpression;
import io.casehub.platform.api.expression.ExpressionEngineRegistry;
import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.StringExpressionEvaluator;
import io.casehub.ras.api.CorrelationKeyExtractor;
import io.casehub.ras.api.DefaultCorrelationKeyExtractor;
import io.casehub.ras.api.EventFilter;
import io.casehub.ras.api.Ganglion;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationDefinitionProvider;
import io.casehub.ras.api.SituationRegistration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@ApplicationScoped
public class SituationDefinitionRegistry {

    private static final java.util.logging.Logger LOG =
            java.util.logging.Logger.getLogger(SituationDefinitionRegistry.class.getName());
    private final    Map<String, Ganglion>    gangliaById;
    private final    ExpressionEngineRegistry expressionRegistry;
    private volatile RegistrySnapshot         snapshot;
    @Inject
    public SituationDefinitionRegistry(Instance<SituationDefinitionProvider> providers,
                                       Instance<Ganglion> ganglia,
                                       ExpressionEngineRegistry expressionRegistry,
                                       Instance<io.casehub.ras.api.GanglionStateStore> stateStore,
                                       Instance<io.micrometer.core.instrument.MeterRegistry> meterRegistryInstance) {
        this(toList(providers), toList(ganglia), expressionRegistry,
             stateStore.isResolvable() ? stateStore.get() : new InMemoryGanglionStateStore(),
             meterRegistryInstance != null && meterRegistryInstance.isResolvable()
             ? meterRegistryInstance.get() : null);
    }

    SituationDefinitionRegistry(List<SituationDefinitionProvider> providers,
                                List<Ganglion> cdiGanglia,
                                ExpressionEngineRegistry expressionRegistry,
                                io.casehub.ras.api.GanglionStateStore stateStore,
                                io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        this.expressionRegistry = expressionRegistry;
        this.gangliaById        = new HashMap<>();

        // Phase 1: descriptor ganglia (before CDI ganglia and before situation validation)
        for (var provider : providers) {
            for (var descriptor : provider.ganglionDescriptors()) {
                try {
                    Ganglion ganglion = constructGanglion(descriptor, stateStore, meterRegistry);
                    if (gangliaById.putIfAbsent(ganglion.ganglionId(), ganglion) != null) {
                        throw new IllegalStateException(
                                "Duplicate ganglionId: " + ganglion.ganglionId());
                    }
                } catch (IllegalArgumentException e) {
                    throw new IllegalStateException(
                            "Invalid ganglion descriptor '" + descriptor.ganglionId()
                            + "': " + e.getMessage(), e);
                }
            }
        }

        // Phase 2: CDI ganglia
        for (Ganglion g : cdiGanglia) {
            if (gangliaById.putIfAbsent(g.ganglionId(), g) != null) {
                throw new IllegalStateException("Duplicate ganglionId '" + g.ganglionId()
                                                + "' — declared in both YAML descriptor and CDI: " + g.getClass().getName());
            }
        }

        // Phase 3: situation registrations (all ganglia now in gangliaById)
        List<SituationRegistration> allRegistrations = new ArrayList<>();
        Set<String>                 seenSituationIds = new HashSet<>();
        for (var provider : providers) {
            for (var reg : provider.registrations()) {
                String sitId = reg.definition().situationId();
                if (!seenSituationIds.add(sitId)) {
                    throw new IllegalStateException(
                            "Duplicate situationId '" + sitId + "' across providers");
                }
                validate(reg.definition());
                allRegistrations.add(compileRegistration(reg));
            }
        }

        this.snapshot = buildSnapshot(allRegistrations);
    }

    SituationDefinitionRegistry(List<SituationDefinitionProvider> providers,
                                List<Ganglion> ganglia,
                                ExpressionEngineRegistry expressionRegistry) {
        this(providers, ganglia, expressionRegistry, new InMemoryGanglionStateStore(), null);
    }


    SituationDefinitionRegistry(List<SituationDefinitionProvider> providers,
                                List<Ganglion> ganglia) {
        this(providers, ganglia, null, new InMemoryGanglionStateStore(), null);
    }

    private static RegistrySnapshot buildSnapshot(List<SituationRegistration> registrations) {
        Map<String, List<SituationRegistration>> index = new HashMap<>();
        Map<String, SituationRegistration>       byId  = new HashMap<>();
        Set<String>                              ids   = new HashSet<>();
        for (var reg : registrations) {
            String sitId = reg.definition().situationId();
            ids.add(sitId);
            byId.put(sitId, reg);
            for (String eventType : reg.definition().eventTypes()) {
                index.computeIfAbsent(eventType, k -> new ArrayList<>()).add(reg);
            }
        }
        Duration maxWindow = registrations.stream()
                                          .map(r -> r.definition().correlationWindow())
                                          .filter(Objects::nonNull)
                                          .max(Comparator.naturalOrder())
                                          .orElse(null);

        return new RegistrySnapshot(
                Map.copyOf(index.entrySet().stream()
                                .collect(Collectors.toMap(Map.Entry::getKey, e -> List.copyOf(e.getValue())))),
                Map.copyOf(byId),
                Set.copyOf(ids),
                maxWindow);
    }

    private static <T> List<T> toList(Instance<T> instance) {
        List<T> list = new ArrayList<>();
        instance.forEach(list::add);
        return list;
    }

    public List<SituationRegistration> findByEventType(String eventType) {
        return snapshot.byEventType().getOrDefault(eventType, List.of());
    }

    public Ganglion ganglion(String ganglionId) {
        Ganglion g = gangliaById.get(ganglionId);
        if (g == null) {
            throw new IllegalArgumentException("Unknown ganglionId: " + ganglionId);
        }
        return g;
    }

    public Duration maxCorrelationWindow() {
        return snapshot.maxCorrelationWindow();
    }

    public int definitionCount() {
        return snapshot.situationIds().size();
    }

    @SuppressWarnings("unchecked")
    public Map<String, CompiledExpression<Map, Object>> getCompiledDynamicData(String situationId) {
        SituationRegistration reg = snapshot.bySituationId().get(situationId);
        return reg != null ? reg.compiledDynamicData() : null;
    }

    public synchronized void register(SituationRegistration registration) {
        String sitId = registration.definition().situationId();
        if (snapshot.situationIds().contains(sitId)) {
            throw new IllegalStateException("Duplicate situationId: " + sitId);
        }
        validate(registration.definition());

        List<SituationRegistration> all = new ArrayList<>();
        snapshot.byEventType().values().stream()
                .flatMap(List::stream)
                .distinct()
                .forEach(all::add);
        all.add(compileRegistration(registration));
        this.snapshot = buildSnapshot(all);
    }

    public synchronized void deregister(String situationId) {
        if (!snapshot.situationIds().contains(situationId)) {
            return;
        }
        List<SituationRegistration> remaining = snapshot.byEventType().values().stream()
                                                        .flatMap(List::stream)
                                                        .distinct()
                                                        .filter(reg -> !reg.definition().situationId().equals(situationId))
                                                        .toList();
        this.snapshot = buildSnapshot(remaining);
    }

    @SuppressWarnings("unchecked")
    private SituationRegistration compileRegistration(SituationRegistration registration) {
        SituationDefinition def = registration.definition();
        boolean hasExpressions = def.correlationKeyExpression() != null
                                 || def.eventFilter() != null
                                 || !def.dynamicCaseData().isEmpty();
        if (!hasExpressions) {
            return registration;
        }

        CorrelationKeyExtractor extractor = registration.correlationKeyExtractor();
        if (def.correlationKeyExpression() != null) {
            if (extractor != DefaultCorrelationKeyExtractor.INSTANCE) {
                LOG.warning("Situation '" + def.situationId()
                            + "' has both correlationKeyExpression and a custom CorrelationKeyExtractor"
                            + " — expression wins (definition is the spec)");
            }
            CompiledExpression<Map, String> compiled = compileExpression(
                    def.correlationKeyExpression(), def.situationId(), Map.class, String.class);
            extractor = event -> {
                Map<String, Object> ctx    = CloudEventExpressionContext.build(event);
                String              result = compiled.eval(ctx);
                return result != null ? result : "_singleton";
            };
        }

        EventFilter filter = registration.eventFilter();
        if (def.eventFilter() != null) {
            CompiledExpression<Map, Boolean> compiled = compileExpression(
                    def.eventFilter(), def.situationId(), Map.class, Boolean.class);
            filter = event -> {
                Map<String, Object> ctx    = CloudEventExpressionContext.build(event);
                Boolean             result = compiled.eval(ctx);
                return result != null && result;
            };
        }

        Map<String, CompiledExpression<Map, Object>> compiledDynamic = null;
        if (!def.dynamicCaseData().isEmpty()) {
            compiledDynamic = new LinkedHashMap<>();
            for (var entry : def.dynamicCaseData().entrySet()) {
                compiledDynamic.put(entry.getKey(), compileExpression(
                        entry.getValue(), def.situationId(), Map.class, Object.class));
            }
            compiledDynamic = Map.copyOf(compiledDynamic);
        }

        return new SituationRegistration(def, extractor, filter, compiledDynamic);
    }

    @SuppressWarnings("unchecked")
    private Ganglion constructGanglion(io.casehub.ras.api.GanglionDescriptor descriptor,
                                       io.casehub.ras.api.GanglionStateStore stateStore,
                                       io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        if (descriptor instanceof io.casehub.ras.api.GanglionDescriptor.NaiveBayes nb) {
            Map<String, CompiledExpression<Map, String>> compiledFeatures = new LinkedHashMap<>();
            for (var entry : nb.features().entrySet()) {
                var feature = entry.getValue();
                CompiledExpression<Map, String> compiled = compileExpression(
                        feature.expression(), nb.ganglionId(), Map.class, String.class);
                compiledFeatures.put(entry.getKey(), compiled);
            }

            var featureExtractor = new ExpressionFeatureExtractor(
                    nb.ganglionId(), compiledFeatures, meterRegistry);

            Map<String, FeatureLikelihood> features = new LinkedHashMap<>();
            for (var entry : nb.features().entrySet()) {
                features.put(entry.getKey(), new FeatureLikelihood(
                        entry.getValue().values(), entry.getValue().likelihoods()));
            }

            var signalMapping = new NaiveBayesSignalMapping(
                    nb.signalMapping().targetOutcome(),
                    nb.signalMapping().detectedThreshold(),
                    nb.signalMapping().weakThreshold(),
                    nb.signalMapping().antiThreshold());

            var config = new NaiveBayesConfig(
                    nb.ganglionId(), nb.handledEventTypes(),
                    nb.outcomes(), nb.priors(),
                    features, featureExtractor, signalMapping);

            return new NaiveBayesGanglion(config,
                                          stateStore != null ? stateStore : new InMemoryGanglionStateStore());
        }
        throw new IllegalStateException("Unsupported GanglionDescriptor type: "
                                        + descriptor.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    private <C, R> CompiledExpression<C, R> compileExpression(
            ExpressionEvaluator evaluator, String situationId,
            Class<C> contextType, Class<R> resultType) {
        if (evaluator instanceof CompiledExpression<?, ?> compiled) {
            return (CompiledExpression<C, R>) compiled;
        }
        if (evaluator instanceof StringExpressionEvaluator stringEval) {
            if (expressionRegistry == null || expressionRegistry.resolve(stringEval.type()).isEmpty()) {
                throw new IllegalStateException(
                        "Situation '" + situationId + "' uses expression type '"
                        + stringEval.type() + "' but no ExpressionEngine is registered for it"
                        + " — add casehub-platform-expression to the classpath");
            }
            CompiledExpression<C, R> compiled = expressionRegistry.compile(
                    stringEval.type(), stringEval.expression(), contextType, resultType);
            if ("jq".equals(stringEval.type())
                && resultType != Boolean.class
                && resultType != List.class) {
                return (CompiledExpression<C, R>) new JqResultUnwrapper<>(
                        (CompiledExpression<Map, ?>) compiled, resultType);
            }
            return compiled;
        }
        throw new IllegalStateException(
                "Unknown ExpressionEvaluator type: " + evaluator.getClass().getName());
    }

    private void validate(SituationDefinition def) {
        for (String ganglionId : def.chainMode().referencedGanglia()) {
            Ganglion g = gangliaById.get(ganglionId);
            if (g == null) {
                throw new IllegalStateException(
                        "Situation '" + def.situationId() + "' references unknown ganglion '" + ganglionId + "'");
            }
            Set<String> overlap = new HashSet<>(g.handledEventTypes());
            overlap.retainAll(def.eventTypes());
            if (overlap.isEmpty()) {
                throw new IllegalStateException(
                        "Ganglion '" + ganglionId + "' handles " + g.handledEventTypes()
                        + " but situation '" + def.situationId() + "' declares " + def.eventTypes()
                        + " — no overlap");
            }
        }
    }

    private record RegistrySnapshot(
            Map<String, List<SituationRegistration>> byEventType,
            Map<String, SituationRegistration> bySituationId,
            Set<String> situationIds,
            Duration maxCorrelationWindow
    ) {}
}
