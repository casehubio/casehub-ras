package io.casehub.ras.runtime;

import io.casehub.platform.api.expression.ExpressionEvaluator;
import io.casehub.platform.api.expression.JQExpressionEvaluator;
import io.casehub.platform.api.expression.MvelExpressionEvaluator;
import io.casehub.ras.api.CaseTriggerConfig;
import io.casehub.ras.api.ChainMode;
import io.casehub.ras.api.DetectionSignal;
import io.casehub.ras.api.GanglionDescriptor;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationDefinitionProvider;
import io.casehub.ras.api.SituationRegistration;
import io.casehub.ras.api.TriggerAction;
import io.casehub.ras.api.TriggerMode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@ApplicationScoped
public class YamlSituationDefinitionProvider implements SituationDefinitionProvider {

    private static final Logger LOG = Logger.getLogger(YamlSituationDefinitionProvider.class.getName());
    private static final java.util.regex.Pattern WHOLE_PARAM_PATTERN =
            java.util.regex.Pattern.compile("^\\$\\{([^}]+)}$");
    private static final java.util.regex.Pattern PARAM_PATTERN =
            java.util.regex.Pattern.compile("\\$\\{([^}]+)}");


    private final List<SituationRegistration> registrations;
    private final List<GanglionDescriptor>    ganglionDescriptors;

    @Inject
    YamlSituationDefinitionProvider(
            @ConfigProperty(name = "ras.situations.yaml",
                            defaultValue = "META-INF/ras-situations.yaml") String resourcePath) {
        Map<String, SituationTemplate> builtInTemplates = loadBuiltInTemplates();

        InputStream is = Thread.currentThread().getContextClassLoader()
                               .getResourceAsStream(resourcePath);
        if (is == null) {
            LOG.fine("No YAML situation definitions found at " + resourcePath);
            this.registrations       = List.of();
            this.ganglionDescriptors = List.of();
        } else {
            try (is) {
                var parsed = parseAll(is, builtInTemplates);
                this.registrations       = parsed.registrations();
                this.ganglionDescriptors = parsed.ganglionDescriptors();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + resourcePath, e);
            }
        }
    }

    public YamlSituationDefinitionProvider(InputStream yaml) {
        var parsed = parseAll(yaml, loadBuiltInTemplates());
        this.registrations       = parsed.registrations();
        this.ganglionDescriptors = parsed.ganglionDescriptors();
    }

    @Override
    public List<SituationRegistration> registrations() {
        return registrations;
    }

    @Override
    public List<GanglionDescriptor> ganglionDescriptors() {
        return ganglionDescriptors;
    }

    private record ParseResult(List<SituationRegistration> registrations,
                               List<GanglionDescriptor> ganglionDescriptors) {}

    record SituationTemplate(
            String id,
            String description,
            Map<String, ParameterDef> parameters,
            Map<String, Object> definition,
            List<Map<String, Object>> ganglia
    ) {}

    record ParameterDef(boolean required, Object defaultValue) {}

    private record SituationParseResult(
            List<SituationRegistration> registrations,
            List<GanglionDescriptor> bundledGanglia
    ) {}


    private static Map<String, SituationTemplate> loadBuiltInTemplates() {
        InputStream is = Thread.currentThread().getContextClassLoader()
                               .getResourceAsStream("META-INF/ras-situation-templates.yaml");
        if (is == null) {return Map.of();}
        try (is) {
            Map<String, Object> root = new Yaml().load(is);
            return root != null ? parseTemplates(root) : Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read built-in templates", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static ParseResult parseAll(InputStream yaml) {
        return parseAll(yaml, Map.of());
    }

    @SuppressWarnings("unchecked")
    private static ParseResult parseAll(InputStream yaml,
                                        Map<String, SituationTemplate> builtInTemplates) {
        Map<String, Object> root = new Yaml().load(yaml);
        if (root == null) {
            return new ParseResult(List.of(), List.of());
        }
        Map<String, SituationTemplate> templates = new LinkedHashMap<>(builtInTemplates);
        templates.putAll(parseTemplates(root));
        List<GanglionDescriptor> ganglia    = parseGanglia(root);
        SituationParseResult     sitResult  = parseSituations(root, templates);
        List<GanglionDescriptor> allGanglia = new ArrayList<>(ganglia);
        allGanglia.addAll(sitResult.bundledGanglia());
        return new ParseResult(sitResult.registrations(), List.copyOf(allGanglia));
    }

    @SuppressWarnings("unchecked")
    private static SituationParseResult parseSituations(Map<String, Object> root,
                                                        Map<String, SituationTemplate> templates) {
        if (!root.containsKey("situations")) {
            return new SituationParseResult(List.of(), List.of());
        }
        List<Map<String, Object>>   situations     = (List<Map<String, Object>>) root.get("situations");
        List<SituationRegistration> result         = new ArrayList<>(situations.size());
        List<GanglionDescriptor>    bundledGanglia = new ArrayList<>();
        for (Map<String, Object> sit : situations) {
            if (sit.containsKey("fromTemplate")) {
                String              templateId = (String) sit.get("fromTemplate");
                String              sitId      = String.valueOf(sit.getOrDefault("situationId", "<missing>"));
                Map<String, Object> resolved;
                try {
                    resolved = resolveTemplate(sit, templates);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            "Error resolving template '" + templateId
                            + "' for situation '" + sitId + "': " + e.getMessage(), e);
                }
                SituationTemplate template = templates.get(templateId);
                if (template.ganglia() != null && !template.ganglia().isEmpty()) {
                    Map<String, Object> resolvedParams      = buildResolvedParams(sit, template);
                    List<Object>        resolvedGangliaList = new ArrayList<>();
                    for (Map<String, Object> g : template.ganglia()) {
                        resolvedGangliaList.add(substituteParams(new LinkedHashMap<>(g), resolvedParams));
                    }
                    checkUnresolved(resolvedGangliaList, templateId);
                    Map<String, Object> gangliaRoot = Map.of("ganglia", resolvedGangliaList);
                    bundledGanglia.addAll(parseGanglia(gangliaRoot));
                }
                try {
                    result.add(parseSituation(resolved));
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            "Error parsing resolved template '" + templateId
                            + "' for situation '" + sitId + "': " + e.getMessage(), e);
                }
            } else {
                result.add(parseSituation(sit));
            }
        }
        return new SituationParseResult(List.copyOf(result), List.copyOf(bundledGanglia));
    }

    @SuppressWarnings("unchecked")
    private static List<GanglionDescriptor> parseGanglia(Map<String, Object> root) {
        List<Map<String, Object>> ganglia = (List<Map<String, Object>>) root.get("ganglia");
        if (ganglia == null) {return List.of();}
        List<GanglionDescriptor> result = new ArrayList<>(ganglia.size());
        for (Map<String, Object> g : ganglia) {
            String type = requireString(g, "type");
            result.add(switch (type) {
                case "naive-bayes" -> parseNaiveBayesGanglion(g);
                case "expression-rules" -> parseExpressionRulesGanglion(g);
                default -> throw new IllegalArgumentException(
                        "Unknown ganglion type '" + type + "' for ganglion '"
                        + g.getOrDefault("ganglionId", "<missing>") + "'");
            });
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static GanglionDescriptor.NaiveBayes parseNaiveBayesGanglion(Map<String, Object> map) {
        String       ganglionId = requireString(map, "ganglionId");
        List<String> eventTypes = (List<String>) map.get("handledEventTypes");
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "handledEventTypes must not be empty for ganglion '" + ganglionId + "'");
        }
        List<String> outcomes = (List<String>) map.get("outcomes");
        if (outcomes == null || outcomes.size() < 2) {
            throw new IllegalArgumentException(
                    "outcomes must have at least 2 entries for ganglion '" + ganglionId + "'");
        }
        List<Number> priorsList = (List<Number>) map.get("priors");
        if (priorsList == null) {
            throw new IllegalArgumentException("Missing priors for ganglion '" + ganglionId + "'");
        }
        double[] priors = priorsList.stream().mapToDouble(Number::doubleValue).toArray();

        Map<String, Object> featuresMap = (Map<String, Object>) map.get("features");
        if (featuresMap == null) {featuresMap = Map.of();}
        Map<String, GanglionDescriptor.NaiveBayes.Feature> features = new LinkedHashMap<>();
        for (var entry : featuresMap.entrySet()) {
            features.put(entry.getKey(),
                         parseNaiveBayesFeature((Map<String, Object>) entry.getValue(), ganglionId, entry.getKey()));
        }

        Map<String, Object> sigMap = (Map<String, Object>) map.get("signalMapping");
        if (sigMap == null) {
            throw new IllegalArgumentException("Missing signalMapping for ganglion '" + ganglionId + "'");
        }

        Map<String, String> outcomeGroundTruth = null;
        Map<String, Object> groundTruthMap = (Map<String, Object>) map.get("outcomeGroundTruth");
        if (groundTruthMap != null) {
            outcomeGroundTruth = new LinkedHashMap<>();
            for (var entry : groundTruthMap.entrySet()) {
                String value = entry.getValue().toString();
                if (!outcomes.contains(value)) {
                    throw new IllegalArgumentException(
                            "outcomeGroundTruth value '" + value + "' for label '"
                            + entry.getKey() + "' is not in outcomes " + outcomes
                            + " for ganglion '" + ganglionId + "'");
                }
                outcomeGroundTruth.put(entry.getKey(), value);
            }
        }

        return new GanglionDescriptor.NaiveBayes(
                ganglionId, new LinkedHashSet<>(eventTypes), outcomes, priors,
                features, parseSignalMapping(sigMap),
                parseEvidenceTemplates(map),
                parseOutcomeEvidenceTemplates(map, outcomes, ganglionId),
                outcomeGroundTruth);
    }

    @SuppressWarnings("unchecked")
    private static GanglionDescriptor.ExpressionRules parseExpressionRulesGanglion(Map<String, Object> map) {
        String       ganglionId = requireString(map, "ganglionId");
        List<String> eventTypes = (List<String>) map.get("handledEventTypes");
        if (eventTypes == null || eventTypes.isEmpty()) {
            throw new IllegalArgumentException(
                    "handledEventTypes must not be empty for ganglion '" + ganglionId + "'");
        }

        List<Map<String, Object>> rulesList = (List<Map<String, Object>>) map.get("rules");
        if (rulesList == null || rulesList.isEmpty()) {
            throw new IllegalArgumentException(
                    "rules must not be empty for ganglion '" + ganglionId + "'");
        }

        List<GanglionDescriptor.ExpressionRules.Rule> rules = new ArrayList<>();
        for (int i = 0; i < rulesList.size(); i++) {
            Map<String, Object> ruleMap      = rulesList.get(i);
            boolean             hasWhen      = ruleMap.containsKey("when");
            boolean             hasOtherwise = ruleMap.containsKey("otherwise");

            if (hasWhen && hasOtherwise) {
                throw new IllegalArgumentException(
                        "Rule " + i + " in ganglion '" + ganglionId
                        + "' has both 'when' and 'otherwise' — mutually exclusive");
            }
            if (!hasWhen && !hasOtherwise) {
                throw new IllegalArgumentException(
                        "Rule " + i + " in ganglion '" + ganglionId
                        + "' has neither 'when' nor 'otherwise'");
            }
            if (hasOtherwise && i != rulesList.size() - 1) {
                throw new IllegalArgumentException(
                        "'otherwise' must be the last rule in ganglion '" + ganglionId + "'");
            }

            ExpressionEvaluator when = hasWhen
                                       ? parseExpressionEntry((Map<String, Object>) ruleMap.get("when"),
                                                              "rule " + i + " in ganglion '" + ganglionId + "'")
                                       : null;

            String          signalStr = requireString(ruleMap, "signal");
            DetectionSignal signal;
            try {
                signal = DetectionSignal.valueOf(signalStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Invalid signal '" + signalStr + "' in rule " + i
                        + " of ganglion '" + ganglionId + "'. Expected: DETECTED, WEAK, NOISE, ANTI");
            }

            Number confidenceNum = requireNumber(ruleMap, "confidence", ganglionId);
            double confidence    = confidenceNum.doubleValue();
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException(
                        "confidence must be 0.0-1.0 in rule " + i
                        + " of ganglion '" + ganglionId + "', got: " + confidence);
            }

            ExpressionEvaluator confidenceExpr = parseExpressionEvaluator(ruleMap, "confidenceExpression");

            rules.add(new GanglionDescriptor.ExpressionRules.Rule(when, signal, confidence, confidenceExpr, parseEvidenceTemplates(ruleMap)));
        }

        Map<String, ExpressionEvaluator> evidenceTemplates = parseEvidenceTemplates(map);

        return new GanglionDescriptor.ExpressionRules(
                ganglionId, new LinkedHashSet<>(eventTypes),
                List.copyOf(rules), evidenceTemplates);
    }


    @SuppressWarnings("unchecked")
    private static GanglionDescriptor.NaiveBayes.Feature parseNaiveBayesFeature(
            Map<String, Object> map, String ganglionId, String featureName) {
        ExpressionEvaluator expression = parseExpressionEntry(map,
                                                              "feature '" + featureName + "' in ganglion '" + ganglionId + "'");

        List<String> values = (List<String>) map.get("values");
        if (values == null || values.isEmpty()) {
            throw new IllegalArgumentException(
                    "Feature '" + featureName + "' in ganglion '" + ganglionId
                    + "' must have non-empty values");
        }
        List<List<Number>> likelihoodsList = (List<List<Number>>) map.get("likelihoods");
        if (likelihoodsList == null) {
            throw new IllegalArgumentException(
                    "Feature '" + featureName + "' in ganglion '" + ganglionId
                    + "' must have likelihoods");
        }
        double[][] likelihoods = likelihoodsList.stream()
                                                .map(row -> row.stream().mapToDouble(Number::doubleValue).toArray())
                                                .toArray(double[][]::new);

        return new GanglionDescriptor.NaiveBayes.Feature(expression, values, likelihoods);
    }

    private static GanglionDescriptor.NaiveBayes.SignalMapping parseSignalMapping(Map<String, Object> map) {
        String targetOutcome = requireString(map, "targetOutcome");
        double detected      = ((Number) map.get("detectedThreshold")).doubleValue();
        double weak          = ((Number) map.get("weakThreshold")).doubleValue();
        Double anti = map.containsKey("antiThreshold")
                      ? ((Number) map.get("antiThreshold")).doubleValue()
                      : null;
        return new GanglionDescriptor.NaiveBayes.SignalMapping(targetOutcome, detected, weak, anti);
    }


    @SuppressWarnings("unchecked")
    static Map<String, SituationTemplate> parseTemplates(Map<String, Object> root) {
        List<Map<String, Object>> templates = (List<Map<String, Object>>) root.get("templates");
        if (templates == null) {return Map.of();}
        Map<String, SituationTemplate> result = new LinkedHashMap<>();
        for (Map<String, Object> t : templates) {
            String id          = requireString(t, "id");
            String description = (String) t.get("description");
            Map<String, ParameterDef> params = parseParameterDefs(
                    (Map<String, Object>) t.get("parameters"));
            Map<String, Object> definition = (Map<String, Object>) t.get("definition");
            if (definition == null) {
                throw new IllegalArgumentException("Template '" + id + "' has no definition");
            }
            List<Map<String, Object>> ganglia = (List<Map<String, Object>>) t.get("ganglia");
            result.put(id, new SituationTemplate(id, description, params, definition, ganglia));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ParameterDef> parseParameterDefs(Map<String, Object> raw) {
        if (raw == null) {return Map.of();}
        Map<String, ParameterDef> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            Map<String, Object> defMap       = (Map<String, Object>) entry.getValue();
            boolean             required     = Boolean.TRUE.equals(defMap.get("required"));
            Object              defaultValue = defMap.get("default");
            result.put(entry.getKey(), new ParameterDef(required, defaultValue));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    static Object substituteParams(Object value, Map<String, Object> resolvedParams) {
        if (value instanceof String s) {
            java.util.regex.Matcher wholeMatcher = WHOLE_PARAM_PATTERN.matcher(s);
            if (wholeMatcher.matches()) {
                String name = wholeMatcher.group(1);
                return resolvedParams.containsKey(name) ? resolvedParams.get(name) : s;
            }
            return PARAM_PATTERN.matcher(s).replaceAll(mr -> {
                String name = mr.group(1);
                return resolvedParams.containsKey(name)
                       ? java.util.regex.Matcher.quoteReplacement(resolvedParams.get(name).toString())
                       : java.util.regex.Matcher.quoteReplacement(mr.group(0));
            });
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            for (var entry : ((Map<String, Object>) map).entrySet()) {
                result.put(entry.getKey(), substituteParams(entry.getValue(), resolvedParams));
            }
            return result;
        }
        if (value instanceof List<?> list) {
            List<Object> result = new ArrayList<>(list.size());
            for (Object item : list) {
                result.add(substituteParams(item, resolvedParams));
            }
            return result;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    static void checkUnresolved(Object value, String templateId) {
        if (value instanceof String s && PARAM_PATTERN.matcher(s).find()) {
            throw new IllegalArgumentException(
                    "Unresolved parameter in template '" + templateId + "': " + s);
        }
        if (value instanceof Map<?, ?> map) {
            for (var entry : ((Map<String, Object>) map).entrySet()) {
                if (PARAM_PATTERN.matcher(entry.getKey()).find()) {
                    throw new IllegalArgumentException(
                            "Parameter placeholder in map key not supported in template '"
                            + templateId + "': " + entry.getKey());
                }
                checkUnresolved(entry.getValue(), templateId);
            }
        }
        if (value instanceof List<?> list) {
            for (Object item : list) {
                checkUnresolved(item, templateId);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> deepMerge(Map<String, Object> base,
                                         Map<String, Object> overrides) {
        Map<String, Object> result = new LinkedHashMap<>(base);
        for (var entry : overrides.entrySet()) {
            Object baseValue     = result.get(entry.getKey());
            Object overrideValue = entry.getValue();
            if (baseValue instanceof Map && overrideValue instanceof Map) {
                result.put(entry.getKey(), deepMerge(
                        (Map<String, Object>) baseValue,
                        (Map<String, Object>) overrideValue));
            } else {
                result.put(entry.getKey(), overrideValue);
            }
        }
        return result;
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildResolvedParams(Map<String, Object> situationMap,
                                                           SituationTemplate template) {
        String situationId = requireString(situationMap, "situationId");
        Object eventTypes  = situationMap.get("eventTypes");

        Map<String, Object> resolvedParams = new LinkedHashMap<>();
        for (var entry : template.parameters().entrySet()) {
            if (entry.getValue().defaultValue() != null) {
                resolvedParams.put(entry.getKey(), entry.getValue().defaultValue());
            }
        }
        if (template.parameters().containsKey("situationId")) {
            resolvedParams.put("situationId", situationId);
        }
        if (template.parameters().containsKey("eventTypes")) {
            resolvedParams.put("eventTypes", eventTypes);
        }
        Map<String, Object> consumerParams = (Map<String, Object>) situationMap.get("parameters");
        if (consumerParams != null) {
            resolvedParams.putAll(consumerParams);
        }
        return resolvedParams;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> resolveTemplate(Map<String, Object> situationMap,
                                               Map<String, SituationTemplate> templates) {
        String            templateId = (String) situationMap.get("fromTemplate");
        SituationTemplate template   = templates.get(templateId);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template: '" + templateId + "'");
        }

        Map<String, Object> resolvedParams = buildResolvedParams(situationMap, template);

        Map<String, Object> consumerParams = (Map<String, Object>) situationMap.get("parameters");
        if (consumerParams != null) {
            for (String key : consumerParams.keySet()) {
                if (!template.parameters().containsKey(key)) {
                    LOG.warning("Unknown parameter '" + key + "' for template '" + templateId + "'");
                }
            }
        }
        for (var entry : template.parameters().entrySet()) {
            if (entry.getValue().required() && !resolvedParams.containsKey(entry.getKey())) {
                throw new IllegalArgumentException(
                        "Missing required parameter '" + entry.getKey()
                        + "' for template '" + templateId + "'");
            }
        }

        Map<String, Object> resolved = (Map<String, Object>) substituteParams(
                new LinkedHashMap<>(template.definition()), resolvedParams);
        checkUnresolved(resolved, templateId);

        Map<String, Object> overrides = new LinkedHashMap<>();
        for (var entry : situationMap.entrySet()) {
            String key = entry.getKey();
            if (!"fromTemplate".equals(key) && !"situationId".equals(key)
                && !"eventTypes".equals(key) && !"parameters".equals(key)) {
                overrides.put(key, entry.getValue());
            }
        }
        if (!overrides.isEmpty()) {
            resolved = deepMerge(resolved, overrides);
        }

        String situationId = requireString(situationMap, "situationId");
        Object eventTypes  = situationMap.get("eventTypes");
        resolved.put("situationId", situationId);
        resolved.put("eventTypes", eventTypes);
        return resolved;
    }

    @SuppressWarnings("unchecked")
    private static SituationRegistration parseSituation(Map<String, Object> map) {
        String       situationId   = requireString(map, "situationId");
        List<String> eventTypeList = (List<String>) map.get("eventTypes");
        if (eventTypeList == null || eventTypeList.isEmpty()) {
            throw new IllegalArgumentException(
                    "eventTypes must not be empty for situation '" + situationId + "'");
        }

        Duration correlationWindow = null;
        if (map.containsKey("correlationWindow")) {
            correlationWindow = Duration.parse((String) map.get("correlationWindow"));
        }

        Duration eventBufferDelay = null;
        if (map.containsKey("eventBufferDelay")) {
            eventBufferDelay = Duration.parse((String) map.get("eventBufferDelay"));
        }

        Map<String, Object> chainModeMap = (Map<String, Object>) map.get("chainMode");
        if (chainModeMap == null) {
            throw new IllegalArgumentException(
                    "chainMode required for situation '" + situationId + "'");
        }

        Map<String, Object> triggerActionMap = (Map<String, Object>) map.get("triggerAction");
        if (triggerActionMap == null) {
            throw new IllegalArgumentException(
                    "triggerAction required for situation '" + situationId + "'");
        }

        ChainMode     chainMode     = parseChainMode(chainModeMap, situationId);
        TriggerAction triggerAction = parseTriggerAction(triggerActionMap, situationId);

        TriggerMode triggerMode = new TriggerMode.FireOnce();
        if (map.containsKey("triggerMode")) {
            triggerMode = parseTriggerMode((Map<String, Object>) map.get("triggerMode"));
        }

        ExpressionEvaluator              correlationKeyExpr = parseExpressionEvaluator(map, "correlationKey");
        ExpressionEvaluator              eventFilterExpr    = parseExpressionEvaluator(map, "eventFilter");
        Map<String, ExpressionEvaluator> dynamicCaseData    = parseDynamicCaseData(map);

        io.casehub.ras.api.FeedbackConfig feedbackConfig = null;
        Map<String, Object> feedbackMap = (Map<String, Object>) map.get("feedback");
        if (feedbackMap != null) {
            feedbackConfig = parseFeedbackConfig(feedbackMap);
        }

        SituationDefinition def = new SituationDefinition(
                situationId, new LinkedHashSet<>(eventTypeList),
                correlationWindow, eventBufferDelay, chainMode,
                triggerAction, triggerMode,
                correlationKeyExpr, eventFilterExpr, dynamicCaseData,
                feedbackConfig);
        return new SituationRegistration(def);
    }

    @SuppressWarnings("unchecked")
    private static ChainMode parseChainMode(Map<String, Object> map, String situationId) {
        String type = requireString(map, "type");
        return switch (type) {
            case "and" -> new ChainMode.And(
                    new LinkedHashSet<>(requireList(map, "ganglia", situationId)));
            case "or" -> new ChainMode.Or(
                    new LinkedHashSet<>(requireList(map, "ganglia", situationId)));
            case "threshold" -> new ChainMode.Threshold(
                    new LinkedHashSet<>(requireList(map, "ganglia", situationId)),
                    requireNumber(map, "minConfidence", situationId).doubleValue());
            case "sequence" -> new ChainMode.Sequence(
                    requireList(map, "ganglia", situationId));
            case "count" -> new ChainMode.Count(
                    requireString(map, "ganglionId"),
                    requireNumber(map, "requiredCount", situationId).intValue());
            case "streak" -> new ChainMode.Streak(
                    requireString(map, "ganglionId"),
                    requireNumber(map, "requiredCount", situationId).intValue());
            case "rate" -> new ChainMode.Rate(
                    new LinkedHashSet<>(requireList(map, "ganglia", situationId)),
                    requireNumber(map, "minRate", situationId).doubleValue(),
                    requireNumber(map, "windowSize", situationId).intValue());
            default -> throw new IllegalArgumentException(
                    "Unknown chainMode type '" + type + "' in situation '" + situationId + "'");
        };
    }

    @SuppressWarnings("unchecked")
    private static TriggerAction parseTriggerAction(Map<String, Object> map, String situationId) {
        String type = requireString(map, "type");
        return switch (type) {
            case "create-case" -> new TriggerAction.CreateCase(new CaseTriggerConfig(
                    requireString(map, "caseNamespace"),
                    requireString(map, "caseName"),
                    requireString(map, "caseVersion"),
                    (Map<String, Object>) map.getOrDefault("baseCaseData", Map.of())));
            case "notify-only" -> new TriggerAction.NotifyOnly();
            default -> throw new IllegalArgumentException(
                    "Unknown triggerAction type '" + type + "' in situation '" + situationId
                    + "'. Expected 'create-case' or 'notify-only'");
        };
    }

    @SuppressWarnings("unchecked")
    private static ExpressionEvaluator parseExpressionEvaluator(Map<String, Object> map, String key) {
        Map<String, Object> exprMap = (Map<String, Object>) map.get(key);
        if (exprMap == null) {return null;}
        return parseExpressionEntry(exprMap, key);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ExpressionEvaluator> parseDynamicCaseData(Map<String, Object> map) {
        Map<String, Object> raw = (Map<String, Object>) map.get("dynamicCaseData");
        if (raw == null) {return Map.of();}
        Map<String, ExpressionEvaluator> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            result.put(entry.getKey(), parseExpressionEntry(
                    (Map<String, Object>) entry.getValue(),
                    "dynamicCaseData key '" + entry.getKey() + "'"));
        }
        return result;
    }


    private static ExpressionEvaluator parseExpressionEntry(Map<String, Object> exprMap, String context) {
        String expression = requireString(exprMap, "expression");
        String language   = requireString(exprMap, "language");
        return switch (language) {
            case "jq" -> new JQExpressionEvaluator(expression);
            case "mvel" -> new MvelExpressionEvaluator(expression);
            default -> throw new IllegalArgumentException(
                    "Unknown expression language '" + language + "' in " + context + ". Expected 'jq' or 'mvel'");
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ExpressionEvaluator> parseEvidenceTemplates(Map<String, Object> map) {
        Map<String, Object> raw = (Map<String, Object>) map.get("evidenceTemplates");
        if (raw == null) {return Map.of();}
        Map<String, ExpressionEvaluator> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            result.put(entry.getKey(), parseExpressionEntry(
                    (Map<String, Object>) entry.getValue(),
                    "evidenceTemplate '" + entry.getKey() + "'"));
        }
        return Map.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, ExpressionEvaluator>> parseOutcomeEvidenceTemplates(
            Map<String, Object> map, List<String> outcomes, String ganglionId) {
        Map<String, Object> raw = (Map<String, Object>) map.get("outcomeEvidenceTemplates");
        if (raw == null) {return Map.of();}
        Map<String, Map<String, ExpressionEvaluator>> result = new LinkedHashMap<>();
        for (var entry : raw.entrySet()) {
            String outcomeName = entry.getKey();
            if (!outcomes.contains(outcomeName)) {
                throw new IllegalArgumentException(
                        "outcomeEvidenceTemplates key '" + outcomeName
                        + "' is not in outcomes " + outcomes
                        + " for ganglion '" + ganglionId + "'");
            }
            Map<String, Object>              templates = (Map<String, Object>) entry.getValue();
            Map<String, ExpressionEvaluator> parsed    = new LinkedHashMap<>();
            for (var tmpl : templates.entrySet()) {
                parsed.put(tmpl.getKey(), parseExpressionEntry(
                        (Map<String, Object>) tmpl.getValue(),
                        "outcomeEvidenceTemplate '" + tmpl.getKey()
                        + "' for outcome '" + outcomeName + "'"));
            }
            result.put(outcomeName, Map.copyOf(parsed));
        }
        return Map.copyOf(result);
    }


    private static String requireString(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> requireList(Map<String, Object> map, String key, String situationId) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing required field '" + key + "' in chainMode for situation '" + situationId + "'");
        }
        return (List<String>) value;
    }

    private static Number requireNumber(Map<String, Object> map, String key, String situationId) {
        Object value = map.get(key);
        if (value == null) {
            throw new IllegalArgumentException(
                    "Missing required field '" + key + "' in chainMode for situation '" + situationId + "'");
        }
        return (Number) value;
    }

    @SuppressWarnings("unchecked")
    private static io.casehub.ras.api.FeedbackConfig parseFeedbackConfig(Map<String, Object> map) {
        List<String> noiseLabels = (List<String>) map.get("noiseLabels");
        List<String> confirmedLabels = (List<String>) map.get("confirmedLabels");
        if (noiseLabels == null || noiseLabels.isEmpty()) {
            throw new IllegalArgumentException("feedback.noiseLabels must not be empty");
        }
        if (confirmedLabels == null || confirmedLabels.isEmpty()) {
            throw new IllegalArgumentException("feedback.confirmedLabels must not be empty");
        }
        Duration suppressionCooldown = Duration.parse(requireString(map, "suppressionCooldown"));
        double learningRate = ((Number) map.get("learningRate")).doubleValue();
        Duration retentionPeriod = Duration.parse(requireString(map, "retentionPeriod"));
        boolean tuningEnabled = Boolean.TRUE.equals(map.get("tuningEnabled"));
        return new io.casehub.ras.api.FeedbackConfig(
                new LinkedHashSet<>(noiseLabels),
                new LinkedHashSet<>(confirmedLabels),
                suppressionCooldown, learningRate, retentionPeriod, tuningEnabled);
    }

    @SuppressWarnings("unchecked")
    private static TriggerMode parseTriggerMode(Map<String, Object> map) {
        String type = (String) map.getOrDefault("type", "fire-once");
        return switch (type) {
            case "fire-once" -> new TriggerMode.FireOnce();
            case "repeating" -> {
                Object cooldownValue = map.get("cooldown");
                if (cooldownValue == null) {
                    throw new IllegalArgumentException(
                            "triggerMode type 'repeating' requires 'cooldown' field");
                }
                Duration cooldown = Duration.parse(cooldownValue.toString());
                yield new TriggerMode.Repeating(cooldown);
            }
            default -> throw new IllegalArgumentException(
                    "Unknown triggerMode type: '" + type + "'. Expected 'fire-once' or 'repeating'");
        };
    }
}
