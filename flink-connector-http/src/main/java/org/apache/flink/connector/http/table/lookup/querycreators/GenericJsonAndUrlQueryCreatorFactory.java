/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.connector.http.table.lookup.querycreators;

import org.apache.flink.api.common.serialization.SerializationSchema;
import org.apache.flink.configuration.ConfigOption;
import org.apache.flink.configuration.ConfigOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.ReadableConfig;
import org.apache.flink.connector.http.LookupQueryCreator;
import org.apache.flink.connector.http.LookupQueryCreatorFactory;
import org.apache.flink.connector.http.table.lookup.LookupRow;
import org.apache.flink.connector.http.utils.SynchronizedSerializationSchema;
import org.apache.flink.table.connector.format.EncodingFormat;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.factories.DynamicTableFactory;
import org.apache.flink.table.factories.FactoryUtil;
import org.apache.flink.table.factories.SerializationFormatFactory;

import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.core.JsonProcessingException;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.JsonNode;
import org.apache.flink.shaded.jackson2.com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.apache.flink.configuration.ConfigOptions.key;
import static org.apache.flink.connector.http.table.lookup.HttpLookupConnectorOptions.ASYNC_POLLING;
import static org.apache.flink.connector.http.table.lookup.HttpLookupConnectorOptions.LOOKUP_METHOD;
import static org.apache.flink.connector.http.table.lookup.HttpLookupConnectorOptions.LOOKUP_REQUEST_FORMAT;

/**
 * Generic JSON and url query creator factory defined configuration to define the columns to be.
 *
 * <ol>
 *   <li>List of column names to be included in the query params
 *   <li>List of column names to be included in the body (for PUT and POST)
 *   <li>Map of templated uri segment names to column names
 * </ol>
 */
@SuppressWarnings({"checkstyle:RegexpSingleline", "checkstyle:LineLength"})
public class GenericJsonAndUrlQueryCreatorFactory implements LookupQueryCreatorFactory {
    private static final long serialVersionUID = 1L;

    public static final String ID = "http-generic-json-url";

    public static final ConfigOption<List<String>> REQUEST_QUERY_PARAM_FIELDS =
            key("http.request.query-param-fields")
                    .stringType()
                    .asList()
                    .defaultValues() // default to empty list so we do not need to check for null
                    .withDescription(
                            "The names of the fields that will be mapped to query parameters."
                                    + " The parameters are separated by semicolons,"
                                    + " such as 'param1;param2'.");
    public static final ConfigOption<List<String>> REQUEST_BODY_FIELDS =
            key("http.request.body-fields")
                    .stringType()
                    .asList()
                    .defaultValues() // default to empty list so we do not need to check for null
                    .withDescription(
                            "The names of the fields that will be mapped to the body."
                                    + " The parameters are separated by semicolons,"
                                    + " such as 'param1;param2'.");
    public static final ConfigOption<Map<String, String>> REQUEST_URL_MAP =
            ConfigOptions.key("http.request.url-map")
                    .mapType()
                    .noDefaultValue()
                    .withDescription(
                            "The map of insert names to column names used"
                                    + "as url segments. Parses a string as a map of strings. "
                                    + "<br>"
                                    + "For example if there are table columns called customerId"
                                    + " and orderId, then specifying value customerId:cid1,orderID:oid"
                                    + " and a url of https://myendpoint/customers/{cid}/orders/{oid}"
                                    + " will mean that the url used for the lookup query will"
                                    + " dynamically pickup the values for customerId, orderId"
                                    + " and use them in the url."
                                    + "<br>Notes<br>"
                                    + "The expected format of the map is:"
                                    + "<br>"
                                    + " key1:value1,key2:value2");
    public static final ConfigOption<String> REQUEST_ADDITIONAL_BODY_JSON =
            key("http.request.additional-body-json")
                    .stringType()
                    .noDefaultValue()
                    .withDescription(
                            "Additional JSON content to be merged into the request body"
                                    + " for PUT and POST operations. The value should be a valid"
                                    + " JSON object string (e.g., '{\"c\":789}') that will be parsed"
                                    + " and its fields merged at the top level with the generated"
                                    + " request body. For example, if the body (join keys and runtime values)"
                                    + " is {\"a\":123,\"b\":456}"
                                    + " and additional-body-json is '{\"c\":789}', the result will be"
                                    + " {\"a\":123,\"b\":456,\"c\":789}.");

    public static final ConfigOption<String> REQUEST_MERGE_BODY_JSON =
            key("http.request.merge-body-json")
                    .stringType()
                    .noDefaultValue()
                    .withDescription(
                            "Additional JSON content to be deep merged into the request body"
                                    + " for PUT and POST operations. The value should be a valid"
                                    + " JSON object string that will be parsed and deep merged with the"
                                    + " generated request body. Nested objects are merged recursively,"
                                    + " with merge JSON values taking precedence at each leaf path."
                                    + " For example, if the body (from join keys) is"
                                    + " {\"a\":123,\"user\":{\"name\":\"John\",\"age\":30}}"
                                    + " and merge-body-json is"
                                    + " '{\"user\":{\"age\":25,\"city\":\"NYC\"},\"c\":789}',"
                                    + " the result will be"
                                    + " {\"a\":123,\"user\":{\"name\":\"John\",\"age\":25,\"city\":\"NYC\"},\"c\":789}."
                                    + " The merge JSON can contain any constant values (nested objects,"
                                    + " arrays, primitives) and must not conflict with join key field names.");

    @Override
    public LookupQueryCreator createLookupQueryCreator(
            final ReadableConfig readableConfig,
            final LookupRow lookupRow,
            final DynamicTableFactory.Context dynamicTableFactoryContext) {
        final String httpMethod = readableConfig.get(LOOKUP_METHOD);
        final String formatIdentifier = readableConfig.get(LOOKUP_REQUEST_FORMAT);
        // get the information from config
        final List<String> requestQueryParamsFields =
                readableConfig.get(REQUEST_QUERY_PARAM_FIELDS);
        Map<String, String> requestUrlMap = readableConfig.get(REQUEST_URL_MAP);
        final List<String> requestBodyFields = readableConfig.get(REQUEST_BODY_FIELDS);

        // Get both additional and merge JSON options
        String additionalRequestJson =
                readableConfig.getOptional(REQUEST_ADDITIONAL_BODY_JSON).orElse(null);
        String mergeRequestJson = readableConfig.getOptional(REQUEST_MERGE_BODY_JSON).orElse(null);

        // Validate that both options are not used together
        if (additionalRequestJson != null && mergeRequestJson != null) {
            throw new IllegalArgumentException(
                    String.format(
                            "Cannot use both %s and %s options together. "
                                    + "Use %s for shallow merge or %s for deep merge.",
                            REQUEST_ADDITIONAL_BODY_JSON.key(),
                            REQUEST_MERGE_BODY_JSON.key(),
                            REQUEST_ADDITIONAL_BODY_JSON.key(),
                            REQUEST_MERGE_BODY_JSON.key()));
        }

        ObjectNode additionalRequestObject =
                getValidatedAdditionalObjectNode(
                        additionalRequestJson, REQUEST_ADDITIONAL_BODY_JSON.key());

        ObjectNode mergeRequestBodyConstants =
                getValidatedAdditionalObjectNode(mergeRequestJson, REQUEST_MERGE_BODY_JSON.key());

        final SerializationFormatFactory jsonFormatFactory =
                FactoryUtil.discoverFactory(
                        Thread.currentThread().getContextClassLoader(),
                        SerializationFormatFactory.class,
                        formatIdentifier);
        QueryFormatAwareConfiguration queryFormatAwareConfiguration =
                new QueryFormatAwareConfiguration(
                        LOOKUP_REQUEST_FORMAT.key() + "." + formatIdentifier,
                        (Configuration) readableConfig);
        EncodingFormat<SerializationSchema<RowData>> encoder =
                jsonFormatFactory.createEncodingFormat(
                        dynamicTableFactoryContext, queryFormatAwareConfiguration);

        final SerializationSchema<RowData> jsonSerializationSchema;
        if (readableConfig.get(ASYNC_POLLING)) {
            jsonSerializationSchema =
                    new SynchronizedSerializationSchema<>(
                            encoder.createRuntimeEncoder(
                                    null, lookupRow.getLookupPhysicalRowDataType()));
        } else {
            jsonSerializationSchema =
                    encoder.createRuntimeEncoder(null, lookupRow.getLookupPhysicalRowDataType());
        }
        // create using config parameter values and specify serialization
        // schema from json format.
        return new GenericJsonAndUrlQueryCreator(
                httpMethod,
                jsonSerializationSchema,
                requestQueryParamsFields,
                requestBodyFields,
                requestUrlMap,
                additionalRequestObject,
                mergeRequestBodyConstants,
                lookupRow);
    }

    @Override
    public String factoryIdentifier() {
        return ID;
    }

    @Override
    public Set<ConfigOption<?>> requiredOptions() {
        return Set.of();
    }

    @Override
    public Set<ConfigOption<?>> optionalOptions() {
        return Set.of(
                REQUEST_QUERY_PARAM_FIELDS,
                REQUEST_BODY_FIELDS,
                REQUEST_URL_MAP,
                REQUEST_ADDITIONAL_BODY_JSON,
                REQUEST_MERGE_BODY_JSON);
    }

    /**
     * Creates and validates the additional JSON node from configuration. This method parses the
     * JSON once during factory creation to avoid re-parsing on every lookup request, improving
     * runtime performance. The additional JSON can contain constant values (nested objects, arrays,
     * primitives) that will be merged with the event content.
     *
     * <p>Note: Validation of the merged result (event + constants) against the schema happens at
     * runtime when the actual merge occurs, not at configuration time.
     *
     * @param additionalRequestJson the additional JSON string to validate and parse
     * @param configKey the configuration key name for error messages
     * @return the parsed ObjectNode, or null if no additional JSON is provided
     * @throws IllegalArgumentException if the JSON is invalid
     */
    private ObjectNode getValidatedAdditionalObjectNode(
            String additionalRequestJson, String configKey) {
        if (additionalRequestJson == null || additionalRequestJson.trim().isEmpty()) {
            return null;
        }

        try {
            // Parse the additional JSON once to avoid re-parsing on every lookup
            JsonNode jsonNode = ObjectMapperAdapter.instance().readTree(additionalRequestJson);

            if (!jsonNode.isObject()) {
                throw new IllegalArgumentException(
                        String.format("The %s must be a valid JSON object.", configKey));
            }

            // Validate no primitive arrays with content for merge JSON
            // (they would be replaced entirely, not merged)
            if (configKey.equals(REQUEST_MERGE_BODY_JSON.key())) {
                validateNoPrimitiveArrays(jsonNode, configKey);
            }

            return (ObjectNode) jsonNode;
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException(
                    String.format("Invalid JSON in %s: %s", configKey, e.getMessage()), e);
        }
    }

    /**
     * Recursively validates that the JSON does not contain arrays of primitives with content.
     * Arrays of primitives would be replaced entirely rather than merged, which could be confusing,
     * so we reject them at configuration time.
     *
     * @param node the JSON node to validate
     * @param configKey the configuration key name for error messages
     * @throws IllegalArgumentException if primitive arrays with content are found
     */
    private void validateNoPrimitiveArrays(JsonNode node, String configKey) {
        if (node.isArray()) {
            // Check if this is a non-empty array
            if (node.size() > 0) {
                JsonNode firstElement = node.get(0);
                // If first element is not an object or array, it's a primitive array
                if (!firstElement.isObject() && !firstElement.isArray()) {
                    throw new IllegalArgumentException(
                            String.format(
                                    "The %s option contains an array of primitives with content. "
                                            + "Primitive arrays cannot be merged and would be replaced entirely. "
                                            + "Please use arrays of objects instead, or remove the array content.",
                                    configKey));
                }
                // Recursively check array elements
                for (JsonNode element : node) {
                    validateNoPrimitiveArrays(element, configKey);
                }
            }
        } else if (node.isObject()) {
            // Recursively check object fields
            node.fields()
                    .forEachRemaining(
                            entry -> validateNoPrimitiveArrays(entry.getValue(), configKey));
        }
    }
}
