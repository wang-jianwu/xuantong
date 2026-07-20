package cloud.xuantong.config.management.content;

import org.noear.snack4.Feature;
import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.solon.annotation.Component;
import org.w3c.dom.Document;
import org.xml.sax.SAXParseException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.representer.Representer;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ConfigContentService {
    public static final int MAX_CONTENT_BYTES = 1024 * 1024;

    private static final Pattern LINE_COLUMN = Pattern.compile(
            "(?i).*line\\s*[:=]?\\s*(\\d+).*column\\s*[:=]?\\s*(\\d+).*");
    private static final Pattern POSITION = Pattern.compile(
            "(?i).*(?:position|index|offset)\\s*[:=]?\\s*(\\d+).*");

    public ConfigContentResult validate(String contentType, String content) {
        return process(contentType, content, Action.VALIDATE);
    }

    public ConfigContentResult format(String contentType, String content) {
        return process(contentType, content, Action.FORMAT);
    }

    public ConfigContentResult minify(String contentType, String content) {
        return process(contentType, content, Action.MINIFY);
    }

    public void requireValid(String contentType, String content) {
        ConfigContentResult result = validate(contentType, content);
        if (!result.valid()) {
            throw new ConfigContentValidationException(result);
        }
    }

    private ConfigContentResult process(String contentType, String content, Action action) {
        ConfigContentType type = ConfigContentType.parse(contentType);
        String value = content == null ? "" : content;
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_CONTENT_BYTES) {
            return ConfigContentResult.invalid(type, value, bytes,
                    new ConfigContentIssue(1, 1,
                            "Config content exceeds the 1048576-byte inline limit"));
        }
        try {
            String transformed = switch (type) {
                case TEXT, STRING -> value;
                case NUMBER -> scalarNumber(value, action);
                case BOOLEAN -> scalarBoolean(value, action);
                case JSON -> json(value, action);
                case YAML -> yaml(value, action);
                case XML -> xml(value, action);
                case PROPERTIES -> properties(value, action);
            };
            return ConfigContentResult.valid(type, transformed,
                    transformed.getBytes(StandardCharsets.UTF_8).length);
        } catch (ContentProblem problem) {
            return ConfigContentResult.invalid(type, value, bytes, problem.issue());
        } catch (Exception error) {
            return ConfigContentResult.invalid(type, value, bytes,
                    issueFrom(error, value));
        }
    }

    private String scalarNumber(String content, Action action) {
        String value = content.trim();
        if (value.isEmpty()) {
            throw problem(1, 1, "Number content must not be empty");
        }
        try {
            BigDecimal number = new BigDecimal(value);
            return action == Action.VALIDATE
                    ? content
                    : number.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException e) {
            throw problem(1, 1, "Invalid decimal number");
        }
    }

    private String scalarBoolean(String content, Action action) {
        String value = content.trim();
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw problem(1, 1, "Boolean content must be true or false");
        }
        return action == Action.VALIDATE ? content : value.toLowerCase();
    }

    private String json(String content, Action action) {
        if (content.isBlank()) {
            throw problem(1, 1, "JSON content must not be empty");
        }
        Options readOptions = Options.of(
                Feature.Read_DisableSingleQuotes,
                Feature.Read_DisableUnquotedKeys,
                Feature.Read_UseBigDecimalMode,
                Feature.Read_UseBigIntegerMode);
        ONode node = ONode.ofJson(content, readOptions);
        if (action == Action.VALIDATE) {
            return content;
        }
        node.options(action == Action.FORMAT
                ? Options.of(Feature.Write_PrettyFormat)
                : Options.of());
        return node.toJson();
    }

    private String yaml(String content, Action action) {
        if (content.isBlank()) {
            return content;
        }
        LoaderOptions loader = new LoaderOptions();
        loader.setAllowDuplicateKeys(false);
        loader.setMaxAliasesForCollections(50);
        loader.setNestingDepthLimit(100);
        loader.setCodePointLimit(MAX_CONTENT_BYTES);

        DumperOptions dumper = new DumperOptions();
        dumper.setAllowUnicode(true);
        dumper.setIndent(2);
        dumper.setIndicatorIndent(2);
        dumper.setIndentWithIndicator(true);
        dumper.setPrettyFlow(action == Action.FORMAT);
        dumper.setDefaultFlowStyle(action == Action.MINIFY
                ? DumperOptions.FlowStyle.FLOW
                : DumperOptions.FlowStyle.BLOCK);

        Yaml parser = new Yaml(
                new SafeConstructor(loader), new Representer(dumper), dumper, loader);
        List<Object> documents = new ArrayList<>();
        parser.loadAll(content).forEach(documents::add);
        if (action == Action.VALIDATE) {
            return content;
        }
        return parser.dumpAll(documents.iterator());
    }

    private String xml(String content, Action action) throws Exception {
        if (content.isBlank()) {
            throw problem(1, 1, "XML content must not be empty");
        }
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        DocumentBuilder builder = factory.newDocumentBuilder();
        builder.setErrorHandler(new ThrowingXmlErrorHandler());
        Document document = builder.parse(new org.xml.sax.InputSource(new StringReader(content)));
        if (action == Action.VALIDATE) {
            return content;
        }

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        transformerFactory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        Transformer transformer = transformerFactory.newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no");
        transformer.setOutputProperty(OutputKeys.ENCODING, StandardCharsets.UTF_8.name());
        transformer.setOutputProperty(OutputKeys.INDENT,
                action == Action.FORMAT ? "yes" : "no");
        if (action == Action.FORMAT) {
            transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
        }
        StringWriter output = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(output));
        return output.toString();
    }

    private String properties(String content, Action action) {
        LinkedHashMap<String, String> entries = parseProperties(content);
        if (action == Action.VALIDATE) {
            return content;
        }
        String separator = action == Action.FORMAT ? " = " : "=";
        StringBuilder output = new StringBuilder();
        entries.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .forEach(entry -> output
                        .append(escapeProperty(entry.getKey(), true))
                        .append(separator)
                        .append(escapeProperty(entry.getValue(), false))
                        .append('\n'));
        return output.toString();
    }

    private LinkedHashMap<String, String> parseProperties(String content) {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        Map<String, Integer> linesByKey = new LinkedHashMap<>();
        for (LogicalPropertyLine logical : logicalPropertyLines(content)) {
            String trimmed = logical.text().stripLeading();
            if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            validatePropertyEscapes(logical);
            Properties parsed = new Properties();
            try {
                parsed.load(new StringReader(logical.text()));
            } catch (IllegalArgumentException | java.io.IOException e) {
                throw problem(logical.line(), 1, cleanMessage(e));
            }
            if (parsed.size() != 1) {
                throw problem(logical.line(), 1, "Invalid Properties entry");
            }
            String key = parsed.stringPropertyNames().iterator().next();
            Integer previousLine = linesByKey.putIfAbsent(key, logical.line());
            if (previousLine != null) {
                throw problem(logical.line(), 1,
                        "Duplicate Properties key '" + key + "' (first declared at line "
                                + previousLine + ")");
            }
            values.put(key, parsed.getProperty(key));
        }
        return values;
    }

    private List<LogicalPropertyLine> logicalPropertyLines(String content) {
        String[] lines = content.split("\\r?\\n", -1);
        List<LogicalPropertyLine> logical = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int startLine = 1;
        for (int i = 0; i < lines.length; i++) {
            if (current.isEmpty()) {
                startLine = i + 1;
            }
            current.append(lines[i]);
            if (hasContinuation(lines[i])) {
                current.append('\n');
            } else {
                logical.add(new LogicalPropertyLine(startLine, current.toString()));
                current.setLength(0);
            }
        }
        if (!current.isEmpty()) {
            throw problem(startLine, Math.max(1, current.length()),
                    "Unfinished Properties continuation");
        }
        return logical;
    }

    private boolean hasContinuation(String line) {
        int backslashes = 0;
        for (int i = line.length() - 1; i >= 0 && line.charAt(i) == '\\'; i--) {
            backslashes++;
        }
        return backslashes % 2 == 1;
    }

    private void validatePropertyEscapes(LogicalPropertyLine logical) {
        String value = logical.text();
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) != '\\') {
                continue;
            }
            if (++i >= value.length()) {
                throw problem(logical.line(), value.length(), "Unfinished Properties escape");
            }
            char escaped = value.charAt(i);
            if (escaped == '\n' || "trnf\\ :=#!".indexOf(escaped) >= 0) {
                continue;
            }
            if (escaped != 'u') {
                throw problem(logical.line(), i + 1,
                        "Unsupported Properties escape: \\" + escaped);
            }
            if (i + 4 >= value.length()) {
                throw problem(logical.line(), i + 1, "Incomplete Unicode escape");
            }
            for (int hex = 1; hex <= 4; hex++) {
                if (Character.digit(value.charAt(i + hex), 16) < 0) {
                    throw problem(logical.line(), i + hex + 1, "Invalid Unicode escape");
                }
            }
            i += 4;
        }
    }

    private String escapeProperty(String value, boolean key) {
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            switch (ch) {
                case '\\' -> output.append("\\\\");
                case '\t' -> output.append("\\t");
                case '\n' -> output.append("\\n");
                case '\r' -> output.append("\\r");
                case '\f' -> output.append("\\f");
                case '=', ':', '#', '!' -> {
                    if (key) output.append('\\');
                    output.append(ch);
                }
                case ' ' -> {
                    if (key || i == 0) output.append('\\');
                    output.append(' ');
                }
                default -> output.append(ch);
            }
        }
        return output.toString();
    }

    private ConfigContentIssue issueFrom(Exception error, String content) {
        if (error instanceof ContentProblem problem) {
            return problem.issue();
        }
        if (error instanceof MarkedYAMLException yamlError) {
            Mark mark = yamlError.getProblemMark();
            return new ConfigContentIssue(
                    mark == null ? 1 : mark.getLine() + 1,
                    mark == null ? 1 : mark.getColumn() + 1,
                    yamlError.getProblem() == null ? cleanMessage(error) : yamlError.getProblem());
        }
        if (error instanceof SAXParseException xmlError) {
            return new ConfigContentIssue(
                    xmlError.getLineNumber(), xmlError.getColumnNumber(), cleanMessage(error));
        }
        String message = cleanMessage(error);
        Matcher lineColumn = LINE_COLUMN.matcher(message);
        if (lineColumn.matches()) {
            return new ConfigContentIssue(
                    Integer.parseInt(lineColumn.group(1)),
                    Integer.parseInt(lineColumn.group(2)), message);
        }
        Matcher position = POSITION.matcher(message);
        if (position.matches()) {
            int offset = Math.min(content.length(), Integer.parseInt(position.group(1)));
            int[] location = location(content, offset);
            return new ConfigContentIssue(location[0], location[1], message);
        }
        return new ConfigContentIssue(1, 1, message);
    }

    private int[] location(String content, int offset) {
        int line = 1;
        int column = 1;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        return new int[]{line, column};
    }

    private String cleanMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName()
                : message.replaceAll("[\\r\\n]+", " ").trim();
    }

    private ContentProblem problem(int line, int column, String message) {
        return new ContentProblem(new ConfigContentIssue(line, column, message));
    }

    private enum Action {
        VALIDATE,
        FORMAT,
        MINIFY
    }

    private record LogicalPropertyLine(int line, String text) {
    }

    private static class ContentProblem extends RuntimeException {
        private final ConfigContentIssue issue;

        private ContentProblem(ConfigContentIssue issue) {
            super(issue.message());
            this.issue = issue;
        }

        private ConfigContentIssue issue() {
            return issue;
        }
    }

    private static class ThrowingXmlErrorHandler implements org.xml.sax.ErrorHandler {
        @Override public void warning(SAXParseException exception) throws SAXParseException {
            throw exception;
        }
        @Override public void error(SAXParseException exception) throws SAXParseException {
            throw exception;
        }
        @Override public void fatalError(SAXParseException exception) throws SAXParseException {
            throw exception;
        }
    }
}
