package emosh;

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
import java.util.*;

@Mojo(name = "generate", defaultPhase = LifecyclePhase.GENERATE_SOURCES)
public class HintMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project.basedir}/src/main/resources/META-INF/native-image")
    private File nativeImageDir;

    @Parameter(defaultValue = "${project.build.directory}/generated-sources/annotations")
    private File outputDir;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void execute() throws MojoExecutionException {
        try {
            // Build Spring AOT Hint classes
            ClassName runtimeHints = ClassName.get("org.springframework.aot.hint", "RuntimeHints");
            ClassName registrar = ClassName.get("org.springframework.aot.hint", "RuntimeHintsRegistrar");

            MethodSpec.Builder methodBuilder = MethodSpec.methodBuilder("registerHints")
                    .addAnnotation(Override.class)
                    .addModifiers(Modifier.PUBLIC)
                    .addParameter(runtimeHints, "hints")
                    .addParameter(ClassLoader.class, "classLoader");

            // Process Reflection (The logic we already built)
            processReflection(methodBuilder);

            // Process Resources (pptx, pdf, fonts, etc.)
            processResources(methodBuilder);

            // Build and write the class
            TypeSpec hintClass = TypeSpec.classBuilder("GeneratedNativeHints")
                    .addModifiers(Modifier.PUBLIC)
                    .addSuperinterface(registrar)
                    .addMethod(methodBuilder.build())
                    .build();

            JavaFile.builder("emosh.generated", hintClass).build().writeTo(outputDir);
            getLog().info("Full-stack Native Hints generated successfully!");

        } catch (Exception e) {
            throw new MojoExecutionException("Error generating hints: " + e.getMessage(), e);
        }
    }

    private void processReflection(MethodSpec.Builder method) throws IOException {
        File baselineFile = new File(nativeImageDir, "baseline/reflect-config.json");
        File fullFile = new File(nativeImageDir, "full/reflect-config.json");

        if (!fullFile.exists()) return;

        Set<String> baseline = loadNamesFromJson(baselineFile);
        List<Map<String, Object>> full = mapper.readValue(fullFile, List.class);

        ClassName typeRef = ClassName.get("org.springframework.aot.hint", "TypeReference");
        ClassName memberCat = ClassName.get("org.springframework.aot.hint", "MemberCategory");

        int count = 0;
        for (Map<String, Object> item : full) {
            String className = (String) item.get("name");
            if (!baseline.contains(className)) {
                method.addStatement("hints.reflection().registerType($T.of($S), b -> b.withMembers($T.INVOKE_PUBLIC_CONSTRUCTORS, $T.INVOKE_PUBLIC_METHODS))",
                        typeRef, className, memberCat, memberCat);
                count++;
            }
        }
        getLog().info("Processed " + count + " unique Reflection hints.");
    }

    private void processResources(MethodSpec.Builder method) throws IOException {
        File fullFile = new File(nativeImageDir, "full/resource-config.json");
        if (!fullFile.exists()) return;

        Map<String, Object> data = mapper.readValue(fullFile, Map.class);
        List<Map<String, String>> includes = (List<Map<String, String>>) ((Map) data.get("resources")).get("includes");

        int count = 0;
        for (Map<String, String> include : includes) {
            String pattern = include.get("pattern");
            // Ignore common Spring/JDK resources that are already handled
            if (!pattern.contains("META-INF/services") && !pattern.endsWith(".class")) {
                method.addStatement("hints.resources().registerPattern($S)", pattern);
                count++;
            }
        }
        getLog().info("Processed " + count + " unique Resource patterns.");
    }

    private Set<String> loadNamesFromJson(File file) throws IOException {
        if (!file.exists()) return Collections.emptySet();
        List<Map<String, Object>> list = mapper.readValue(file, List.class);
        Set<String> names = new HashSet<>();
        for (Map<String, Object> item : list) names.add((String) item.get("name"));
        return names;
    }
}