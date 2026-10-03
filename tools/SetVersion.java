import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retargets the project at another Minecraft version.
 *
 *   java tools/SetVersion.java <minecraft version> [--dry-run]
 *
 * gradle.properties is the single source of truth for the target: the build, the mod metadata, the release name and tag,
 * the release notes and the installers are all derived from it. This tool looks up the matching Fabric API and NeoForge
 * versions for the Minecraft version and the latest Fabric Loader, and rewrites gradle.properties. It cannot port the code
 * to the new Minecraft version; after running it, build and fix whatever the new version broke.
 */
public class SetVersion {

    public static void main(String[] args) throws Exception {
        String mc = null;
        boolean dry = false;
        for (String a : args) {
            if (a.equals("--dry-run")) dry = true;
            else mc = a;
        }
        if (mc == null) {
            System.err.println("Usage: java tools/SetVersion.java <minecraft version, e.g. 26.3> [--dry-run]");
            System.exit(2);
        }

        final String target = mc;
        Path props = Path.of("gradle.properties");
        if (!Files.exists(props)) throw new IllegalStateException("Run this from the repository root (gradle.properties not found)");

        HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();

        List<String> fabricApi = versions(http, "https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/maven-metadata.xml");
        String fabricApiVersion = last(fabricApi.stream().filter(v -> v.endsWith("+" + target)).toList(), "Fabric API for " + target).replace("+" + target, "");

        String neoPrefix = neoForgePrefix(mc);
        List<String> neo = versions(http, "https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml").stream()
            .filter(v -> v.startsWith(neoPrefix)).toList();
        List<String> neoStable = neo.stream().filter(v -> !v.contains("-")).toList();
        // Prefer a stable NeoForge; right after a Minecraft release only betas may exist.
        String neoForgeVersion = last(neoStable.isEmpty() ? neo : neoStable, "NeoForge for " + mc);

        String forgePrefix = mc + "-";
        List<String> forge = versions(http, "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml").stream()
            .filter(v -> v.startsWith(forgePrefix)).toList();
        String forgeVersion = last(forge, "Forge for " + mc).substring(mc.length() + 1);

        String loader = release(http, "https://maven.fabricmc.net/net/fabricmc/fabric-loader/maven-metadata.xml", "Fabric Loader");

        String[][] updates = {
            {"minecraftVersion", mc},
            {"fabricLoaderVersion", loader},
            {"fabricApiVersion", fabricApiVersion},
            {"neoforgeVersion", neoForgeVersion},
            {"forgeVersion", forgeVersion},
        };

        String text = Files.readString(props, StandardCharsets.UTF_8);
        for (String[] u : updates) {
            Matcher m = Pattern.compile("(?m)^" + u[0] + "=.*$").matcher(text);
            if (!m.find()) throw new IllegalStateException(u[0] + " missing from gradle.properties");
            System.out.println(u[0] + ": " + m.group().substring(u[0].length() + 1).trim() + " -> " + u[1]);
            text = m.replaceFirst(Matcher.quoteReplacement(u[0] + "=" + u[1]));
        }
        if (dry) {
            System.out.println("(dry run, nothing written)");
        } else {
            Files.writeString(props, text, StandardCharsets.UTF_8);
            System.out.println("gradle.properties updated. Run `java tools/RenderReadme.java` to refresh README.md, then build (./gradlew build) and port any code the new Minecraft version broke.");
        }
    }

    /** NeoForge versions are prefixed by the Minecraft version without its leading "1." (1.21.4 -> 21.4., 26.2 -> 26.2.). */
    private static String neoForgePrefix(String mc) {
        String v = mc.startsWith("1.") ? mc.substring(2) : mc;
        return (v.chars().filter(c -> c == '.').count() == 0 ? v + ".0" : v) + ".";
    }

    private static List<String> versions(HttpClient http, String url) throws Exception {
        String xml = get(http, url);
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("<version>([^<]+)</version>").matcher(xml);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    private static String release(HttpClient http, String url, String what) throws Exception {
        Matcher m = Pattern.compile("<release>([^<]+)</release>").matcher(get(http, url));
        if (!m.find()) throw new IllegalStateException("No release found for " + what);
        return m.group(1);
    }

    private static String last(List<String> list, String what) {
        if (list.isEmpty()) throw new IllegalStateException("No version found for " + what + " (not published yet?)");
        return list.get(list.size() - 1);
    }

    /** Fetches a URL, retrying a few times: the NeoForge Maven occasionally answers 404 for files that exist. */
    private static String get(HttpClient http, String url) throws Exception {
        HttpResponse<String> r = null;
        for (int attempt = 1; attempt <= 4; attempt++) {
            r = http.send(HttpRequest.newBuilder(URI.create(url)).header("User-Agent", "spawncheck-set-version").build(),
                HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) return r.body();
            Thread.sleep(1500L * attempt);
        }
        throw new IllegalStateException(url + " returned HTTP " + r.statusCode());
    }
}
