package com.cajunsystems.example.order.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {

    @Id
    private String id;

    private String customerId;
    private String shippingAddress;
    private double total;

    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    private Instant createdAt;

    protected Order() {}

    public Order(String id, String customerId, String shippingAddress, double total) {
        this.id = id;
        this.customerId = customerId;
        this.shippingAddress = shippingAddress;
        this.total = total;
        this.status = OrderStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public String getId()              { return id; }
    public String getCustomerId()      { return customerId; }
    public String getShippingAddress() { return shippingAddress; }
    public double getTotal()           { return total; }
    public OrderStatus getStatus()     { return status; }
    public Instant getCreatedAt()      { return createdAt; }

    public void setStatus(OrderStatus status) { this.status = status; }
}
