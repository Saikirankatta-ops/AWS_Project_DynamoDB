package com.cravedash.repository;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Repository
public class AuthDynamoRepository {
    private final DynamoDbClient dynamoDbClient;
    private final String tableName;

    public AuthDynamoRepository(
            DynamoDbClient dynamoDbClient,
            @Value("${app.dynamodb.table-name:CraveDashData}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.tableName = tableName;
    }

    public boolean createAccount(String username, String role, String passwordHash) {
        Map<String, AttributeValue> item = new HashMap<>(key("USER#" + username));
        item.put("type", text("USER"));
        item.put("username", text(username));
        item.put("role", text(role));
        item.put("passwordHash", text(passwordHash));
        try {
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(tableName)
                    .item(item)
                    .conditionExpression("attribute_not_exists(pk)")
                    .build());
            return true;
        } catch (ConditionalCheckFailedException exception) {
            return false;
        }
    }

    public Optional<AccountRecord> findAccount(String username) {
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key("USER#" + username))
                .consistentRead(true)
                .build()).item();
        if (item.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new AccountRecord(item.get("role").s(), item.get("passwordHash").s()));
    }

    public void createSession(String token, String username, Instant expiresAt) {
        Map<String, AttributeValue> item = new HashMap<>(key("SESSION#" + token));
        item.put("type", text("SESSION"));
        item.put("username", text(username));
        item.put("expiresAt", AttributeValue.builder().n(Long.toString(expiresAt.getEpochSecond())).build());
        dynamoDbClient.putItem(PutItemRequest.builder().tableName(tableName).item(item).build());
    }

    public Optional<String> findSessionUsername(String token) {
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key("SESSION#" + token))
                .consistentRead(true)
                .build()).item();
        if (item.isEmpty()) {
            return Optional.empty();
        }
        if (Long.parseLong(item.get("expiresAt").n()) <= Instant.now().getEpochSecond()) {
            deleteSession(token);
            return Optional.empty();
        }
        return Optional.of(item.get("username").s());
    }

    public void deleteSession(String token) {
        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(tableName)
                .key(key("SESSION#" + token))
                .build());
    }

    private Map<String, AttributeValue> key(String partition) {
        return Map.of(
                "pk", text(partition),
                "sk", text("META"));
    }

    private AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }

    public record AccountRecord(String role, String passwordHash) {
    }
}
