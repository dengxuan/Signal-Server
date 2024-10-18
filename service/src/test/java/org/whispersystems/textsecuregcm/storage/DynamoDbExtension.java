/*
 * Copyright 2021-2023 Signal Messenger, LLC
 * SPDX-License-Identifier: AGPL-3.0-only
 */

package org.whispersystems.textsecuregcm.storage;

import com.almworks.sqlite4java.SQLite;
import com.amazonaws.services.dynamodbv2.local.main.ServerRunner;
import com.amazonaws.services.dynamodbv2.local.server.DynamoDBProxyServer;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.LocalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.model.ProvisionedThroughput;
import javax.annotation.Nullable;

public class DynamoDbExtension implements BeforeEachCallback, AfterEachCallback {

  private static final String DEFAULT_LIBRARY_PATH = "target/lib";

  public interface TableSchema {
    String tableName();
    String hashKeyName();
    String rangeKeyName();
    List<AttributeDefinition> attributeDefinitions();
    List<GlobalSecondaryIndex> globalSecondaryIndexes();
    List<LocalSecondaryIndex> localSecondaryIndexes();
  }

  record RawSchema(
    String tableName,
    String hashKeyName,
    String rangeKeyName,
    List<AttributeDefinition> attributeDefinitions,
    List<GlobalSecondaryIndex> globalSecondaryIndexes,
    List<LocalSecondaryIndex> localSecondaryIndexes
  ) implements TableSchema { }

  static final ProvisionedThroughput DEFAULT_PROVISIONED_THROUGHPUT = ProvisionedThroughput.builder()
      .readCapacityUnits(20L)
      .writeCapacityUnits(20L)
      .build();

  private static final AtomicBoolean libraryLoaded = new AtomicBoolean();

  private DynamoDBProxyServer server;
  private int port;

  private final String libraryPath;
  private final List<TableSchema> schemas;
  private DynamoDbClient dynamoDB2;
  private DynamoDbAsyncClient dynamoAsyncDB2;

  public DynamoDbExtension(TableSchema... schemas) {
    this(DEFAULT_LIBRARY_PATH, schemas);
  }

  public DynamoDbExtension(@Nullable final String libraryPath, TableSchema... schemas) {
    this.libraryPath = Optional.ofNullable(libraryPath).orElse(DEFAULT_LIBRARY_PATH);
    this.schemas = List.of(schemas);
  }

  private void loadLibrary() {
    // to avoid noise in the logs from “library already loaded” warnings, we make sure we only set it once
    if (libraryLoaded.get()) {
      return;
    }
    if (libraryLoaded.compareAndSet(false, true)) {
      // if you see a library failed to load error, you need to run mvn test-compile at least once first
      SQLite.setLibraryPath(this.libraryPath);
    }
  }

  @Override
  public void afterEach(ExtensionContext context) {
    stopServer();
  }

  /**
   * For use in integration tests that want to test resiliency/error handling
   */
  public void stopServer() {
    try {
      server.stop();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public void beforeEach(ExtensionContext context) throws Exception {

    startServer();

    initializeClient();

    createTables();
  }

  private void createTables() {
    schemas.stream().forEach(this::createTable);
  }

  private void createTable(TableSchema schema) {
    KeySchemaElement[] keySchemaElements;
    if (schema.rangeKeyName() == null) {
      keySchemaElements = new KeySchemaElement[] {
          KeySchemaElement.builder().attributeName(schema.hashKeyName()).keyType(KeyType.HASH).build(),
      };
    } else {
      keySchemaElements = new KeySchemaElement[] {
          KeySchemaElement.builder().attributeName(schema.hashKeyName()).keyType(KeyType.HASH).build(),
          KeySchemaElement.builder().attributeName(schema.rangeKeyName()).keyType(KeyType.RANGE).build(),
      };
    }

    final CreateTableRequest createTableRequest = CreateTableRequest.builder()
        .tableName(schema.tableName())
        .keySchema(keySchemaElements)
        .attributeDefinitions(schema.attributeDefinitions().isEmpty() ? null : schema.attributeDefinitions())
        .globalSecondaryIndexes(schema.globalSecondaryIndexes().isEmpty() ? null : schema.globalSecondaryIndexes())
        .localSecondaryIndexes(schema.localSecondaryIndexes().isEmpty() ? null : schema.localSecondaryIndexes())
        .provisionedThroughput(DEFAULT_PROVISIONED_THROUGHPUT)
        .build();

    getDynamoDbClient().createTable(createTableRequest);
  }

  private void startServer() throws Exception {
    loadLibrary();
    try (ServerSocket serverSocket = new ServerSocket(0)) {
      port = serverSocket.getLocalPort();
    }
    server = ServerRunner.createServerFromCommandLineArgs(new String[]{"-inMemory", "-port", String.valueOf(port)});
    server.start();
  }

  private void initializeClient() {
    dynamoDB2 = DynamoDbClient.builder()
        .endpointOverride(URI.create("http://localhost:" + port))
        .region(Region.of("local-test-region"))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("accessKey", "secretKey")))
        .overrideConfiguration(builder ->
            builder.apiCallTimeout(Duration.ofSeconds(1))
                .apiCallAttemptTimeout(Duration.ofSeconds(1)))
        .build();
    dynamoAsyncDB2 = DynamoDbAsyncClient.builder()
        .endpointOverride(URI.create("http://localhost:" + port))
        .region(Region.of("local-test-region"))
        .credentialsProvider(StaticCredentialsProvider.create(
            AwsBasicCredentials.create("accessKey", "secretKey")))
        .overrideConfiguration(builder ->
            builder.apiCallTimeout(Duration.ofSeconds(1))
                .apiCallAttemptTimeout(Duration.ofSeconds(1)))
        .build();
  }

  public DynamoDbClient getDynamoDbClient() {
    return dynamoDB2;
  }

  public DynamoDbAsyncClient getDynamoDbAsyncClient() {
    return dynamoAsyncDB2;
  }

}
