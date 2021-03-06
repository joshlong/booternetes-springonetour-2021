package com.example.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.rsocket.RSocketRequester;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;

@Component
class CrmClient {

	private final WebClient webClient;
	private final RSocketRequester rSocketRequester;
	private final String customersHostAndPort;

	CrmClient(@Value("${gateway.customers.hostname-and-port}") String customersHostAndPort,
											WebClient webClient, RSocketRequester rSocketRequester) {
		this.webClient = webClient;
		this.rSocketRequester = rSocketRequester;
		this.customersHostAndPort = customersHostAndPort;
	}

	Flux<CustomerOrders> getCustomerOrders() {
		return applySlaDefaults(
			getCustomers()
				.flatMap(customer ->
					Mono.zip(Mono.just(customer), getOrdersFor(customer.getId()).collectList())
				)
				.map(tuple -> new CustomerOrders(tuple.getT1(), tuple.getT2()))
		);
	}

	Flux<Customer> getCustomers() {
		return applySlaDefaults(this.webClient
			.get()
			.uri(this.customersHostAndPort + "/customers")
			.retrieve()
			.bodyToFlux(Customer.class));
	}

	Flux<Order> getOrdersFor(Integer customerId) {
		return applySlaDefaults(this.rSocketRequester
			.route("orders.{customerId}", customerId)
			.retrieveFlux(Order.class));
	}

	private static <T> Flux<T> applySlaDefaults(Flux<T> tFlux) {
		return tFlux
			.onErrorResume(ex -> Flux.empty())
			.timeout(Duration.ofSeconds(10))
			.retryWhen(Retry.backoff(10, Duration.ofSeconds(1)));
	}
}
