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

    private final List<SituationRegistration> registrations;
    private final List<GanglionDescriptor>    ganglionDescriptors;

    @Inject
    YamlSituationDefinitionProvider(
            @ConfigProperty(name = "ras.situations.yaml",
                            defaultValue = "META-INF/ras-situations.yaml") String resourcePath) {
        InputStream is = Thread.currentThread().getContextClassLoader()
                               .getResourceAsStream(resourcePath);
        if (is == null) {
            LOG.fine("No YAML situation definitions found at " + resourcePath);
            this.registrations       = List.of();
            this.ganglionDescriptors = List.of();
        } else {
            try (is) {
                var parsed = parseAll(is);
                this.registrations       = parsed.registrations();
                this.ganglionDescriptors = parsed.ganglionDescriptors();
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + resourcePath, e);
            }
        }
    }

    YamlSituationDefinitionProvider(InputStream yaml) {
        var parsed = parseAll(yaml);
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

    @SuppressWarnings("unchecked")
    private static ParseResult parseAll(InputStream yaml) {
        Map<String, Object> root = new Yaml().load(yaml);
        if (root == null) {
            return new ParseResult(List.of(), List.of());
        }
        List<GanglionDescriptor>    ganglia    = parseGanglia(root);
        List<SituationRegistration> situations = parseSituations(root);
        return new ParseResult(situations, ganglia);
    }

    @SuppressWarnings("unchecked")
    private static List<SituationRegistration> parseSituations(Map<String, Object> root) {
        if (!root.containsKey("situations")) {
            return List.of();
        }
        List<Map<String, Object>>   situations = (List<Map<String, Object>>) root.get("situations");
        List<SituationRegistration> result     = new ArrayList<>(situations.size());
        for (Map<String, Object> sit : situations) {
            result.add(parseSituation(sit));
        }
        return List.copyOf(result);
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

        return new GanglionDescriptor.NaiveBayes(
                ganglionId, new LinkedHashSet<>(eventTypes), outcomes, priors,
                features, parseSignalMapping(sigMap),
                parseEvidenceTemplates(map));
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

            rules.add(new GanglionDescriptor.ExpressionRules.Rule(when, signal, confidence));
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

        SituationDefinition def = new SituationDefinition(
                situationId, new LinkedHashSet<>(eventTypeList),
                correlationWindow, eventBufferDelay, chainMode,
                triggerAction, triggerMode,
                correlationKeyExpr, eventFilterExpr, dynamicCaseData);
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
