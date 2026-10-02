import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders README.md from README.template.md by replacing {{key}} with the value of that key in gradle.properties
 * (e.g. {{minecraftVersion}}). Run from the repository root:  java tools/RenderReadme.java
 */
public class RenderReadme {
    public static void main(String[] args) throws Exception {
        Map<String, String> props = new HashMap<>();
        for (String line : Files.readAllLines(Path.of("gradle.properties"), StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (trimmed.startsWith("#") || eq <= 0) continue;
            props.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }

        String text = Files.readString(Path.of("README.template.md"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        Matcher m = Pattern.compile("[{][{]([A-Za-z0-9_]+)[}][}]").matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String value = props.get(m.group(1));
            if (value == null) {
                throw new IllegalStateException("README.template.md uses {{" + m.group(1) + "}} which is not in gradle.properties");
            }
            m.appendReplacement(out, Matcher.quoteReplacement(value));
        }
        m.appendTail(out);

        Files.writeString(Path.of("README.md"), out.toString(), StandardCharsets.UTF_8);
        System.out.println("Rendered README.md (Minecraft " + props.get("minecraftVersion") + ")");
    }
}
