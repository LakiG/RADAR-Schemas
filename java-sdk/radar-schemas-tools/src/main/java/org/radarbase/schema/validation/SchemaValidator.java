/*
 * Copyright 2017 King's College London and The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarbase.schema.validation;

import static java.util.function.Function.identity;
import static java.util.function.Predicate.not;
import static org.radarbase.schema.validation.ValidationHelper.COMMONS_PATH;
import static org.radarbase.schema.validation.rules.Validator.raise;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;
import org.apache.avro.Schema;
import org.apache.avro.Schema.Parser;
import org.radarbase.schema.CommandLineApp;
import org.radarbase.schema.Scope;
import org.radarbase.schema.util.SubCommand;
import org.radarbase.schema.validation.config.ExcludeConfig;
import org.radarbase.schema.validation.rules.RadarSchemaMetadataRules;
import org.radarbase.schema.validation.rules.RadarSchemaRules;
import org.radarbase.schema.validation.rules.SchemaMetadata;
import org.radarbase.schema.validation.rules.SchemaMetadataRules;
import org.radarbase.schema.validation.rules.Validator;

/**
 * Validator for a set of RADAR-Schemas.
 */
public class SchemaValidator {
    public static final String AVRO_EXTENSION = "avsc";

    private final Path root;
    private final ExcludeConfig config;
    private final Validator<SchemaMetadata> validator;
    private final SchemaMetadataRules rules;

    /**
     * Schema validator for given RADAR-Schemas directory.
     * @param root RADAR-Schemas directory.
     * @param config configuration to exclude certain schemas or fields from validation.
     */
    public SchemaValidator(Path root, ExcludeConfig config) {
        this.config = config;
        this.root = root;
        this.rules = new RadarSchemaMetadataRules(root, config);
        this.validator = rules.getValidator();
    }

    /**
     * TODO.
     * @param scope TODO.
     */
    public Stream<ValidationException> analyseFiles(Scope scope) {
        try {
            List<Path> avroFiles = Files.walk(scope.getPath(root.resolve(COMMONS_PATH)))
                    .filter(Files::isRegularFile)
                    .filter(SchemaValidator::isAvscFile)
                    .filter(not(config::skipFile))
                    .collect(Collectors.toList());

            Map<String, SchemaMetadata> schemas = new HashMap<>();
            int prevSize = -1;

            // Recursively parse all schemas.
            // If the parsed schema size does not change anymore, the final schemas cannot be parsed
            // at all.
            while (prevSize != schemas.size()) {
                prevSize = schemas.size();
                Map<String, Schema> useTypes = schemas.entrySet().stream()
                        .collect(Collectors.toMap(Entry::getKey, e -> e.getValue().getSchema()));
                Set<Path> ignoreFiles = schemas.values().stream()
                        .map(SchemaMetadata::getPath)
                        .collect(Collectors.toSet());

                schemas.putAll(avroFiles.stream()
                        .filter(not(ignoreFiles::contains))
                        .map(p -> {
                            @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
                            Parser parser = new Parser();
                            parser.addTypes(useTypes);
                            try {
                                return new SchemaMetadata(parser.parse(p.toFile()), scope, p);
                            } catch (Exception ex) {
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toMap(
                                m -> m.getSchema().getFullName(),
                                identity(),
                                (v1, v2) -> {
                                    if (v1.equals(v2)) {
                                        return v1;
                                    } else {
                                        throw new IllegalStateException("Duplicate enum: " + v1);
                                    }
                                })));
            }

            Set<Path> ignoreFiles = schemas.values().stream()
                    .map(SchemaMetadata::getPath)
                    .collect(Collectors.toSet());
            Map<String, Schema> useTypes = schemas.entrySet().stream()
                    .collect(Collectors.toMap(Entry::getKey, e -> e.getValue().getSchema()));

            return Stream.concat(
                    avroFiles.stream()
                            .filter(not(ignoreFiles::contains))
                            .map(p -> {
                                Parser parser = new Parser();
                                parser.addTypes(useTypes);
                                try {
                                    parser.parse(p.toFile());
                                    return null;
                                } catch (Exception ex) {
                                    return new ValidationException("Cannot parse schema", ex);
                                }
                            })
                            .filter(Objects::nonNull),
                    schemas.values().stream()
                            .flatMap(this::validate)
            );
        } catch (IOException ex) {
            return raise("Failed to read files: " + ex, ex);
        }
    }

    /**
     * TODO.
     */
    public Stream<ValidationException> analyseFiles() {
        return Arrays.stream(Scope.values())
                .flatMap(this::analyseFiles);
    }

    /** Validate a single schema in given path. */
    public Stream<ValidationException> validate(Schema schema, Path path, Scope scope) {
        return validate(new SchemaMetadata(schema, scope, path));
    }

    /** Validate a single schema in given path. */
    public Stream<ValidationException> validate(SchemaMetadata schemaMetadata) {
        return validator.apply(schemaMetadata);
    }

    /** Formats a stream of validation exceptions. */
    public static String format(Stream<ValidationException> exceptionStream) {
        return exceptionStream
                .map(ex -> "Validation FAILED:\n" + ex.getMessage() + "\n\n")
                .collect(Collectors.joining());
    }

    /**
     * TODO.
     * @param file TODO
     * @return TODO
     */
    private static boolean isAvscFile(Path file) {
        return ValidationHelper.matchesExtension(file, AVRO_EXTENSION);
    }

    /**
     * TODO.
     * @param path TODO
     * @return TODO
     */
    public static String getPath(Path path) {
        return path.toString().substring(path.toString().indexOf(ExcludeConfig.REPOSITORY_NAME));
    }

    public SchemaMetadataRules getRules() {
        return rules;
    }

    public Map<String, Schema> getValidatedSchemas() {
        return ((RadarSchemaRules) rules.getSchemaRules()).getSchemaStore();
    }

    /** Schema validator as a command. */
    public static SubCommand command() {
        return new SchemaValidatorCommand();
    }

    @SuppressWarnings("PMD.SystemPrintln")
    private static class SchemaValidatorCommand implements SubCommand {
        @Override
        public String getName() {
            return "validate";
        }

        @Override
        public int execute(Namespace options, CommandLineApp app) {
            try {
                ExcludeConfig config = loadConfig(app.getRoot(), options.getString("config"));
                SchemaValidator validator = new SchemaValidator(app.getRoot(), config);
                Stream<ValidationException> stream = validateSchemas(
                        options.getString("scope"), validator);

                if (options.getBoolean("quiet")) {
                    return stream.count() > 0 ? 1 : 0;
                } else {
                    String result = SchemaValidator.format(stream);

                    System.out.println(result);
                    if (options.getBoolean("verbose")) {
                        System.out.println("Validated schemas:");
                        Set<String> names = new TreeSet<>(validator.getValidatedSchemas().keySet());
                        for (String name : names) {
                            System.out.println(" - " + name);
                        }
                        System.out.println();
                    }
                    return result.isEmpty() ? 0 : 1;
                }
            } catch (IOException e) {
                System.err.println("Failed to load schemas: " + e);
                return 1;
            }
        }

        @Override
        public void addParser(ArgumentParser parser) {
            parser.description("Validate a set of specifications.");
            parser.addArgument("-s", "--scope")
                    .help("type of specifications to validate")
                    .choices(Scope.values());
            parser.addArgument("-c", "--config")
                    .help("configuration file to use");
            parser.addArgument("-v", "--verbose")
                    .help("verbose validation message")
                    .action(Arguments.storeTrue());
            parser.addArgument("-q", "--quiet")
                    .help("only set exit code.")
                    .action(Arguments.storeTrue());
            SubCommand.addRootArgument(parser);
        }


        private Stream<ValidationException> validateSchemas(String scopeString,
                SchemaValidator validator) {
            if (scopeString == null) {
                return validator.analyseFiles();
            } else {
                return validator.analyseFiles(Scope.valueOf(scopeString));
            }
        }

        private ExcludeConfig loadConfig(Path root, String configSubPath) throws IOException {
            Path configPath = null;
            if (configSubPath != null) {
                if (configSubPath.charAt(0) == '/') {
                    configPath = Paths.get(configSubPath);
                } else {
                    configPath = root.resolve(configSubPath);
                }
            }
            return ExcludeConfig.load(configPath);
        }
    }
}
