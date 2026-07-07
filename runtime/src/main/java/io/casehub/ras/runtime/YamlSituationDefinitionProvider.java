package io.casehub.ras.runtime;

import io.casehub.ras.api.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.yaml.snakeyaml.Yaml;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.*;
import java.util.logging.Logger;

@ApplicationScoped
public class YamlSituationDefinitionProvider implements SituationDefinitionProvider {

    private static final Logger LOG = Logger.getLogger(YamlSituationDefinitionProvider.class.getName());

    private final List<SituationRegistration> registrations;

    @Inject
    YamlSituationDefinitionProvider(
            @ConfigProperty(name = "ras.situations.yaml",
                            defaultValue = "META-INF/ras-situations.yaml") String resourcePath) {
        InputStream is = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(resourcePath);
        if (is == null) {
            LOG.fine("No YAML situation definitions found at " + resourcePath);
            this.registrations = List.of();
        } else {
            try (is) {
                this.registrations = parse(is);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to read " + resourcePath, e);
            }
        }
    }

    YamlSituationDefinitionProvider(InputStream yaml) {
        this.registrations = parse(yaml);
    }

    @Override
    public List<SituationRegistration> registrations() {
        return registrations;
    }

    @SuppressWarnings("unchecked")
    private static List<SituationRegistration> parse(InputStream yaml) {
        Map<String, Object> root = new Yaml().load(yaml);
        if (root == null || !root.containsKey("situations")) {
            return List.of();
        }
        List<Map<String, Object>> situations = (List<Map<String, Object>>) root.get("situations");
        List<SituationRegistration> result = new ArrayList<>(situations.size());
        for (Map<String, Object> sit : situations) {
            result.add(parseSituation(sit));
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static SituationRegistration parseSituation(Map<String, Object> map) {
        String situationId = requireString(map, "situationId");
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

        ChainMode chainMode = parseChainMode(chainModeMap, situationId);
        TriggerAction triggerAction = parseTriggerAction(triggerActionMap, situationId);

        TriggerMode triggerMode = new TriggerMode.FireOnce();
        if (map.containsKey("triggerMode")) {
            triggerMode = parseTriggerMode((Map<String, Object>) map.get("triggerMode"));
        }

        SituationDefinition def = new SituationDefinition(
                situationId, new LinkedHashSet<>(eventTypeList),
                correlationWindow, eventBufferDelay, chainMode,
                triggerAction, triggerMode);
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
