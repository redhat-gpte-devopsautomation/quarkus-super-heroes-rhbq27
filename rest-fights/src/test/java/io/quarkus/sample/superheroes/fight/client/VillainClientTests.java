package io.quarkus.sample.superheroes.fight.client;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static javax.ws.rs.core.HttpHeaders.ACCEPT;
import static javax.ws.rs.core.MediaType.APPLICATION_JSON;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.stream.IntStream;

import javax.inject.Inject;
import javax.ws.rs.WebApplicationException;

import org.eclipse.microprofile.faulttolerance.exceptions.CircuitBreakerOpenException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.quarkus.sample.superheroes.fight.HeroesVillainsWiremockServerResource;
import io.quarkus.sample.superheroes.fight.InjectWireMock;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.smallrye.faulttolerance.api.CircuitBreakerMaintenance;
import io.smallrye.faulttolerance.api.CircuitBreakerState;
import io.smallrye.mutiny.helpers.test.UniAssertSubscriber;

/**
 * Tests for the {@link VillainClient}. Uses wiremock to stub responses and verify interactions.
 * @see HeroesVillainsWiremockServerResource
 */
@QuarkusTest
@QuarkusTestResource(HeroesVillainsWiremockServerResource.class)
class VillainClientTests {
	private static final String VILLAIN_API = "/api/villains/random";
	private static final String DEFAULT_VILLAIN_NAME = "Super Chocolatine";
	private static final String DEFAULT_VILLAIN_PICTURE = "super_chocolatine.png";
	private static final String DEFAULT_VILLAIN_POWERS = "does not eat pain au chocolat";
	private static final int DEFAULT_VILLAIN_LEVEL = 42;

	private static final Villain DEFAULT_VILLAIN = new Villain(
		DEFAULT_VILLAIN_NAME,
		DEFAULT_VILLAIN_LEVEL,
		DEFAULT_VILLAIN_PICTURE,
		DEFAULT_VILLAIN_POWERS
	);

	@InjectWireMock
	WireMockServer wireMockServer;

	@Inject
	VillainClient villainClient;

	@Inject
	ObjectMapper objectMapper;

	@Inject
	CircuitBreakerMaintenance circuitBreakerMaintenance;

	@BeforeEach
	public void beforeEach() {
		this.wireMockServer.resetAll();
	}

	@AfterEach
	public void afterEach() {
		// Reset all circuit breaker counts after each test
		this.circuitBreakerMaintenance.resetAll();
	}

	@Test
	public void findsRandom() {
		this.wireMockServer.stubFor(
			get(urlEqualTo(VILLAIN_API))
				.willReturn(okForContentType(APPLICATION_JSON, getDefaultVillainJson()))
		);

		IntStream.range(0, 5)
			.forEach(i -> {
				var villain = this.villainClient.findRandomVillain()
					.subscribe().withSubscriber(UniAssertSubscriber.create())
					.assertSubscribed()
					.awaitItem(Duration.ofSeconds(5))
					.getItem();

				assertThat(villain)
					.isNotNull()
					.extracting(
						Villain::getName,
						Villain::getLevel,
						Villain::getPicture,
						Villain::getPowers
					)
					.containsExactly(
						DEFAULT_VILLAIN_NAME,
						DEFAULT_VILLAIN_LEVEL,
						DEFAULT_VILLAIN_PICTURE,
						DEFAULT_VILLAIN_POWERS
					);
			});

		this.wireMockServer.verify(5,
			getRequestedFor(urlEqualTo(VILLAIN_API))
				.withHeader(ACCEPT, equalTo(APPLICATION_JSON))
		);
	}

	@Test
	public void recoversFrom404() {
		this.wireMockServer.stubFor(
			get(urlEqualTo(VILLAIN_API))
				.willReturn(notFound())
		);

		IntStream.range(0, 5)
			.forEach(i ->
				this.villainClient.findRandomVillain()
					.subscribe().withSubscriber(UniAssertSubscriber.create())
					.assertSubscribed()
					.awaitItem(Duration.ofSeconds(5))
					.assertItem(null)
			);

		this.wireMockServer.verify(5,
			getRequestedFor(urlEqualTo(VILLAIN_API))
				.withHeader(ACCEPT, equalTo(APPLICATION_JSON))
		);
	}

	@Test
	public void doesntRecoverFrom500() {
		this.wireMockServer.stubFor(
			get(urlEqualTo(VILLAIN_API))
				.willReturn(serverError())
		);

		// The way the circuit breaker works is that you have to fire at least requestVolumeThreshold
		// requests at the breaker before it starts to trip
		// This is so it can fill its window

		// Circuit breaker should trip after 2 calls to findRandomVillain
		// 1 Call = 1 actual call + 3 fallbacks = 4 total calls
		assertThat(this.circuitBreakerMaintenance.currentState("findRandomVillain"))
			.isEqualTo(CircuitBreakerState.CLOSED);

		// First 2 calls (and 3 subsequent retries) should just fail with WebApplicationException
		// While making actual calls to the service
		IntStream.rangeClosed(1, 2)
			.forEach(i ->
				this.villainClient.findRandomVillain()
					.subscribe().withSubscriber(UniAssertSubscriber.create())
					.assertSubscribed()
					.awaitFailure(Duration.ofSeconds(5))
					.assertFailedWith(WebApplicationException.class)
			);

		// Next call should trip the breaker
		// The breaker should not make an actual call
		var ex = this.villainClient.findRandomVillain()
			.subscribe().withSubscriber(UniAssertSubscriber.create())
			.assertSubscribed()
			.awaitFailure(Duration.ofSeconds(5))
			.getFailure();

		assertThat(ex)
			.isNotNull()
			.isExactlyInstanceOf(CircuitBreakerOpenException.class)
      .hasMessageContainingAll(String.format("%s#getRandomVillain", VillainClient.class.getName()), "circuit breaker is open");

		// Verify that the breaker is open
		assertThat(this.circuitBreakerMaintenance.currentState("findRandomVillain"))
			.isEqualTo(CircuitBreakerState.OPEN);

		// Verify that the server only saw 8 actual requests
		// (2 "real" requests and 3 retries each)
		this.wireMockServer.verify(8,
			getRequestedFor(urlEqualTo(VILLAIN_API))
				.withHeader(ACCEPT, equalTo(APPLICATION_JSON))
		);
	}

	private String getDefaultVillainJson() {
		try {
			return this.objectMapper.writeValueAsString(DEFAULT_VILLAIN);
		}
		catch (JsonProcessingException ex) {
			throw new RuntimeException(ex);
		}
	}
}
