package com.cravedash.service;

import com.cravedash.model.AuthenticatedUser;
import com.cravedash.model.Order;
import com.cravedash.model.OrderStatus;
import com.cravedash.model.OrderStatusHistory;
import com.cravedash.model.UserRole;
import com.cravedash.repository.OrderDynamoRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
public class OrderService {
    private final OrderDynamoRepository orderRepository;

    public OrderService(OrderDynamoRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    public Order createOrder(String restaurant, BigDecimal amount, AuthenticatedUser user) {
        requireCustomer(user);
        LocalDateTime now = LocalDateTime.now();
        Order order = new Order(orderRepository.nextOrderId(), user.username(), restaurant, amount,
                OrderStatus.PLACED, now, now, user.username());
        orderRepository.saveWithHistory(
                order, List.of(new OrderStatusHistory(order.status(), now)), null);
        return order;
    }

    public Order getOrder(String orderId, AuthenticatedUser user) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new OrderNotFoundException(orderId));
        requireOrderAccess(order, user);
        return order;
    }

    public List<Order> getAllOrders(AuthenticatedUser user) {
        if (user.role() == UserRole.OWNER) {
            List<Order> orders = orderRepository.findAll();
            orders.sort((first, second) -> {
                int deliveryOrder = Boolean.compare(
                        first.status() == OrderStatus.DELIVERED,
                        second.status() == OrderStatus.DELIVERED);
                if (deliveryOrder != 0) {
                    return deliveryOrder;
                }
                int arrivalOrder = first.createdAt().compareTo(second.createdAt());
                return arrivalOrder != 0 ? arrivalOrder : first.orderId().compareTo(second.orderId());
            });
            return orders;
        }
        return orderRepository.findAllForCustomer(user.username()).stream()
                .sorted((first, second) -> second.createdAt().compareTo(first.createdAt()))
                .toList();
    }

    public Order updateStatus(String orderId, OrderStatus status, AuthenticatedUser user) {
        requireOwner(user);
        Order current = getOrder(orderId, user);
        if (status.ordinal() != current.status().ordinal() + 1) {
            throw new IllegalArgumentException("Order status can only move to the next step.");
        }
        LocalDateTime now = LocalDateTime.now();
        Order updated = new Order(current.orderId(), current.userName(), current.restaurant(), current.amount(),
                status, current.createdAt(), now, current.customerUsername());
        orderRepository.saveWithHistory(
                updated, List.of(new OrderStatusHistory(status, now)), current.status());
        return updated;
    }

    public List<OrderStatusHistory> getHistory(String orderId, AuthenticatedUser user) {
        getOrder(orderId, user);
        return orderRepository.findHistory(orderId);
    }

    public boolean isDynamoDbConnected() {
        return orderRepository.isConnected();
    }

    public void seedDemoOrders() {
        if (orderRepository.findById("5001").isEmpty()) {
            createFixedOrder("5001", "Tammu", "Pizza House", BigDecimal.valueOf(799), OrderStatus.OUT_FOR_DELIVERY);
        }
        if (orderRepository.findById("5002").isEmpty()) {
            createFixedOrder("5002", "Rahul", "Burger King", BigDecimal.valueOf(499), OrderStatus.PREPARING);
        }
    }

    private void requireCustomer(AuthenticatedUser user) {
        if (user.role() != UserRole.CUSTOMER) {
            throw new AccessDeniedException();
        }
    }

    private void requireOwner(AuthenticatedUser user) {
        if (user.role() != UserRole.OWNER) {
            throw new AccessDeniedException();
        }
    }

    private void requireOrderAccess(Order order, AuthenticatedUser user) {
        if (user.role() != UserRole.OWNER && !user.username().equals(order.customerUsername())) {
            throw new OrderNotFoundException(order.orderId());
        }
    }

    private void createFixedOrder(String orderId, String userName, String restaurant,
                                  BigDecimal amount, OrderStatus status) {
        LocalDateTime now = LocalDateTime.now();
        Order order = new Order(orderId, userName, restaurant, amount, status, now, now, null);
        List<OrderStatusHistory> history = new ArrayList<>();
        history.add(new OrderStatusHistory(OrderStatus.PLACED, now));
        if (status != OrderStatus.PLACED) {
            history.add(new OrderStatusHistory(status, now));
        }
        orderRepository.saveWithHistory(order, history, null);
    }
}
