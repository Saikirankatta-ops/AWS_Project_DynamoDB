package com.cravedash.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.CreateTableRequest;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.Projection;
import software.amazon.awssdk.services.dynamodb.model.ProjectionType;
import software.amazon.awssdk.services.dynamodb.model.ResourceNotFoundException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;
import software.amazon.awssdk.services.dynamodb.model.GlobalSecondaryIndex;
import software.amazon.awssdk.services.dynamodb.waiters.DynamoDbWaiter;

@Component
public class DynamoDbTableInitializer implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(DynamoDbTableInitializer.class);
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;
    private final String endpoint;
    private final boolean autoCreateTables;

    public DynamoDbTableInitializer(
            DynamoDbClient dynamoDbClient,
            @Value("${app.dynamodb.table-name:CraveDashData}") String tableName,
            @Value("${app.dynamodb.endpoint:}") String endpoint,
            @Value("${app.dynamodb.auto-create-tables:false}") boolean autoCreateTables) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
        this.endpoint = endpoint;
        this.autoCreateTables = autoCreateTables;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!autoCreateTables) {
            logger.info("DynamoDB table auto-creation is disabled; expecting table '{}'.", tableName);
            return;
        }
        if (endpoint.isBlank()) {
            throw new IllegalStateException(
                    "DynamoDB table auto-creation requires an explicit local DynamoDB endpoint.");
        }

        try {
            dynamoDbClient.describeTable(DescribeTableRequest.builder().tableName(tableName).build());
            return;
        } catch (ResourceNotFoundException ignored) {
            logger.info("Creating local DynamoDB table '{}'.", tableName);
        }

        dynamoDbClient.createTable(createTableRequest());
        try (DynamoDbWaiter waiter = DynamoDbWaiter.builder().client(dynamoDbClient).build()) {
            waiter.waitUntilTableExists(builder -> builder.tableName(tableName));
        }
        logger.info("Local DynamoDB table '{}' is ready.", tableName);
    }

    private CreateTableRequest createTableRequest() {
        return CreateTableRequest.builder()
                .tableName(tableName)
                .attributeDefinitions(
                        attribute("pk"),
                        attribute("sk"),
                        attribute("gsi1pk"),
                        attribute("gsi1sk"))
                .keySchema(
                        key("pk", KeyType.HASH),
                        key("sk", KeyType.RANGE))
                .globalSecondaryIndexes(GlobalSecondaryIndex.builder()
                        .indexName("gsi1")
                        .keySchema(
                                key("gsi1pk", KeyType.HASH),
                                key("gsi1sk", KeyType.RANGE))
                        .projection(Projection.builder().projectionType(ProjectionType.ALL).build())
                        .build())
                .billingMode(BillingMode.PAY_PER_REQUEST)
                .build();
    }

    private AttributeDefinition attribute(String name) {
        return AttributeDefinition.builder()
                .attributeName(name)
                .attributeType(ScalarAttributeType.S)
                .build();
    }

    private KeySchemaElement key(String name, KeyType type) {
        return KeySchemaElement.builder().attributeName(name).keyType(type).build();
    }
}
