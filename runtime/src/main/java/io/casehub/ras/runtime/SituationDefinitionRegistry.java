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

    private record RegistrySnapshot(
            Map<String, List<SituationRegistration>> byEventType,
            Map<String, SituationRegistration> bySituationId,
            Set<String> situationIds,
            Duration maxCorrelationWindow
    ) {}

    private volatile RegistrySnapshot         snapshot;
    private final    Map<String, Ganglion>    gangliaById;
    private final    ExpressionEngineRegistry expressionRegistry;

    @Inject
    public SituationDefinitionRegistry(Instance<SituationDefinitionProvider> providers,
                                       Instance<Ganglion> ganglia,
                                       ExpressionEngineRegistry expressionRegistry) {
        this(toList(providers), toList(ganglia), expressionRegistry);
    }

    SituationDefinitionRegistry(List<SituationDefinitionProvider> providers,
                                List<Ganglion> ganglia,
                                ExpressionEngineRegistry expressionRegistry) {
        this.expressionRegistry = expressionRegistry;
        this.gangliaById        = ganglia.stream()
                                         .collect(Collectors.toMap(
                                                 Ganglion::ganglionId,
                                                 g -> g,
                                                 (g1, g2) -> {
                                                     throw new IllegalStateException(
                                                             "Duplicate ganglionId '" + g1.ganglionId()
                                                             + "' — found in " + g1.getClass().getName()
                                                             + " and " + g2.getClass().getName());
                                                 }));

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
                                List<Ganglion> ganglia) {
        this(providers, ganglia, null);
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
            if ("jq".equals(stringEval.type()) && Map.class.isAssignableFrom(contextType)) {
                CompiledExpression<com.fasterxml.jackson.databind.JsonNode, ?> jqExpr =
                        expressionRegistry.compile(stringEval.type(), stringEval.expression(),
                                                   com.fasterxml.jackson.databind.JsonNode.class, resultType);
                return (CompiledExpression<C, R>) new JqMapAdapter<>(jqExpr, resultType);
            }
            return expressionRegistry.compile(stringEval.type(), stringEval.expression(),
                                              contextType, resultType);
        }
        throw new IllegalStateException(
                "Unknown ExpressionEvaluator type: " + evaluator.getClass().getName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static class JqMapAdapter<R> implements CompiledExpression<Map, R> {
        private static final com.fasterxml.jackson.databind.ObjectMapper                    MAPPER =
                new com.fasterxml.jackson.databind.ObjectMapper();
        private final        CompiledExpression<com.fasterxml.jackson.databind.JsonNode, ?> delegate;
        private final        Class<R>                                                       resultType;

        JqMapAdapter(CompiledExpression<com.fasterxml.jackson.databind.JsonNode, ?> delegate,
                     Class<R> resultType) {
            this.delegate   = delegate;
            this.resultType = resultType;
        }

        @Override
        public String type() {return "jq";}

        @Override
        public R eval(Map context) {
            com.fasterxml.jackson.databind.JsonNode node   = MAPPER.valueToTree(context);
            Object                                  result = delegate.eval(node);
            if (result instanceof java.util.List<?> list && !list.isEmpty()) {
                com.fasterxml.jackson.databind.JsonNode first =
                        (com.fasterxml.jackson.databind.JsonNode) list.getFirst();
                if (resultType == String.class) {
                    return (R) (first.isNull() ? null : first.asText());
                }
                if (resultType == Boolean.class) {
                    return (R) Boolean.valueOf(first.asBoolean());
                }
                return (R) MAPPER.convertValue(first, resultType);
            }
            return (R) result;
        }
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

    private static <T> List<T> toList(Instance<T> instance) {
        List<T> list = new ArrayList<>();
        instance.forEach(list::add);
        return list;
    }
}
