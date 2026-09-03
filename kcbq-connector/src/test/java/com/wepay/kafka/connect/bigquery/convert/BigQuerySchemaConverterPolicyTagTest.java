/*
 * Copyright 2024 Copyright 2022 Aiven Oy and
 * bigquery-connector-for-apache-kafka project contributors
 *
 * This software contains code derived from the Confluent BigQuery
 * Kafka Connector, Copyright Confluent, Inc, which in turn
 * contains code derived from the WePay BigQuery Kafka Connector,
 * Copyright WePay, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package com.wepay.kafka.connect.bigquery.convert;

import static com.wepay.kafka.connect.bigquery.convert.BigQuerySchemaConverter.PII_SCHEMA_PARAMETER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.google.cloud.bigquery.Field;
import com.wepay.kafka.connect.bigquery.SinkPropertiesFactory;
import com.wepay.kafka.connect.bigquery.config.BigQuerySinkConfig;
import java.util.Collections;
import java.util.Map;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.junit.jupiter.api.Test;

/**
 * Covers the Alpian-specific {@code policyTag} support in {@link BigQuerySchemaConverter}: fields
 * whose Kafka Connect schema carries the {@value BigQuerySchemaConverter#PII_SCHEMA_PARAMETER}
 * parameter get the configured BigQuery policy tag attached, enabling column-level access control.
 *
 * <p>This lives in its own file rather than in {@code BigQuerySchemaConverterTest} so that the fork
 * patch stays purely additive and does not conflict when rebasing onto a new upstream release.
 */
public class BigQuerySchemaConverterPolicyTagTest {

  private static final String POLICY_TAG = "projects/p/locations/eu/taxonomies/123/policyTags/456";

  /** A struct with one PII-marked field and one ordinary field. */
  private static Schema schemaWithPiiField() {
    return SchemaBuilder.struct()
        .field("email", SchemaBuilder.string().parameter(PII_SCHEMA_PARAMETER, "true").build())
        .field("country", Schema.STRING_SCHEMA)
        .build();
  }

  private static Field fieldNamed(com.google.cloud.bigquery.Schema schema, String name) {
    return schema.getFields().stream()
        .filter(f -> name.equals(f.getName()))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no such field: " + name));
  }

  @Test
  public void testPiiFieldGetsPolicyTagWhenConfigured() {
    com.google.cloud.bigquery.Schema converted =
        new BigQuerySchemaConverter(false, false, POLICY_TAG).convertSchema(schemaWithPiiField());

    Field email = fieldNamed(converted, "email");
    assertEquals(Collections.singletonList(POLICY_TAG), email.getPolicyTags().getNames());
  }

  @Test
  public void testNonPiiFieldIsNotTagged() {
    com.google.cloud.bigquery.Schema converted =
        new BigQuerySchemaConverter(false, false, POLICY_TAG).convertSchema(schemaWithPiiField());

    assertNull(fieldNamed(converted, "country").getPolicyTags());
  }

  @Test
  public void testNoPolicyTagConfiguredLeavesPiiFieldUntagged() {
    com.google.cloud.bigquery.Schema converted =
        new BigQuerySchemaConverter(false, false, "").convertSchema(schemaWithPiiField());

    assertNull(fieldNamed(converted, "email").getPolicyTags());
  }

  @Test
  public void testNullPolicyTagIsTreatedAsDisabled() {
    com.google.cloud.bigquery.Schema converted =
        new BigQuerySchemaConverter(false, false, null).convertSchema(schemaWithPiiField());

    assertNull(fieldNamed(converted, "email").getPolicyTags());
  }

  /** The two-argument constructor must keep behaving exactly as it did before policyTag existed. */
  @Test
  public void testTwoArgConstructorDisablesPolicyTagging() {
    com.google.cloud.bigquery.Schema converted =
        new BigQuerySchemaConverter(false, false).convertSchema(schemaWithPiiField());

    assertNull(fieldNamed(converted, "email").getPolicyTags());
  }

  /**
   * Covers the wiring, not just the converter: {@code BigQuerySinkConfig.getSchemaConverter()} is
   * the only place the connector builds a converter, so this is the path that decides whether the
   * configured policy tag reaches production at all. Without this, an unconverted construction site
   * would leave the feature silently inert while every other test still passed.
   */
  @Test
  public void testPolicyTagConfigReachesTheConverter() {
    Map<String, String> properties = new SinkPropertiesFactory().getProperties();
    properties.put(BigQuerySinkConfig.POLICY_TAG_CONFIG, POLICY_TAG);

    com.google.cloud.bigquery.Schema converted =
        new BigQuerySinkConfig(properties).getSchemaConverter().convertSchema(schemaWithPiiField());

    assertEquals(
        Collections.singletonList(POLICY_TAG),
        fieldNamed(converted, "email").getPolicyTags().getNames());
  }

  /** Omitting the option must leave PII fields untagged rather than failing. */
  @Test
  public void testPolicyTagConfigDefaultsToDisabled() {
    Map<String, String> properties = new SinkPropertiesFactory().getProperties();

    com.google.cloud.bigquery.Schema converted =
        new BigQuerySinkConfig(properties).getSchemaConverter().convertSchema(schemaWithPiiField());

    assertNull(fieldNamed(converted, "email").getPolicyTags());
  }
}
