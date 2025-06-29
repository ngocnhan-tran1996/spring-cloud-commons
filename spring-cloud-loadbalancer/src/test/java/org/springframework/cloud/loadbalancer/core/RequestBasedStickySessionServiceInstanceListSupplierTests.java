/*
 * Copyright 2012-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.loadbalancer.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import org.springframework.cloud.client.DefaultServiceInstance;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.DefaultRequest;
import org.springframework.cloud.client.loadbalancer.DefaultRequestContext;
import org.springframework.cloud.client.loadbalancer.LoadBalancerProperties;
import org.springframework.cloud.client.loadbalancer.Request;
import org.springframework.cloud.client.loadbalancer.RequestData;
import org.springframework.cloud.client.loadbalancer.RequestDataContext;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.cloud.loadbalancer.core.LoadBalancerTestUtils.buildLoadBalancerClientFactory;

/**
 * Tests for {@link RequestBasedStickySessionServiceInstanceListSupplier}.
 *
 * @author Olga Maciaszek-Sharma
 */
class RequestBasedStickySessionServiceInstanceListSupplierTests {

	private static final String SERVICE_ID = "test";

	private final DiscoveryClientServiceInstanceListSupplier delegate = mock(
			DiscoveryClientServiceInstanceListSupplier.class);

	private final LoadBalancerProperties properties = new LoadBalancerProperties();

	private final RequestBasedStickySessionServiceInstanceListSupplier supplier = new RequestBasedStickySessionServiceInstanceListSupplier(
			delegate, buildLoadBalancerClientFactory(SERVICE_ID, properties));

	private final ClientRequest clientRequest = mock(ClientRequest.class);

	private final ServiceInstance first = serviceInstance("test-1");

	private final ServiceInstance second = serviceInstance("test-2");

	private final ServiceInstance third = serviceInstance("test-3");

	@BeforeEach
	void setUp() {
		when(delegate.get(any())).thenReturn(Flux.just(Arrays.asList(first, second, third)));
	}

	@Test
	void shouldReturnInstanceBasedOnCookieFromClientRequest() {
		MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		headers.put(properties.getStickySession().getInstanceIdCookieName(), Collections.singletonList("test-1"));
		when(clientRequest.cookies()).thenReturn(headers);
		Request<RequestDataContext> request = new DefaultRequest<>(
				new RequestDataContext(new RequestData(clientRequest)));

		List<ServiceInstance> serviceInstances = supplier.get(request).blockFirst();

		assertThat(serviceInstances).hasSize(1);
		assertThat(serviceInstances.get(0).getInstanceId()).isEqualTo("test-1");
	}

	@Test
	void shouldReturnAllDelegateInstancesIfInstanceBasedOnCookieFromClientRequestNotFound() {
		MultiValueMap<String, String> headers = new LinkedMultiValueMap<>();
		headers.put(properties.getStickySession().getInstanceIdCookieName(), Collections.singletonList("test-4"));
		when(clientRequest.cookies()).thenReturn(headers);
		Request<RequestDataContext> request = new DefaultRequest<>(
				new RequestDataContext(new RequestData(clientRequest)));

		List<ServiceInstance> serviceInstances = supplier.get(request).blockFirst();

		assertThat(serviceInstances).hasSize(3);
	}

	@Test
	void shouldReturnAllInstancesFromDelegateIfClientRequestHasNoCookie() {
		when(clientRequest.cookies()).thenReturn(new LinkedMultiValueMap<>());
		Request<RequestDataContext> request = new DefaultRequest<>(
				new RequestDataContext(new RequestData(clientRequest)));

		List<ServiceInstance> serviceInstances = supplier.get(request).blockFirst();

		assertThat(serviceInstances).hasSize(3);
	}

	@Test
	void shouldReturnAllInstancesFromDelegateIfNotSupportedRequestContext() {
		Request<DefaultRequestContext> request = new DefaultRequest<>(new DefaultRequestContext(clientRequest));

		List<ServiceInstance> serviceInstances = supplier.get(request).blockFirst();

		assertThat(serviceInstances).hasSize(3);
	}

	private DefaultServiceInstance serviceInstance(String instanceId) {
		return new DefaultServiceInstance(instanceId, SERVICE_ID, "http://test.test", 9080, false);
	}

}
