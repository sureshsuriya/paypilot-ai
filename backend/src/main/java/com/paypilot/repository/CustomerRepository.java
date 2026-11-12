package com.paypilot.repository;

import com.paypilot.model.Customer;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CustomerRepository extends JpaRepository<Customer, Long> {

    List<Customer> findByUserIdOrderByNameAsc(Long userId);

    Optional<Customer> findByIdAndUserId(Long id, Long userId);

    Optional<Customer> findByUserIdAndEmailIgnoreCase(Long userId, String email);

    boolean existsByUserIdAndEmailIgnoreCase(Long userId, String email);
}
