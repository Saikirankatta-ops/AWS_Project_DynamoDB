package com.cravedash.repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.cravedash.model.Order;
import com.cravedash.model.OrderStatus;
import com.cravedash.model.OrderStatusHistory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DescribeTableRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;
import software.amazon.awssdk.services.dynamodb.model.QueryResponse;
import software.amazon.awssdk.services.dynamodb.model.ReturnValue;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItem;
import software.amazon.awssdk.services.dynamodb.model.TransactWriteItemsRequest;
import software.amazon.awssdk.services.dynamodb.model.UpdateItemRequest;
import software.amazon.awssdk.services.dynamodb.model.Put;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OrderDynamoRepository {
    private static final String ORDER_PARTITION = "ORDER#";
    private static final String HISTORY_PREFIX = "HISTORY#";
    private static final String ORDER_INDEX_PARTITION = "ORDERS";
    private static final String ORDER_SEQUENCE_PARTITION = "SYSTEM#ORDER_SEQUENCE";
    private static final String ORDER_SEQUENCE_SORT = "META";
    private static final DateTimeFormatter HISTORY_TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSSSS");

    private final DynamoDbClient dynamoDbClient;
    private final ObjectMapper objectMapper;
    private final String tableName;

    public OrderDynamoRepository(
            DynamoDbClient dynamoDbClient,
            ObjectMapper objectMapper,
            @Value("${app.dynamodb.table-name:CraveDashData}") String tableName) {
        this.dynamoDbClient = dynamoDbClient;
        this.objectMapper = objectMapper;
        this.tableName = tableName;
    }

    public String nextOrderId() {
        while (true) {
            Map<String, AttributeValue> attributes = dynamoDbClient.updateItem(UpdateItemRequest.builder()
                    .tableName(tableName)
                    .key(key(ORDER_SEQUENCE_PARTITION, ORDER_SEQUENCE_SORT))
                    .updateExpression("SET #value = if_not_exists(#value, :zero) + :one")
                    .expressionAttributeNames(Map.of("#value", "value"))
                    .expressionAttributeValues(Map.of(
                            ":zero", number("0"),
                            ":one", number("1")))
                    .returnValues(ReturnValue.UPDATED_NEW)
                    .build()).attributes();
            String orderId = String.valueOf(5000 + Long.parseLong(attributes.get("value").n()));
            if (findById(orderId).isEmpty()) {
                return orderId;
            }
        }
    }

    public void saveWithHistory(
            Order order,
            List<OrderStatusHistory> history,
            OrderStatus expectedPreviousStatus) {
        Map<String, AttributeValue> orderItem = orderItem(order);
        Put.Builder orderPut = Put.builder().tableName(tableName).item(orderItem);
        Map<String, String> names = new HashMap<>();
        Map<String, AttributeValue> values = new HashMap<>();
        if (expectedPreviousStatus == null) {
            orderPut.conditionExpression("attribute_not_exists(#pk)");
            names.put("#pk", "pk");
        } else {
            orderPut.conditionExpression("#status = :expectedStatus");
            names.put("#status", "status");
            values.put(":expectedStatus", text(expectedPreviousStatus.name()));
        }
        if (!names.isEmpty()) {
            orderPut.expressionAttributeNames(names);
        }
        if (!values.isEmpty()) {
            orderPut.expressionAttributeValues(values);
        }

        List<TransactWriteItem> operations = new ArrayList<>();
        operations.add(TransactWriteItem.builder().put(orderPut.build()).build());
        for (int index = 0; index < history.size(); index++) {
            OrderStatusHistory event = history.get(index);
            operations.add(TransactWriteItem.builder()
                    .put(Put.builder()
                            .tableName(tableName)
                            .item(historyItem(order.orderId(), event, index))
                            .build())
                    .build());
        }
        dynamoDbClient.transactWriteItems(TransactWriteItemsRequest.builder()
                .transactItems(operations)
                .build());
    }

    public Optional<Order> findById(String orderId) {
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(tableName)
                .key(key(ORDER_PARTITION + orderId, "META"))
                .consistentRead(true)
                .build()).item();
        return Optional.ofNullable(item.get("data")).map(data -> readJson(data.s(), Order.class));
    }

    public List<Order> findAll() {
        return queryOrders(null);
    }

    public List<Order> findAllForCustomer(String username) {
        String encodedUsername = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(username.getBytes(StandardCharsets.UTF_8));
        return queryOrders(encodedUsername + "#");
    }

    public List<OrderStatusHistory> findHistory(String orderId) {
        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .keyConditionExpression("#pk = :pk AND begins_with(#sk, :historyPrefix)")
                .expressionAttributeNames(Map.of("#pk", "pk", "#sk", "sk"))
                .expressionAttributeValues(Map.of(
                        ":pk", text(ORDER_PARTITION + orderId),
                        ":historyPrefix", text(HISTORY_PREFIX)))
                .scanIndexForward(true)
                .build();
        List<OrderStatusHistory> history = new ArrayList<>();
        for (Map<String, AttributeValue> item : queryItems(request)) {
            history.add(readJson(item.get("data").s(), OrderStatusHistory.class));
        }
        return history;
    }

    public boolean isConnected() {
        try {
            return "ACTIVE".equals(dynamoDbClient.describeTable(
                    DescribeTableRequest.builder().tableName(tableName).build())
                    .table().tableStatusAsString());
        } catch (SdkException exception) {
            return false;
        }
    }

    private List<Order> queryOrders(String customerPrefix) {
        Map<String, String> names = new HashMap<>();
        names.put("#gsiPk", "gsi1pk");
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":partition", text(ORDER_INDEX_PARTITION));
        String expression = "#gsiPk = :partition";
        if (customerPrefix != null) {
            names.put("#gsiSk", "gsi1sk");
            values.put(":customerPrefix", text(customerPrefix));
            expression += " AND begins_with(#gsiSk, :customerPrefix)";
        }
        QueryRequest request = QueryRequest.builder()
                .tableName(tableName)
                .indexName("gsi1")
                .keyConditionExpression(expression)
                .expressionAttributeNames(names)
                .expressionAttributeValues(values)
                .build();
        List<Order> orders = new ArrayList<>();
        for (Map<String, AttributeValue> item : queryItems(request)) {
            orders.add(readJson(item.get("data").s(), Order.class));
        }
        return orders;
    }

    private List<Map<String, AttributeValue>> queryItems(QueryRequest request) {
        List<Map<String, AttributeValue>> items = new ArrayList<>();
        Map<String, AttributeValue> lastKey = null;
        do {
            QueryRequest.Builder pageRequest = request.toBuilder();
            if (lastKey != null && !lastKey.isEmpty()) {
                pageRequest.exclusiveStartKey(lastKey);
            }
            QueryResponse page = dynamoDbClient.query(pageRequest.build());
            items.addAll(page.items());
            lastKey = page.lastEvaluatedKey();
        } while (lastKey != null && !lastKey.isEmpty());
        return items;
    }

    private Map<String, AttributeValue> orderItem(Order order) {
        Map<String, AttributeValue> item = new HashMap<>(key(ORDER_PARTITION + order.orderId(), "META"));
        item.put("type", text("ORDER"));
        item.put("data", text(writeJson(order)));
        item.put("status", text(order.status().name()));
        item.put("gsi1pk", text(ORDER_INDEX_PARTITION));
        String customer = order.customerUsername() == null
                ? "~"
                : Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(order.customerUsername().getBytes(StandardCharsets.UTF_8));
        item.put("gsi1sk", text(customer + "#" + order.orderId()));
        return item;
    }

    private Map<String, AttributeValue> historyItem(String orderId, OrderStatusHistory event, int sequence) {
        String timestamp = event.timestamp().format(HISTORY_TIMESTAMP);
        String sortKey = HISTORY_PREFIX + timestamp + "#" + String.format(Locale.ROOT, "%08d", sequence)
                + "#" + UUID.randomUUID();
        Map<String, AttributeValue> item = new HashMap<>(key(ORDER_PARTITION + orderId, sortKey));
        item.put("type", text("HISTORY"));
        item.put("data", text(writeJson(event)));
        return item;
    }

    private Map<String, AttributeValue> key(String partition, String sort) {
        return Map.of("pk", text(partition), "sk", text(sort));
    }

    private AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private AttributeValue number(String value) {
        return AttributeValue.builder().n(value).build();
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to serialize DynamoDB data.", exception);
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Unable to deserialize DynamoDB data.", exception);
        }
    }
}
