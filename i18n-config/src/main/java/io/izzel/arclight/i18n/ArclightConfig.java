package io.izzel.arclight.i18n;

import com.google.common.reflect.TypeToken;
import io.izzel.arclight.i18n.conf.ConfigSpec;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.ValueType;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import ninja.leaping.configurate.yaml.YAMLConfigurationLoader;
import org.yaml.snakeyaml.DumperOptions;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;

public class ArclightConfig {

    private static final Path YAML_PATH = Paths.get("arclight.yml");
    private static final Path LEGACY_HOCON_PATH = Paths.get("arclight.conf");

    private static ArclightConfig instance;

    private final ConfigurationNode node;
    private final ConfigSpec spec;

    public ArclightConfig(ConfigurationNode node) throws ObjectMappingException {
        this.node = node;
        this.spec = this.node.getValue(TypeToken.of(ConfigSpec.class));
    }

    public ConfigurationNode getNode() {
        return node;
    }

    public ConfigSpec getSpec() {
        return spec;
    }

    public ConfigurationNode get(String path) {
        return this.node.getNode((Object[]) path.split("\\."));
    }

    public static ArclightConfig getInstance() {
        return instance;
    }

    public static ConfigSpec spec() {
        return instance.spec;
    }

    /**
     * Reload {@code arclight.yml} from disk (and jar defaults) without restarting the JVM.
     */
    public static synchronized void reload() throws Exception {
        load();
    }

    /**
     * Persist the current in-memory node to {@code arclight.yml}, with i18n comments.
     */
    public static synchronized void save() throws Exception {
        if (instance == null) {
            return;
        }
        writeYaml(instance.node, YAML_PATH, ArclightLocale.getInstance());
    }

    /**
     * Copy values from another node into the live config (overwrites leaves; used by Bukkit adapters).
     */
    public static synchronized void mergeValues(ConfigurationNode other) throws Exception {
        if (instance == null) {
            return;
        }
        deepCopyValues(other, instance.node);
        instance = new ArclightConfig(instance.node);
        save();
    }

    public static synchronized void mergeFromYamlString(String yaml) throws Exception {
        mergeValues(fromYamlString(yaml));
    }

    public static synchronized void putAll(Map<String, Object> values) throws Exception {
        if (instance == null) {
            return;
        }
        for (Map.Entry<String, Object> entry : values.entrySet()) {
            instance.node.getNode((Object[]) entry.getKey().split("\\.")).setValue(entry.getValue());
        }
        instance = new ArclightConfig(instance.node);
        save();
    }

    private static void deepCopyValues(ConfigurationNode from, ConfigurationNode to) {
        if (from.hasMapChildren()) {
            for (Map.Entry<Object, ? extends ConfigurationNode> entry : from.getChildrenMap().entrySet()) {
                deepCopyValues(entry.getValue(), to.getNode(entry.getKey()));
            }
            return;
        }
        if (!from.isVirtual()) {
            to.setValue(from.getValue());
        }
    }

    private static YAMLConfigurationLoader yamlLoader(Path path) {
        return YAMLConfigurationLoader.builder()
            .setPath(path)
            .setFlowStyle(DumperOptions.FlowStyle.BLOCK)
            .build();
    }

    private static ConfigurationNode emptyYamlNode() {
        return yamlLoader(YAML_PATH).createEmptyNode();
    }

    private static void load() throws Exception {
        ConfigurationNode defaults = YAMLConfigurationLoader.builder().setSource(
            () -> new BufferedReader(new InputStreamReader(
                ArclightConfig.class.getResourceAsStream("/META-INF/arclight.yml"), StandardCharsets.UTF_8))
        ).setFlowStyle(DumperOptions.FlowStyle.BLOCK).build().load();

        ConfigurationNode cur;
        if (Files.exists(YAML_PATH)) {
            cur = yamlLoader(YAML_PATH).load();
        } else {
            cur = emptyYamlNode();
        }

        cur = migrateLegacyHocon(cur);
        cur.mergeValuesFrom(defaults);
        cur.getNode("_v").setValue(2);
        cur.getNode("locale", "current").setValue(ArclightLocale.getInstance().getCurrent());
        instance = new ArclightConfig(cur);
        writeYaml(cur, YAML_PATH, ArclightLocale.getInstance());
    }

    private static ConfigurationNode migrateLegacyHocon(ConfigurationNode cur) throws Exception {
        if (!Files.exists(LEGACY_HOCON_PATH)) {
            return cur;
        }
        ConfigurationNode legacy = HoconConfigurationLoader.builder()
            .setPath(LEGACY_HOCON_PATH)
            .build()
            .load();
        ConfigurationNode merged = emptyYamlNode();
        merged.mergeValuesFrom(cur);
        merged.mergeValuesFrom(legacy);
        Path bak = Paths.get("arclight.conf.bak");
        Files.move(LEGACY_HOCON_PATH, bak, StandardCopyOption.REPLACE_EXISTING);
        System.out.println("[Arclight] Migrated arclight.conf -> arclight.yml (legacy saved as arclight.conf.bak)");
        return merged;
    }

    static void writeYaml(ConfigurationNode root, Path path, ArclightLocale locale) throws Exception {
        StringBuilder out = new StringBuilder();
        writeMapChildren(out, root, locale, 0);
        Files.writeString(path, out.toString(), StandardCharsets.UTF_8);
    }

    private static void writeMapChildren(StringBuilder out, ConfigurationNode node, ArclightLocale locale, int indent) {
        if (!node.hasMapChildren()) {
            return;
        }
        for (Map.Entry<Object, ? extends ConfigurationNode> entry : node.getChildrenMap().entrySet()) {
            writeEntry(out, String.valueOf(entry.getKey()), entry.getValue(), locale, indent);
        }
    }

    private static void writeEntry(StringBuilder out, String key, ConfigurationNode node, ArclightLocale locale, int indent) {
        writeComment(out, pathOf(node), locale, indent);
        indent(out, indent);
        out.append(escapeKey(key)).append(':');
        if (node.hasMapChildren()) {
            out.append('\n');
            writeMapChildren(out, node, locale, indent + 1);
            return;
        }
        if (node.getValueType() == ValueType.LIST) {
            List<? extends ConfigurationNode> list = node.getChildrenList();
            if (list.isEmpty()) {
                out.append(" []\n");
                return;
            }
            out.append('\n');
            for (ConfigurationNode child : list) {
                writeComment(out, pathOf(child), locale, indent + 1);
                indent(out, indent + 1);
                out.append("- ");
                if (child.hasMapChildren()) {
                    out.append('\n');
                    writeMapChildren(out, child, locale, indent + 2);
                } else {
                    out.append(formatScalar(child.getValue())).append('\n');
                }
            }
            return;
        }
        Object value = node.getValue();
        if (value == null) {
            out.append('\n');
        } else {
            out.append(' ').append(formatScalar(value)).append('\n');
        }
    }

    private static void writeComment(StringBuilder out, String path, ArclightLocale locale, int indent) {
        Optional<String> option = locale.getOption("comments." + path + ".comment");
        if (!option.isPresent()) {
            return;
        }
        for (String line : option.get().split("\n", -1)) {
            indent(out, indent);
            out.append('#');
            if (!line.isEmpty()) {
                out.append(' ').append(line);
            }
            out.append('\n');
        }
    }

    private static void indent(StringBuilder out, int indent) {
        out.append("  ".repeat(Math.max(0, indent)));
    }

    private static String escapeKey(String key) {
        if (key.isEmpty() || key.contains(":") || key.contains("#") || key.contains(" ")
            || key.contains("'") || key.contains("\"")) {
            return "'" + key.replace("'", "''") + "'";
        }
        return key;
    }

    private static final Set<String> RESERVED_WORDS = Set.of(
        "true", "false", "null", "yes", "no", "on", "off", "y", "n", "~"
    );

    private static String formatScalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        String text = String.valueOf(value);
        if (text.isEmpty()) {
            return "\"\"";
        }
        if (needsQuotes(text)) {
            return "\"" + text
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
                .replace("\"", "\\\"")
                + "\"";
        }
        return text;
    }

    private static boolean needsQuotes(String text) {
        if (Character.isWhitespace(text.charAt(0)) || Character.isWhitespace(text.charAt(text.length() - 1))) {
            return true;
        }
        char first = text.charAt(0);
        if (first == '&' || first == '*' || first == '!' || first == '|' || first == '>'
            || first == '%' || first == '`' || first == '@' || first == '{' || first == '[') {
            return true;
        }
        if ((first == '-' || first == '?') && text.length() > 1 && text.charAt(1) == ' ') {
            return true;
        }
        if (text.contains(": ") || text.contains("#") || text.contains("\n")
            || text.contains("\r") || text.contains("\t")
            || text.contains("'") || text.contains("\"")) {
            return true;
        }
        if (RESERVED_WORDS.contains(text.toLowerCase(Locale.ROOT))) {
            return true;
        }
        try {
            Double.parseDouble(text);
            return true;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static String pathOf(ConfigurationNode node) {
        StringJoiner joiner = new StringJoiner(".");
        for (Object o : node.getPath()) {
            if (o != null) {
                joiner.add(o.toString());
            }
        }
        String s = joiner.toString();
        return s.isEmpty() ? "__root__" : s;
    }

    /**
     * Serialize the live node to a YAML string (no comments) for Bukkit adapters.
     */
    public static String toYamlString() throws Exception {
        return toYamlString(getInstance().getNode());
    }

    public static String toYamlString(ConfigurationNode node) throws Exception {
        StringWriter writer = new StringWriter();
        YAMLConfigurationLoader.builder()
            .setSink(() -> new BufferedWriter(writer))
            .setFlowStyle(DumperOptions.FlowStyle.BLOCK)
            .build()
            .save(node);
        return writer.toString();
    }

    public static ConfigurationNode fromYamlString(String yaml) throws Exception {
        return YAMLConfigurationLoader.builder()
            .setSource(() -> new BufferedReader(new StringReader(yaml)))
            .setFlowStyle(DumperOptions.FlowStyle.BLOCK)
            .build()
            .load();
    }

    static {
        try {
            load();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
