package emosh;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.squareup.javapoet.*;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import javax.lang.model.element.Modifier;
import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Maven Mojo that reads GraalVM Native Image hint JSON files and generates a
 * Spring AOT {@code RuntimeHintsRegistrar} source file containing only the
 * application-specific hints (i.e. entries present in {@code full/} but absent
 * from {@code baseline/}).
 *
 * <p>Bind it to the {@code generate-sources} phase so the produced class is
 * compiled alongside the rest of the project:
 * <pre>{@code
 * <plugin>
 *   <groupId>emosh</groupId>
 *   <artifactId>emosh-maven-plugin</artifactId>
 *   <executions>
 *     <execution>
 *       <goals><goal>generate</goal></goals>
 *     </execution>
 *   </executions>
 * </plugin>
 * }</pre>
 */
@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class HintMojo extends AbstractMojo {

    // -----------------------------------------------------------------------
    // Spring AOT class/method names used in the generated source
    // -----------------------------------------------------------------------
    private static final String AOT_HINTS_PKG   = "org.springframework.aot.hint";
    private static final String RUNTIME_HINTS   = "RuntimeHints";
    private static final String REGISTRAR       = "RuntimeHintsRegistrar";
    private static final String TYPE_REFERENCE  = "TypeReference";
    private static final String MEMBER_CATEGORY = "MemberCategory";

    /**
     * Resource patterns that Spring Boot / the JDK already register on their
     * own; registering them again is harmless but produces unnecessary noise.
     *
     * Rules:
     *  - Substring match: if the pattern *contains* any entry below it is skipped.
     *  - Keep entries as specific as needed to avoid false positives.
     */
    private static final Set<String> RESOURCE_PATTERN_BLOCKLIST = Set.of(
            // JDK / standard service-loader — handled by GraalVM agent itself
            "META-INF/services",
            ".class",

            // Spring Boot autoconfigure infrastructure
            "META-INF/spring.factories",
            "META-INF/spring-autoconfigure",   // covers spring-autoconfigure-metadata.properties
            "META-INF/spring/",                // covers META-INF/spring/** AOT files
            "META-INF/spring.components",

            // Spring Boot property sources — PropertySourceLoader already registers these
            "application.properties",
            "application.xml",
            "application.yaml",
            "application.yml",
            "application-default.properties",
            "application-default.xml",
            "application-default.yaml",
            "application-default.yml",
            "config/application"               // covers all config/application-*.* variants
    );

    // -----------------------------------------------------------------------
    // Plugin parameters
    // -----------------------------------------------------------------------

    /** Root directory that contains the {@code baseline/} and {@code full/} subdirectories. */
    @Parameter(defaultValue = "${project.basedir}/src/main/resources/META-INF/native-image")
    private File nativeImageDir;

    /** Directory where the generated {@code .java} source file will be written. */
    @Parameter(defaultValue = "${project.build.directory}/generated-sources/annotations")
    private File outputDir;

    // -----------------------------------------------------------------------
    // Jackson – reuse a single, thread-safe instance
    // -----------------------------------------------------------------------
    private final ObjectMapper mapper = new ObjectMapper();

    // -----------------------------------------------------------------------
    // Entry point
    // -----------------------------------------------------------------------

    @Override
    public void execute() throws MojoExecutionException {
        ensureOutputDir();

        try {
            ClassName runtimeHints = ClassName.get(AOT_HINTS_PKG, RUNTIME_HINTS);
            ClassName registrar    = ClassName.get(AOT_HINTS_PKG, REGISTRAR);

            MethodSpec.Builder registerHints = MethodSpec.methodBuilder("registerHints")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(runtimeHints, "hints")
                    .addParameter(ClassLoader.class, "classLoader");

            processReflection(registerHints);
            processResources(registerHints);

            TypeSpec generatedClass = TypeSpec.classBuilder("GeneratedNativeHints")
                    .addModifiers(Modifier.PUBLIC)
                    .addSuperinterface(registrar)
                    .addMethod(registerHints.build())
                    .build();

            JavaFile.builder("emosh.generated", generatedClass)
                    .build()
                    .writeTo(outputDir);

            getLog().info("Native hints generated successfully → emosh.generated.GeneratedNativeHints");

        } catch (IOException e) {
            throw new MojoExecutionException("I/O error while generating hints: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new MojoExecutionException("Unexpected error while generating hints: " + e.getMessage(), e);
        }
    }

    // -----------------------------------------------------------------------
    // Reflection hints
    // -----------------------------------------------------------------------

    /**
     * Reads {@code full/reflect-config.json}, subtracts the entries already
     * present in {@code baseline/reflect-config.json}, and emits one
     * {@code hints.reflection().registerType(…)} statement per remaining entry.
     */
    private void processReflection(MethodSpec.Builder method) throws IOException {
        File fullFile     = resolveHintFile("full",     "reflect-config.json");
        File baselineFile = resolveHintFile("baseline", "reflect-config.json");

        if (!fullFile.exists()) {
            getLog().warn("reflect-config.json not found under 'full/' – skipping reflection hints.");
            return;
        }

        Set<String> baseline = loadTypeNames(baselineFile);

        ClassName typeRef   = ClassName.get(AOT_HINTS_PKG, TYPE_REFERENCE);
        ClassName memberCat = ClassName.get(AOT_HINTS_PKG, MEMBER_CATEGORY);

        JsonNode root = mapper.readTree(fullFile);
        if (!root.isArray()) {
            getLog().warn("reflect-config.json does not contain a JSON array – skipping.");
            return;
        }

        int count = 0;
        for (JsonNode entry : root) {
            String className = entry.path("name").asText(null);
            if (className == null || className.isBlank()) {
                getLog().debug("Skipping reflection entry with missing/blank 'name'.");
                continue;
            }
            if (baseline.contains(className)) {
                continue;
            }

            MemberCategorySet cats = resolveMemberCategories(entry);
            emitReflectionStatement(method, typeRef, memberCat, className, cats);
            count++;
        }

        getLog().info("Reflection hints registered: " + count);
    }

    /**
     * Chooses the appropriate {@link MemberCategorySet} for a given
     * reflect-config entry by inspecting the boolean flags GraalVM writes.
     *
     * <p>Falls back to {@code PUBLIC_CONSTRUCTORS + PUBLIC_METHODS} when no
     * explicit flags are present.
     */
    private MemberCategorySet resolveMemberCategories(JsonNode entry) {
        boolean allDeclared =
                entry.path("allDeclaredConstructors").asBoolean(false) ||
                        entry.path("allDeclaredMethods").asBoolean(false)      ||
                        entry.path("allDeclaredFields").asBoolean(false);

        if (allDeclared) {
            return MemberCategorySet.ALL_DECLARED;
        }

        boolean needsFields = entry.path("allPublicFields").asBoolean(false);
        if (needsFields) {
            return MemberCategorySet.PUBLIC_ALL;
        }

        return MemberCategorySet.PUBLIC_CONSTRUCTORS_AND_METHODS;
    }

    /** Emits the correct {@code registerType} call for the resolved category set. */
    private void emitReflectionStatement(MethodSpec.Builder method,
                                         ClassName typeRef,
                                         ClassName memberCat,
                                         String className,
                                         MemberCategorySet cats) {
        switch (cats) {
            case ALL_DECLARED ->
                    method.addStatement(
                            "hints.reflection().registerType($T.of($S), b -> b.withMembers(" +
                                    "$T.INVOKE_DECLARED_CONSTRUCTORS, $T.INVOKE_DECLARED_METHODS, $T.DECLARED_FIELDS))",
                            typeRef, className, memberCat, memberCat, memberCat);

            case PUBLIC_ALL ->
                    method.addStatement(
                            "hints.reflection().registerType($T.of($S), b -> b.withMembers(" +
                                    "$T.INVOKE_PUBLIC_CONSTRUCTORS, $T.INVOKE_PUBLIC_METHODS, $T.PUBLIC_FIELDS))",
                            typeRef, className, memberCat, memberCat, memberCat);

            default ->
                    method.addStatement(
                            "hints.reflection().registerType($T.of($S), b -> b.withMembers(" +
                                    "$T.INVOKE_PUBLIC_CONSTRUCTORS, $T.INVOKE_PUBLIC_METHODS))",
                            typeRef, className, memberCat, memberCat);
        }
    }

    // -----------------------------------------------------------------------
    // Resource hints
    // -----------------------------------------------------------------------

    /**
     * Reads {@code full/resource-config.json} and emits one
     * {@code hints.resources().registerPattern(…)} statement per include
     * pattern that is not covered by the blocklist.
     *
     * <p>Patterns that are blank or resolve to an empty literal (e.g. {@code \Q\E})
     * are silently dropped — they match nothing useful and indicate a malformed
     * entry in the source JSON.
     */
    private void processResources(MethodSpec.Builder method) throws IOException {
        File fullFile = resolveHintFile("full", "resource-config.json");

        if (!fullFile.exists()) {
            getLog().warn("resource-config.json not found under 'full/' – skipping resource hints.");
            return;
        }

        JsonNode root      = mapper.readTree(fullFile);
        JsonNode resources = root.path("resources");

        if (resources.isMissingNode()) {
            getLog().warn("resource-config.json has no 'resources' key – skipping.");
            return;
        }

        JsonNode includes = resources.path("includes");
        if (!includes.isArray()) {
            getLog().debug("No 'includes' array found in resource-config.json.");
            return;
        }

        int count = 0;
        for (JsonNode include : includes) {
            String pattern = include.path("pattern").asText(null);

            // Drop null, blank, or effectively-empty patterns (e.g. bare \Q\E)
            if (pattern == null || pattern.isBlank() || isEmptyLiteralPattern(pattern)) {
                getLog().debug("Skipping empty/blank resource pattern: " + pattern);
                continue;
            }

            if (isBlocklisted(pattern)) {
                getLog().debug("Skipping blocklisted resource pattern: " + pattern);
                continue;
            }

            method.addStatement("hints.resources().registerPattern($S)", pattern);
            count++;
        }

        getLog().info("Resource hints registered: " + count);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Loads all {@code name} values from a reflect-config JSON array.
     * Returns an empty set if the file does not exist.
     */
    private Set<String> loadTypeNames(File file) throws IOException {
        Set<String> names = new HashSet<>();
        if (!file.exists()) {
            return names;
        }

        JsonNode root = mapper.readTree(file);
        if (!root.isArray()) {
            getLog().warn(file.getName() + " is not a JSON array – treating as empty.");
            return names;
        }

        for (JsonNode entry : root) {
            String name = entry.path("name").asText(null);
            if (name != null && !name.isBlank()) {
                names.add(name);
            }
        }

        return names;
    }

    /** Returns {@code true} when a resource pattern matches any blocklist entry. */
    private boolean isBlocklisted(String pattern) {
        return RESOURCE_PATTERN_BLOCKLIST.stream().anyMatch(pattern::contains);
    }

    /**
     * Returns {@code true} for patterns that are syntactically valid but match
     * nothing useful — specifically the bare {@code \Q\E} that GraalVM occasionally
     * writes for empty resource entries.
     */
    private boolean isEmptyLiteralPattern(String pattern) {
        String stripped = pattern.strip();
        return stripped.equals("\\Q\\E") || stripped.equals("\\Q \\E");
    }

    /** Resolves a hint file path relative to {@link #nativeImageDir}. */
    private File resolveHintFile(String subDir, String fileName) {
        return new File(new File(nativeImageDir, subDir), fileName);
    }

    /** Creates {@link #outputDir} (and any missing parents) if it does not exist. */
    private void ensureOutputDir() throws MojoExecutionException {
        if (!outputDir.exists() && !outputDir.mkdirs()) {
            throw new MojoExecutionException(
                    "Could not create output directory: " + outputDir.getAbsolutePath());
        }
    }

    // -----------------------------------------------------------------------
    // Internal enum – avoids magic strings for member-category combinations
    // -----------------------------------------------------------------------

    private enum MemberCategorySet {
        PUBLIC_CONSTRUCTORS_AND_METHODS,
        PUBLIC_ALL,
        ALL_DECLARED
    }
}