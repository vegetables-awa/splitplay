package cn.splitplay.probe;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Offline release gate for the built mod jar.
 *
 * <p>It exists because the previous mod of this kind shipped a jar that looked fine and crashed on
 * startup: its classes still referenced Mojang's readable names ({@code net/minecraft/client/...})
 * while the Fabric runtime only knows intermediary names ({@code net/minecraft/class_465}). Nothing
 * in that project noticed, because nothing ever looked inside the jar. This check looks inside.
 *
 * <p>Usage: {@code java -cp <classes> cn.splitplay.probe.JarCheck <path-to.jar>}
 */
public final class JarCheck {

    /** Readable Minecraft class references are fine only in their intermediary form. */
    private static final Pattern MINECRAFT_REF = Pattern.compile("net/minecraft/[A-Za-z0-9_/$]+");
    /** Intermediary names, including nested ones such as {@code class_4185$class_4241}. */
    private static final Pattern INTERMEDIARY =
            Pattern.compile("net/minecraft/class_\\d+(\\$class_\\d+)*");

    private static int pass;
    private static int fail;

    private JarCheck() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.out.println("usage: JarCheck <path-to.jar>");
            System.exit(2);
        }
        Path jar = Path.of(args[0]);
        if (!Files.isRegularFile(jar)) {
            System.out.println("FAIL jar does not exist: " + jar);
            System.exit(2);
        }
        checkJar(jar);
        System.out.println("jarcheck: pass=" + pass + " fail=" + fail + " (" + jar.getFileName() + ")");
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void checkJar(Path jar) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                if (entry.isDirectory()) {
                    continue;
                }
                try (InputStream stream = zip.getInputStream(entry)) {
                    entries.put(entry.getName(), readAll(stream));
                }
            }
        }

        check("jar has classes", true, entries.keySet().stream().anyMatch(n -> n.endsWith(".class")));
        check("jar has fabric.mod.json", true, entries.containsKey("fabric.mod.json"));
        check("jar ships the license", true, entries.containsKey("LICENSE"));
        check("jar ships the icon", true, entries.containsKey("assets/splitplay/icon.png"));

        String metadata = text(entries, "fabric.mod.json");
        check("metadata declares the id", true, metadata.contains("\"id\": \"splitplay\""));
        check("metadata declares the license", true, metadata.contains("\"license\": \"MIT\""));
        check("metadata declares the icon", true, metadata.contains("\"icon\": \"assets/splitplay/icon.png\""));
        check("metadata is client only", true, metadata.contains("\"environment\": \"client\""));
        check("metadata wires the mixin config", true, metadata.contains("splitplay.mixins.json"));

        String mixins = text(entries, "splitplay.mixins.json");
        Set<String> mixinClasses = quotedArray(mixins, "mixins");
        Set<String> clientMixins = quotedArray(mixins, "client");
        check("mixin config lists classes", true, !mixinClasses.isEmpty() || !clientMixins.isEmpty());
        for (String name : mixinClasses) {
            check("mixin class packaged: " + name, true, entries.containsKey("cn/splitplay/mixin/" + name + ".class"));
        }
        for (String name : clientMixins) {
            check("client mixin packaged: " + name, true, entries.containsKey("cn/splitplay/mixin/" + name + ".class"));
        }

        String refmapName = jsonString(mixins, "refmap");
        check("mixin config declares a refmap", true, !refmapName.isEmpty());
        if (!refmapName.isEmpty()) {
            check("refmap packaged", true, entries.containsKey(refmapName));
            String refmap = text(entries, refmapName);
            check("refmap has mappings", true, refmap.contains("method_") || refmap.contains("field_"));
        }

        // The remap gate: every Minecraft reference must already be intermediary.
        Set<String> readable = new LinkedHashSet<>();
        int classes = 0;
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            if (!entry.getKey().endsWith(".class")) {
                continue;
            }
            classes++;
            Matcher matcher = MINECRAFT_REF.matcher(new String(entry.getValue(), StandardCharsets.ISO_8859_1));
            while (matcher.find()) {
                String ref = matcher.group();
                if (!INTERMEDIARY.matcher(ref).matches()) {
                    readable.add(ref);
                }
            }
        }
        check("jar contains a sane number of classes", true, classes > 20);
        check("no readable Minecraft names (jar is remapped)", true, readable.isEmpty());
        if (!readable.isEmpty()) {
            List<String> sample = new ArrayList<>(readable).subList(0, Math.min(5, readable.size()));
            System.out.println("  readable references found: " + sample);
        }

        // Nothing from the upstream mod this was inspired by should be left behind.
        boolean upstream = entries.keySet().stream().anyMatch(n -> n.startsWith("cn/kafei/"));
        check("no upstream classes", false, upstream);
    }

    private static String text(Map<String, byte[]> entries, String name) {
        byte[] bytes = entries.get(name);
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }

    private static Set<String> quotedArray(String json, String key) {
        Set<String> values = new LinkedHashSet<>();
        int at = json.indexOf('"' + key + '"');
        if (at < 0) {
            return values;
        }
        int open = json.indexOf('[', at);
        int close = json.indexOf(']', open);
        if (open < 0 || close < 0) {
            return values;
        }
        Matcher matcher = Pattern.compile("\"([^\"]+)\"").matcher(json.substring(open, close));
        while (matcher.find()) {
            values.add(matcher.group(1));
        }
        return values;
    }

    private static String jsonString(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return matcher.find() ? matcher.group(1) : "";
    }

    private static byte[] readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    private static void check(String name, Object expected, Object actual) {
        boolean ok = expected == null ? actual == null : expected.equals(actual);
        if (ok) {
            pass++;
        } else {
            fail++;
            System.out.println("FAIL " + name + ": expected <" + expected + "> got <" + actual + ">");
        }
    }
}
