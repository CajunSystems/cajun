package com.cajunsystems.example.order.repository;

import com.cajunsystems.example.order.domain.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, String> {}
