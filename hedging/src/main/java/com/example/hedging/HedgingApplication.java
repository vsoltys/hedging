package com.example.hedging;

import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootApplication
public class HedgingApplication {


	@Bean
	WebClient client(HedgingExchangeFilterFunction e) {
		return WebClient
			.builder()
			.filter(e)
			.build();
	}

	public static void main(String[] args) {
		SpringApplication.run(HedgingApplication.class, args);
	}
}

@RestController
class HedgingRestController {

	private final WebClient client;

	HedgingRestController(WebClient client) {
		this.client = client;
	}

	@GetMapping("/hedge")
	Flux<String> hedge() {
		return this.client
			.get()
			.uri("http://service/hi")
			.retrieve()
			.bodyToFlux(String.class);
	}

}

@Log4j2
@Component
class HedgingExchangeFilterFunction
	implements ExchangeFilterFunction {

	private final DiscoveryClient discoveryClient;
	private final LoadBalancerClient loadBalancerClient;
	private final int attempts, maxAttempts;

	HedgingExchangeFilterFunction(
		DiscoveryClient discoveryClient, LoadBalancerClient loadBalancerClient, int attempts) {
		this.discoveryClient = discoveryClient;
		this.loadBalancerClient = loadBalancerClient;
		this.attempts = attempts;
		this.maxAttempts = attempts * 2;
	}

	@Autowired
	HedgingExchangeFilterFunction(LoadBalancerClient loadBalancerClient,
																															DiscoveryClient discoveryClient) {
		this(discoveryClient, loadBalancerClient, 3);
	}

	@Override
	public Mono<ClientResponse> filter(ClientRequest request,
																																				ExchangeFunction next) {
		URI originalUrl = request.url();
		String serviceId = originalUrl.getHost();
		Assert.notNull(serviceId,
			"request URI does not contain a valid hostname: " + originalUrl);
		List<ServiceInstance> instances = this.discoveryClient
			.getInstances(serviceId);
		Assert.isTrue(instances.size() >= this.attempts, "there must be at least " +
			this.attempts + " instances of service '" + serviceId + "' in the registry.");
		int counter = 0;
		Map<String, Mono<ClientResponse>> ships = new HashMap<>();
		while (ships.size() < this.attempts && (counter++ < this.maxAttempts)) {
			ServiceInstance instance = this.loadBalancerClient.choose(serviceId);
			Assert.notNull(instance,
				"there must be a valid " + ServiceInstance.class.getName() + " instance " +
					"in the registry");
			String asciiString = instance.getUri().toASCIIString();
			if (!ships.containsKey(asciiString)) {
				ships.put(asciiString, this.invoke(instance, originalUrl, request, next));
				log.info("adding " + asciiString);
			}
		}
		return Flux
			.first(ships.values())
			.singleOrEmpty();
	}

	private void debug(String msg) {
		log.info(msg);
	}

	private Mono<ClientResponse> invoke(
		ServiceInstance instance, URI originalUrl,
		ClientRequest request, ExchangeFunction next) {

		URI uri = this.loadBalancerClient.reconstructURI(instance, originalUrl);

		ClientRequest newRequest = ClientRequest
			.create(request.method(), uri)
			.headers(headers -> headers.addAll(request.headers()))
			.cookies(cookies -> cookies.addAll(request.cookies()))
			.attributes(attributes -> attributes.putAll(request.attributes()))
			.body(request.body())
			.build();

		return next
			.exchange(newRequest)
			.doOnNext(cr -> this.debug("launching " + newRequest.url()));
	}
}
