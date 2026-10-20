package io.casehub.ras.runtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.casehub.ras.api.GanglionDescriptor;
import io.casehub.ras.api.SituationDefinition;
import io.casehub.ras.api.SituationDefinitionProvider;
import io.casehub.ras.api.SituationRegistration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

@ApplicationScoped
public class YamlSituationDefinitionProvider implements SituationDefinitionProvider {

    private static final Logger LOG = Logger.getLogger(YamlSituationDefinitionProvider.class.getName());
    private static final ObjectMapper YAML_MAPPER = RasYamlMapper.instance();
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
            JsonNode definition,
            JsonNode ganglia
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
            JsonNode root = YAML_MAPPER.readTree(is);
            return root != null && !root.isNull() ? parseTemplates(root) : Map.of();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read built-in templates", e);
        }
    }

    private static ParseResult parseAll(InputStream yaml,
                                        Map<String, SituationTemplate> builtInTemplates) {
        JsonNode root;
        try {
            root = YAML_MAPPER.readTree(yaml);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to parse YAML", e);
        }
        if (root == null || root.isNull() || root.isEmpty()) {
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

    private static SituationParseResult parseSituations(JsonNode root,
                                                         Map<String, SituationTemplate> templates) {
        JsonNode situationsNode = root.get("situations");
        if (situationsNode == null || !situationsNode.isArray()) {
            return new SituationParseResult(List.of(), List.of());
        }
        List<SituationRegistration> result         = new ArrayList<>();
        List<GanglionDescriptor>    bundledGanglia = new ArrayList<>();
        for (JsonNode sit : situationsNode) {
            if (sit.has("fromTemplate")) {
                String templateId = sit.get("fromTemplate").asText();
                String sitId      = sit.has("situationId") ? sit.get("situationId").asText() : "<missing>";
                JsonNode resolved;
                try {
                    resolved = resolveTemplate(sit, templates);
                } catch (Exception e) {
                    throw new IllegalArgumentException(
                            "Error resolving template '" + templateId
                            + "' for situation '" + sitId + "': " + e.getMessage(), e);
                }
                SituationTemplate template = templates.get(templateId);
                if (template.ganglia() != null && template.ganglia().isArray() && !template.ganglia().isEmpty()) {
                    Map<String, Object> resolvedParams = buildResolvedParams(sit, template);
                    ArrayNode resolvedGangliaArray = YAML_MAPPER.createArrayNode();
                    for (JsonNode g : template.ganglia()) {
                        resolvedGangliaArray.add(substituteParams(g.deepCopy(), resolvedParams));
                    }
                    checkUnresolved(resolvedGangliaArray, templateId);
                    ObjectNode gangliaRoot = YAML_MAPPER.createObjectNode();
                    gangliaRoot.set("ganglia", resolvedGangliaArray);
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

    private static List<GanglionDescriptor> parseGanglia(JsonNode root) {
        JsonNode gangliaNode = root.get("ganglia");
        if (gangliaNode == null || !gangliaNode.isArray()) {
            return List.of();
        }
        List<GanglionDescriptor> result = new ArrayList<>(gangliaNode.size());
        for (JsonNode g : gangliaNode) {
            try {
                validateGanglionTree(g);
                GanglionDescriptor desc = YAML_MAPPER.treeToValue(g, GanglionDescriptor.class);
                validateGanglionDescriptor(desc);
                result.add(desc);
            } catch (JsonProcessingException e) {
                String gId = g.has("ganglionId") ? g.get("ganglionId").asText() : "<unknown>";
                throw new IllegalArgumentException(
                        "Failed to deserialize ganglion '" + gId + "': " + e.getMessage(), e);
            }
        }
        return List.copyOf(result);
    }

    private static SituationRegistration parseSituation(JsonNode node) {
        try {
            SituationDefinition def = YAML_MAPPER.treeToValue(node, SituationDefinition.class);
            return new SituationRegistration(def);
        } catch (JsonProcessingException e) {
            String sitId = node.has("situationId") ? node.get("situationId").asText() : "<unknown>";
            throw new IllegalArgumentException(
                    "Failed to deserialize situation '" + sitId + "': " + e.getMessage(), e);
        }
    }

    private static void validateGanglionDescriptor(GanglionDescriptor desc) {
        switch (desc) {
            case GanglionDescriptor.NaiveBayes nb -> {
                if (nb.handledEventTypes() == null || nb.handledEventTypes().isEmpty()) {
                    throw new IllegalArgumentException(
                            "handledEventTypes must not be empty for ganglion '" + nb.ganglionId() + "'");
                }
                if (nb.outcomes() == null || nb.outcomes().size() < 2) {
                    throw new IllegalArgumentException(
                            "outcomes must have at least 2 entries for ganglion '" + nb.ganglionId() + "'");
                }
                if (nb.outcomeGroundTruth() != null) {
                    for (var entry : nb.outcomeGroundTruth().entrySet()) {
                        if (!nb.outcomes().contains(entry.getValue())) {
                            throw new IllegalArgumentException(
                                    "outcomeGroundTruth value '" + entry.getValue() + "' for label '"
                                    + entry.getKey() + "' is not in outcomes " + nb.outcomes()
                                    + " for ganglion '" + nb.ganglionId() + "'");
                        }
                    }
                }
                for (var key : nb.outcomeEvidenceTemplates().keySet()) {
                    if (!nb.outcomes().contains(key)) {
                        throw new IllegalArgumentException(
                                "outcomeEvidenceTemplates key '" + key
                                + "' is not in outcomes " + nb.outcomes()
                                + " for ganglion '" + nb.ganglionId() + "'");
                    }
                }
            }
            case GanglionDescriptor.ExpressionRules er -> {
                if (er.handledEventTypes() == null || er.handledEventTypes().isEmpty()) {
                    throw new IllegalArgumentException(
                            "handledEventTypes must not be empty for ganglion '" + er.ganglionId() + "'");
                }
                if (er.rules() == null || er.rules().isEmpty()) {
                    throw new IllegalArgumentException(
                            "rules must not be empty for ganglion '" + er.ganglionId() + "'");
                }
                for (int i = 0; i < er.rules().size(); i++) {
                    var rule = er.rules().get(i);
                    if (rule.when() == null && i != er.rules().size() - 1) {
                        throw new IllegalArgumentException(
                                "'otherwise' must be the last rule in ganglion '" + er.ganglionId() + "'");
                    }
                    if (rule.confidence() < 0.0 || rule.confidence() > 1.0) {
                        throw new IllegalArgumentException(
                                "confidence must be 0.0-1.0 in rule " + i
                                + " of ganglion '" + er.ganglionId() + "', got: " + rule.confidence());
                    }
                }
            }
            case GanglionDescriptor.SituationWatcher sw -> {
                if (sw.changeTypeMapping() == null || sw.changeTypeMapping().isEmpty()) {
                    throw new IllegalArgumentException(
                            "situation-watcher ganglion '" + sw.ganglionId() + "' requires non-empty changeTypeMapping");
                }
            }
        }
    }

    private static void validateGanglionTree(JsonNode g) {
        if (!g.has("type")) {
            throw new IllegalArgumentException("Missing required field: type");
        }
        String type = g.get("type").asText();
        if (!g.has("ganglionId")) {
            throw new IllegalArgumentException("Missing required field: ganglionId");
        }
        String ganglionId = g.get("ganglionId").asText();

        if (!"naive-bayes".equals(type) && !"expression-rules".equals(type) && !"situation-watcher".equals(type)) {
            throw new IllegalArgumentException(
                    "Unknown ganglion type '" + type + "' for ganglion '" + ganglionId + "'");
        }

        if ("expression-rules".equals(type)) {
            JsonNode rulesNode = g.get("rules");
            if (rulesNode == null || !rulesNode.isArray()) {return;}
            for (int i = 0; i < rulesNode.size(); i++) {
                JsonNode rule         = rulesNode.get(i);
                boolean  hasWhen      = rule.has("when");
                boolean  hasOtherwise = rule.has("otherwise");
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
                if (hasOtherwise && i != rulesNode.size() - 1) {
                    throw new IllegalArgumentException(
                            "'otherwise' must be the last rule in ganglion '" + ganglionId + "'");
                }
                if (!rule.has("signal")) {
                    throw new IllegalArgumentException("Missing required field: signal");
                }
                String signalStr = rule.get("signal").asText();
                try {
                    io.casehub.ras.api.DetectionSignal.valueOf(signalStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException(
                            "Invalid signal '" + signalStr + "' in rule " + i
                            + " of ganglion '" + ganglionId + "'. Expected: DETECTED, WEAK, NOISE, ANTI");
                }
                if (!rule.has("confidence")) {
                    throw new IllegalArgumentException(
                            "Missing required field '" + "confidence" + "' in chainMode for situation '" + ganglionId + "'");
                }
            }
        }
    }


    // --- Template system (operates on JsonNode trees) ---

    static Map<String, SituationTemplate> parseTemplates(JsonNode root) {
        JsonNode templatesNode = root.get("templates");
        if (templatesNode == null || !templatesNode.isArray()) {return Map.of();}
        Map<String, SituationTemplate> result = new LinkedHashMap<>();
        for (JsonNode t : templatesNode) {
            String id = requireString(t, "id");
            String description = t.has("description") ? t.get("description").asText() : null;
            Map<String, ParameterDef> params = parseParameterDefs(t.get("parameters"));
            JsonNode definition = t.get("definition");
            if (definition == null || definition.isNull()) {
                throw new IllegalArgumentException("Template '" + id + "' has no definition");
            }
            JsonNode ganglia = t.get("ganglia");
            result.put(id, new SituationTemplate(id, description, params, definition, ganglia));
        }
        return result;
    }

    private static Map<String, ParameterDef> parseParameterDefs(JsonNode raw) {
        if (raw == null || raw.isNull()) {return Map.of();}
        Map<String, ParameterDef> result = new LinkedHashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fields = raw.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode defNode = entry.getValue();
            boolean required = defNode.has("required") && defNode.get("required").asBoolean(false);
            Object defaultValue = null;
            if (defNode.has("default")) {
                defaultValue = nodeToObject(defNode.get("default"));
            }
            result.put(entry.getKey(), new ParameterDef(required, defaultValue));
        }
        return result;
    }

    static JsonNode substituteParams(JsonNode value, Map<String, Object> resolvedParams) {
        if (value.isTextual()) {
            String s = value.textValue();
            java.util.regex.Matcher wholeMatcher = WHOLE_PARAM_PATTERN.matcher(s);
            if (wholeMatcher.matches()) {
                String name = wholeMatcher.group(1);
                if (resolvedParams.containsKey(name)) {
                    return YAML_MAPPER.valueToTree(resolvedParams.get(name));
                }
                return value;
            }
            String replaced = PARAM_PATTERN.matcher(s).replaceAll(mr -> {
                String name = mr.group(1);
                return resolvedParams.containsKey(name)
                       ? java.util.regex.Matcher.quoteReplacement(resolvedParams.get(name).toString())
                       : java.util.regex.Matcher.quoteReplacement(mr.group(0));
            });
            return new TextNode(replaced);
        }
        if (value.isObject()) {
            ObjectNode obj = (ObjectNode) value;
            ObjectNode resultObj = YAML_MAPPER.createObjectNode();
            Iterator<Map.Entry<String, JsonNode>> fields = obj.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                resultObj.set(entry.getKey(), substituteParams(entry.getValue(), resolvedParams));
            }
            return resultObj;
        }
        if (value.isArray()) {
            ArrayNode arr = (ArrayNode) value;
            ArrayNode resultArr = YAML_MAPPER.createArrayNode();
            for (JsonNode item : arr) {
                resultArr.add(substituteParams(item, resolvedParams));
            }
            return resultArr;
        }
        return value;
    }

    static void checkUnresolved(JsonNode value, String templateId) {
        if (value.isTextual() && PARAM_PATTERN.matcher(value.textValue()).find()) {
            throw new IllegalArgumentException(
                    "Unresolved parameter in template '" + templateId + "': " + value.textValue());
        }
        if (value.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = value.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                if (PARAM_PATTERN.matcher(entry.getKey()).find()) {
                    throw new IllegalArgumentException(
                            "Parameter placeholder in map key not supported in template '"
                            + templateId + "': " + entry.getKey());
                }
                checkUnresolved(entry.getValue(), templateId);
            }
        }
        if (value.isArray()) {
            for (JsonNode item : value) {
                checkUnresolved(item, templateId);
            }
        }
    }

    static JsonNode deepMerge(JsonNode base, JsonNode overrides) {
        if (!base.isObject() || !overrides.isObject()) {
            return overrides;
        }
        ObjectNode result = ((ObjectNode) base).deepCopy();
        Iterator<Map.Entry<String, JsonNode>> fields = overrides.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            JsonNode baseValue = result.get(entry.getKey());
            if (baseValue != null && baseValue.isObject() && entry.getValue().isObject()) {
                result.set(entry.getKey(), deepMerge(baseValue, entry.getValue()));
            } else {
                result.set(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> buildResolvedParams(JsonNode situationNode,
                                                            SituationTemplate template) {
        String situationId = requireString(situationNode, "situationId");
        Object eventTypes  = situationNode.has("eventTypes") ? nodeToObject(situationNode.get("eventTypes")) : null;

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
        JsonNode consumerParamsNode = situationNode.get("parameters");
        if (consumerParamsNode != null && consumerParamsNode.isObject()) {
            Map<String, Object> consumerParams = (Map<String, Object>) nodeToObject(consumerParamsNode);
            if (consumerParams != null) {
                resolvedParams.putAll(consumerParams);
            }
        }
        return resolvedParams;
    }

    static JsonNode resolveTemplate(JsonNode situationNode,
                                     Map<String, SituationTemplate> templates) {
        String templateId = situationNode.get("fromTemplate").asText();
        SituationTemplate template = templates.get(templateId);
        if (template == null) {
            throw new IllegalArgumentException("Unknown template: '" + templateId + "'");
        }

        Map<String, Object> resolvedParams = buildResolvedParams(situationNode, template);

        JsonNode consumerParamsNode = situationNode.get("parameters");
        if (consumerParamsNode != null && consumerParamsNode.isObject()) {
            Iterator<String> keys = consumerParamsNode.fieldNames();
            while (keys.hasNext()) {
                String key = keys.next();
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

        JsonNode resolved = substituteParams(template.definition().deepCopy(), resolvedParams);
        checkUnresolved(resolved, templateId);

        ObjectNode overrides = YAML_MAPPER.createObjectNode();
        Iterator<Map.Entry<String, JsonNode>> fields = situationNode.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = entry.getKey();
            if (!"fromTemplate".equals(key) && !"situationId".equals(key)
                && !"eventTypes".equals(key) && !"parameters".equals(key)) {
                overrides.set(key, entry.getValue());
            }
        }
        if (!overrides.isEmpty()) {
            resolved = deepMerge(resolved, overrides);
        }

        String situationId = requireString(situationNode, "situationId");
        JsonNode eventTypes = situationNode.get("eventTypes");
        ((ObjectNode) resolved).put("situationId", situationId);
        if (eventTypes != null) {
            ((ObjectNode) resolved).set("eventTypes", eventTypes);
        }
        return resolved;
    }

    private static String requireString(JsonNode node, String key) {
        JsonNode value = node.get(key);
        if (value == null || value.isNull()) {
            throw new IllegalArgumentException("Missing required field: " + key);
        }
        return value.asText();
    }

    @SuppressWarnings("unchecked")
    private static Object nodeToObject(JsonNode node) {
        if (node == null || node.isNull()) {return null;}
        if (node.isTextual()) {return node.textValue();}
        if (node.isInt()) {return node.intValue();}
        if (node.isLong()) {return node.longValue();}
        if (node.isDouble() || node.isFloat()) {return node.doubleValue();}
        if (node.isBoolean()) {return node.booleanValue();}
        if (node.isArray()) {
            List<Object> list = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                list.add(nodeToObject(item));
            }
            return list;
        }
        if (node.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (fields.hasNext()) {
                var entry = fields.next();
                map.put(entry.getKey(), nodeToObject(entry.getValue()));
            }
            return map;
        }
        return node.asText();
    }
}
