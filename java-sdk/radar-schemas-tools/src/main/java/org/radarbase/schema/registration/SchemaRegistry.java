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

package org.radarbase.schema.registration;

import static org.radarbase.util.Strings.isNullOrEmpty;
import static org.radarbase.schema.CommandLineApp.matchTopic;

import java.io.IOException;
import java.net.MalformedURLException;
import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import net.sourceforge.argparse4j.impl.Arguments;
import net.sourceforge.argparse4j.inf.ArgumentParser;
import net.sourceforge.argparse4j.inf.Namespace;
import okhttp3.Credentials;
import okhttp3.Headers;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import org.radarbase.config.ServerConfig;
import org.radarbase.producer.rest.RestClient;
import org.radarbase.producer.rest.SchemaRetriever;
import org.radarbase.topic.AvroTopic;
import org.radarbase.schema.CommandLineApp;
import org.radarbase.schema.specification.DataProducer;
import org.radarbase.schema.specification.SourceCatalogue;
import org.radarbase.schema.util.SubCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Schema registry interface.
 */
public class SchemaRegistry {
    public enum Compatibility {
        NONE, FULL, BACKWARD, FORWARD, BACKWARD_TRANSITIVE, FORWARD_TRANSITIVE, FULL_TRANSITIVE
    }

    private static final Logger logger = LoggerFactory.getLogger(SchemaRegistry.class);
    private final SchemaRetriever schemaClient;
    private final RestClient httpClient;

    /**
     * Schema registry for given URL. If this is https, unsafe certificates are accepted.
     * @param baseUrl URL of the schema registry
     * @throws MalformedURLException if given URL is invalid.
     */
    public SchemaRegistry(String baseUrl) throws MalformedURLException {
        ServerConfig config = new ServerConfig(baseUrl);
        config.setUnsafe(false);
        this.httpClient = RestClient.global()
            .timeout(10, TimeUnit.SECONDS)
            .server(config)
            .build();
        this.schemaClient = new SchemaRetriever(this.httpClient);
    }

    public SchemaRegistry(String baseUrl, String apiKey, String apiSecret)
            throws MalformedURLException {
        ServerConfig config = new ServerConfig(baseUrl);
        config.setUnsafe(true);
        this.httpClient = RestClient.global()
                .timeout(10, TimeUnit.SECONDS)
                .server(config)
                .headers(Headers.of("Authorization", Credentials.basic(apiKey, apiSecret)))
                .build();
        this.schemaClient = new SchemaRetriever(this.httpClient);
    }

    /**
     * Register all schemas in a source catalogue. Stream and connector sources are ignored.
     * @param catalogue schema catalogue to read schemas from
     * @return whether all schemas were successfully registered.
     */
    public boolean registerSchemas(SourceCatalogue catalogue) {
        return catalogue.getSources().stream()
                .filter(DataProducer::doRegisterSchema)
                .flatMap(DataProducer::getTopics)
                .sorted(Comparator.comparing(AvroTopic::getName))
                .distinct()
                .peek(t -> logger.info("Registering topic {} schemas: {} - {}",
                        t.getName(), t.getKeySchema().getFullName(),
                        t.getValueSchema().getFullName()))
                .allMatch(this::registerSchema);
    }

    /** Register the schema of a single topic. */
    public boolean registerSchema(AvroTopic<?, ?> topic) {
        try {
            this.schemaClient.addSchema(topic.getName(), false, topic.getKeySchema());
            this.schemaClient.addSchema(topic.getName(), true, topic.getValueSchema());
            return true;
        } catch (IOException ex) {
            logger.error("Failed to register schemas for topic {}", topic.getName(), ex);
            return false;
        }
    }

    /**
     * Set the compatibility level of the schema registry.
     * @param compatibility target compatibility level.
     * @return whether the request was successful.
     */
    public boolean putCompatibility(Compatibility compatibility) {
        logger.info("Setting compatibility to {}", compatibility);

        Request request;
        try {
            request = httpClient.requestBuilder("config")
                    .put(new RequestBody() {
                        @Override
                        public MediaType contentType() {
                            return MediaType.parse(
                                    "application/vnd.schemaregistry.v1+json; charset=utf-8");
                        }

                        @Override
                        public void writeTo(BufferedSink sink) throws IOException {
                            sink.writeUtf8("{\"compatibility\": \"");
                            sink.writeUtf8(compatibility.name());
                            sink.writeUtf8("\"}");
                        }
                    })
                    .build();
        } catch (MalformedURLException ex) {
            // should not occur with valid base URL
            return false;
        }

        try (Response response = httpClient.request(request);
                ResponseBody body = response.body()) {
            if (response.isSuccessful()) {
                logger.info("Compatibility set to {}", compatibility);
                return true;
            } else {
                String bodyString = body == null ? null : body.string();
                logger.info("Failed to set compatibility set to {}: {}", compatibility, bodyString);
                return false;
            }
        } catch (IOException ex) {
            logger.error("Error changing compatibility level to {}", compatibility, ex);
            return false;
        }
    }

    /** Return the schema registry as a subcommand. */
    public static SubCommand command() {
        return new RegisterCommand();
    }

    private static class RegisterCommand implements SubCommand {
        @Override
        public String getName() {
            return "register";
        }

        @Override
        public int execute(Namespace options, CommandLineApp app) {
            String url = options.get("schemaRegistry");
            String apiKey = options.getString("api_key");
            String apiSecret = options.getString("api_secret");
            try {
                SchemaRegistry registration;
                if (isNullOrEmpty(apiKey) || isNullOrEmpty(apiSecret)) {
                    logger.info("Initializing standard SchemaRegistration ...");
                    registration = new SchemaRegistry(url);
                } else {
                    logger.info("Initializing SchemaRegistration with authentication...");
                    registration = new SchemaRegistry(url, apiKey, apiSecret);
                }

                boolean forced = options.getBoolean("force");
                if (forced && !registration.putCompatibility(Compatibility.NONE)) {
                    return 1;
                }
                boolean result;
                Pattern pattern = matchTopic(
                        options.getString("topic"), options.getString("match"));

                if (pattern == null) {
                    result = registration.registerSchemas(app.getCatalogue());
                } else {
                    Optional<Boolean> didUpload = app.getCatalogue().getTopics()
                            .filter(t -> pattern.matcher(t.getName()).find())
                            .map(registration::registerSchema)
                            .reduce((a, b) -> a && b);

                    if (didUpload.isPresent()) {
                        result = didUpload.get();
                    } else {
                        logger.error("Topic {} does not match a known topic."
                                + " Find the list of acceptable topics"
                                + " with the `radar-schemas-tools list` command. Aborting.",
                                pattern);
                        result = false;
                    }
                }
                if (forced) {
                    registration.putCompatibility(Compatibility.FULL);
                }
                return result ? 0 : 1;
            } catch (MalformedURLException ex) {
                logger.error("Schema registry URL {} is invalid: {}", url, ex.toString());
                return 1;
            }
        }

        @Override
        public void addParser(ArgumentParser parser) {
            parser.description("Register schemas in the schema registry.");
            parser.addArgument("-f", "--force")
                    .help("force registering schema, even if it is incompatible")
                    .action(Arguments.storeTrue());
            parser.addArgument("-t", "--topic")
                    .help("register the schemas of one topic")
                    .type(String.class);
            parser.addArgument("-m", "--match")
                    .help("register the schemas of all topics matching the given regex"
                            + "; does not do anything if --topic is specified")
                    .type(String.class);
            parser.addArgument("schemaRegistry")
                    .help("schema registry URL");
            parser.addArgument("-u", "--api-key")
                    .help("Client password to authorize with.");
            parser.addArgument("-p", "--api-secret")
                    .help("Client key to authorize with.");
            SubCommand.addRootArgument(parser);
        }
    }
}
