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

package org.springframework.cloud.client.serviceregistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.BeansException;
import org.springframework.boot.web.server.context.ConfigurableWebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.cloud.client.discovery.ManagementServerPortUtils;
import org.springframework.cloud.client.discovery.event.InstancePreRegisteredEvent;
import org.springframework.cloud.client.discovery.event.InstanceRegisteredEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;

/**
 * Lifecycle methods that may be useful and common to {@link ServiceRegistry}
 * implementations.
 *
 * TODO: Document the lifecycle.
 *
 * @param <R> Registration type passed to the {@link ServiceRegistry}.
 * @author Spencer Gibb
 * @author Zen Huifer
 */
public abstract class AbstractAutoServiceRegistration<R extends Registration>
		implements AutoServiceRegistration, ApplicationContextAware, ApplicationListener<WebServerInitializedEvent> {

	private static final Log logger = LogFactory.getLog(AbstractAutoServiceRegistration.class);

	private final ServiceRegistry<R> serviceRegistry;

	private final boolean autoStartup = true;

	private final AtomicBoolean running = new AtomicBoolean(false);

	private final int order = 0;

	private final AtomicInteger port = new AtomicInteger(0);

	private ApplicationContext context;

	private Environment environment;

	private AutoServiceRegistrationProperties properties;

	private List<RegistrationManagementLifecycle<R>> registrationManagementLifecycles = new ArrayList<>();

	private List<RegistrationLifecycle<R>> registrationLifecycles = new ArrayList<>();

	protected AbstractAutoServiceRegistration(ServiceRegistry<R> serviceRegistry,
			AutoServiceRegistrationProperties properties) {
		this.serviceRegistry = serviceRegistry;
		this.properties = properties;
	}

	protected AbstractAutoServiceRegistration(ServiceRegistry<R> serviceRegistry,
			AutoServiceRegistrationProperties properties,
			List<RegistrationManagementLifecycle<R>> registrationManagementLifecycles,
			List<RegistrationLifecycle<R>> registrationLifecycles) {
		this.serviceRegistry = serviceRegistry;
		this.properties = properties;
		this.registrationManagementLifecycles = registrationManagementLifecycles;
		this.registrationLifecycles = registrationLifecycles;
	}

	protected AbstractAutoServiceRegistration(ServiceRegistry<R> serviceRegistry,
			AutoServiceRegistrationProperties properties, List<RegistrationLifecycle<R>> registrationLifecycles) {
		this.serviceRegistry = serviceRegistry;
		this.properties = properties;
		this.registrationLifecycles = registrationLifecycles;
	}

	public void addRegistrationManagementLifecycle(RegistrationManagementLifecycle<R> registrationManagementLifecycle) {
		this.registrationManagementLifecycles.add(registrationManagementLifecycle);
	}

	public void addRegistrationLifecycle(RegistrationLifecycle<R> registrationLifecycle) {
		this.registrationLifecycles.add(registrationLifecycle);
	}

	protected ApplicationContext getContext() {
		return this.context;
	}

	@Override
	@SuppressWarnings("deprecation")
	public void onApplicationEvent(WebServerInitializedEvent event) {
		ApplicationContext context = event.getApplicationContext();
		if (context instanceof ConfigurableWebServerApplicationContext) {
			if ("management".equals(((ConfigurableWebServerApplicationContext) context).getServerNamespace())) {
				return;
			}
		}
		this.port.compareAndSet(0, event.getWebServer().getPort());
		this.start();
	}

	@Override
	public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
		this.context = applicationContext;
		this.environment = this.context.getEnvironment();
	}

	@Deprecated
	protected Environment getEnvironment() {
		return this.environment;
	}

	@Deprecated
	protected AtomicInteger getPort() {
		return this.port;
	}

	public boolean isAutoStartup() {
		return this.autoStartup;
	}

	public void start() {
		if (!isEnabled()) {
			if (logger.isDebugEnabled()) {
				logger.debug("Discovery Lifecycle disabled. Not starting");
			}
			return;
		}

		// only initialize if nonSecurePort is greater than 0 and it isn't already running
		// because of containerPortInitializer below
		if (!this.running.get()) {
			this.context.publishEvent(new InstancePreRegisteredEvent(this, getRegistration()));
			registrationLifecycles.forEach(
					registrationLifecycle -> registrationLifecycle.postProcessBeforeStartRegister(getRegistration()));
			register();
			this.registrationLifecycles.forEach(
					registrationLifecycle -> registrationLifecycle.postProcessAfterStartRegister(getRegistration()));
			if (shouldRegisterManagement()) {
				this.registrationManagementLifecycles
					.forEach(registrationManagementLifecycle -> registrationManagementLifecycle
						.postProcessBeforeStartRegisterManagement(getManagementRegistration()));
				this.registerManagement();
				registrationManagementLifecycles
					.forEach(registrationManagementLifecycle -> registrationManagementLifecycle
						.postProcessAfterStartRegisterManagement(getManagementRegistration()));

			}
			this.context.publishEvent(new InstanceRegisteredEvent<>(this, getConfiguration()));
			this.running.compareAndSet(false, true);
		}

	}

	/**
	 * @return Whether the management service should be registered with the
	 * {@link ServiceRegistry}.
	 */
	protected boolean shouldRegisterManagement() {
		if (this.properties == null || this.properties.isRegisterManagement()) {
			return getManagementPort() != null && ManagementServerPortUtils.isDifferent(this.context);
		}
		return false;
	}

	/**
	 * @return The object used to configure the registration.
	 */
	@Deprecated
	protected abstract Object getConfiguration();

	/**
	 * @return True, if this is enabled.
	 */
	protected abstract boolean isEnabled();

	/**
	 * @return The serviceId of the Management Service.
	 */
	@Deprecated
	protected String getManagementServiceId() {
		// TODO: configurable management suffix
		return this.context.getId() + ":management";
	}

	/**
	 * @return The service name of the Management Service.
	 */
	@Deprecated
	protected String getManagementServiceName() {
		// TODO: configurable management suffix
		return getAppName() + ":management";
	}

	/**
	 * @return The management server port.
	 */
	@Deprecated
	protected Integer getManagementPort() {
		return ManagementServerPortUtils.getPort(this.context);
	}

	/**
	 * @return The app name (currently the spring.application.name property).
	 */
	@Deprecated
	protected String getAppName() {
		return this.environment.getProperty("spring.application.name", "application");
	}

	@PreDestroy
	public void destroy() {
		stop();
	}

	public boolean isRunning() {
		return this.running.get();
	}

	protected AtomicBoolean getRunning() {
		return this.running;
	}

	public int getOrder() {
		return this.order;
	}

	public int getPhase() {
		return 0;
	}

	protected ServiceRegistry<R> getServiceRegistry() {
		return this.serviceRegistry;
	}

	protected abstract R getRegistration();

	protected abstract R getManagementRegistration();

	/**
	 * Register the local service with the {@link ServiceRegistry}.
	 */
	protected void register() {
		this.serviceRegistry.register(getRegistration());
	}

	/**
	 * Register the local management service with the {@link ServiceRegistry}.
	 */
	protected void registerManagement() {
		R registration = getManagementRegistration();
		if (registration != null) {
			this.serviceRegistry.register(registration);
		}
	}

	/**
	 * De-register the local service with the {@link ServiceRegistry}.
	 */
	protected void deregister() {
		this.serviceRegistry.deregister(getRegistration());
	}

	/**
	 * De-register the local management service with the {@link ServiceRegistry}.
	 */
	protected void deregisterManagement() {
		R registration = getManagementRegistration();
		if (registration != null) {
			this.serviceRegistry.deregister(registration);
		}
	}

	public void stop() {
		if (this.getRunning().compareAndSet(true, false) && isEnabled()) {

			this.registrationLifecycles.forEach(
					registrationLifecycle -> registrationLifecycle.postProcessBeforeStopRegister(getRegistration()));
			deregister();
			this.registrationLifecycles.forEach(
					registrationLifecycle -> registrationLifecycle.postProcessAfterStopRegister(getRegistration()));
			if (shouldRegisterManagement()) {
				this.registrationManagementLifecycles
					.forEach(registrationManagementLifecycle -> registrationManagementLifecycle
						.postProcessBeforeStopRegisterManagement(getManagementRegistration()));
				deregisterManagement();
				this.registrationManagementLifecycles
					.forEach(registrationManagementLifecycle -> registrationManagementLifecycle
						.postProcessAfterStopRegisterManagement(getManagementRegistration()));
			}
			this.serviceRegistry.close();
		}
	}

}
