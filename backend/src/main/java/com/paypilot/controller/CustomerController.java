package com.paypilot.controller;

import com.paypilot.dto.customer.CreateCustomerRequest;
import com.paypilot.dto.customer.CustomerDetailResponse;
import com.paypilot.dto.customer.CustomerResponse;
import com.paypilot.dto.customer.UpdateCustomerRequest;
import com.paypilot.security.AuthenticatedUser;
import com.paypilot.service.CustomerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * Seller-authenticated REST API controller for customer contact directory and invoice linkage.
 * Strictly isolated per seller JWT principal.
 */
@RestController
@RequestMapping("/api/customers")
@RequiredArgsConstructor
public class CustomerController {

    private final CustomerService customerService;

    @GetMapping
    public ResponseEntity<List<CustomerResponse>> listCustomers(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @RequestParam(name = "search", required = false) String search) {
        return ResponseEntity.ok(customerService.listCustomers(currentUser.getId(), search));
    }

    @GetMapping("/{id}")
    public ResponseEntity<CustomerDetailResponse> getCustomer(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("id") Long customerId) {
        return ResponseEntity.ok(customerService.getCustomer(currentUser.getId(), customerId));
    }

    @PostMapping
    public ResponseEntity<CustomerResponse> createCustomer(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @Valid @RequestBody CreateCustomerRequest request,
            HttpServletRequest httpRequest) {
        CustomerResponse created = customerService.createCustomer(
            currentUser.getId(), request, httpRequest.getRemoteAddr()
        );
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
            .path("/{id}")
            .buildAndExpand(created.id())
            .toUri();
        return ResponseEntity.created(location).body(created);
    }

    @PutMapping("/{id}")
    public ResponseEntity<CustomerResponse> updateCustomer(
            @AuthenticationPrincipal AuthenticatedUser currentUser,
            @PathVariable("id") Long customerId,
            @Valid @RequestBody UpdateCustomerRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.ok(customerService.updateCustomer(
            currentUser.getId(), customerId, request, httpRequest.getRemoteAddr()
        ));
    }
}

