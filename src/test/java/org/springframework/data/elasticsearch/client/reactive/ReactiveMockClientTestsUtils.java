/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.elasticsearch.client.reactive;

import static org.mockito.Mockito.*;

import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.mockito.Mockito;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.data.elasticsearch.client.ElasticsearchHost;
import org.springframework.data.elasticsearch.client.reactive.ReactiveMockClientTestsUtils.MockWebClientProvider.Send;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClient.RequestBodyUriSpec;
import org.springframework.web.reactive.function.client.WebClient.RequestHeadersUriSpec;

/**
 * @author Christoph Strobl
 */
public class ReactiveMockClientTestsUtils {

	public static MockDelegatingElasticsearchHostProvider<SingleNodeHostProvider> single(String host) {
		return provider(host);
	}

	public static MockDelegatingElasticsearchHostProvider<MultiNodeHostProvider> multi(String... hosts) {
		return provider(hosts);
	}

	public static <T extends HostProvider> MockDelegatingElasticsearchHostProvider<T> provider(String... hosts) {

		ErrorCollector errorCollector = new ErrorCollector();
		MockWebClientProvider clientProvider = new MockWebClientProvider(errorCollector);
		HostProvider delegate = null;

		if (hosts.length == 1) {

			delegate = new SingleNodeHostProvider(clientProvider, hosts[0]) {};
		} else {

			delegate = new MultiNodeHostProvider(clientProvider, hosts) {};
		}

		return new MockDelegatingElasticsearchHostProvider(HttpHeaders.EMPTY, clientProvider, errorCollector, delegate,
				null);

	}

	public static class ErrorCollector implements Consumer<Throwable> {

		List<Throwable> errors = new CopyOnWriteArrayList<>();

		@Override
		public void accept(Throwable throwable) {
			errors.add(throwable);
		}

		List<Throwable> captured() {
			return Collections.unmodifiableList(errors);
		}
	}

	public static class MockDelegatingElasticsearchHostProvider<T extends HostProvider> implements HostProvider {

		private final T delegate;
		private final MockWebClientProvider clientProvider;
		private final ErrorCollector errorCollector;
		private @Nullable String activeDefaultHost;

		public MockDelegatingElasticsearchHostProvider(HttpHeaders httpHeaders, MockWebClientProvider clientProvider,
				ErrorCollector errorCollector, T delegate, String activeDefaultHost) {

			this.errorCollector = errorCollector;
			this.clientProvider = clientProvider;
			this.delegate = delegate;
			this.activeDefaultHost = activeDefaultHost;
		}

		public Mono<String> lookupActiveHost() {
			return delegate.lookupActiveHost();
		}

		public Mono<String> lookupActiveHost(VerificationMode verificationMode) {

			if (StringUtils.hasText(activeDefaultHost)) {
				return Mono.just(activeDefaultHost);
			}

			return delegate.lookupActiveHost(verificationMode);
		}

		public Mono<WebClient> getActive() {
			return delegate.getActive();
		}

		public Mono<WebClient> getActive(VerificationMode verificationMode) {
			return delegate.getActive(verificationMode);
		}

		public WebClient createWebClient(String baseUrl) {
			return delegate.createWebClient(baseUrl);
		}

		@Override
		public Mono<ClusterInformation> clusterInfo() {

			if (StringUtils.hasText(activeDefaultHost)) {
				return Mono.just(new ClusterInformation(Collections.singleton(ElasticsearchHost.online(activeDefaultHost))));
			}

			return delegate.clusterInfo();
		}

		public Send when(String host) {
			return clientProvider.when(host);
		}

		public WebClient client(String host) {
			return clientProvider.when(host).client();
		}

		public List<Throwable> errors() {
			return errorCollector.captured();
		}

		public T getDelegate() {
			return delegate;
		}

		public MockDelegatingElasticsearchHostProvider<T> withActiveDefaultHost(String host) {
			return new MockDelegatingElasticsearchHostProvider(HttpHeaders.EMPTY, clientProvider, errorCollector, delegate,
					host);
		}
	}

	public static class MockWebClientProvider implements WebClientProvider {

		private final Object lock = new Object();
		private final Consumer<Throwable> errorListener;

		private Map<String, WebClient> clientMap;
		private Map<String, RequestHeadersUriSpec> headersUriSpecMap;
		private Map<String, RequestBodyUriSpec> bodyUriSpecMap;
		private Map<String, ClientResponse> responseMap;

		public MockWebClientProvider(Consumer<Throwable> errorListener) {

			this.errorListener = errorListener;
			this.clientMap = new LinkedHashMap<>();
			this.headersUriSpecMap = new LinkedHashMap<>();
			this.bodyUriSpecMap = new LinkedHashMap<>();
			this.responseMap = new LinkedHashMap<>();
		}

		public WebClient get(String host) {

			synchronized (lock) {

				return clientMap.computeIfAbsent(host, key -> {

					WebClient webClient = mock(WebClient.class);

					RequestHeadersUriSpec headersUriSpec = mock(RequestHeadersUriSpec.class);
					Mockito.when(webClient.get()).thenReturn(headersUriSpec);
					Mockito.when(webClient.head()).thenReturn(headersUriSpec);

					Mockito.when(headersUriSpec.uri(any(String.class))).thenReturn(headersUriSpec);
					Mockito.when(headersUriSpec.uri(any(), any(Map.class))).thenReturn(headersUriSpec);
					Mockito.when(headersUriSpec.headers(any(Consumer.class))).thenReturn(headersUriSpec);

					RequestBodyUriSpec bodyUriSpec = mock(RequestBodyUriSpec.class);
					Mockito.when(webClient.method(any())).thenReturn(bodyUriSpec);
					Mockito.when(bodyUriSpec.body(any())).thenReturn(headersUriSpec);
					Mockito.when(bodyUriSpec.uri(any(), any(Map.class))).thenReturn(bodyUriSpec);
					Mockito.when(bodyUriSpec.headers(any(Consumer.class))).thenReturn(bodyUriSpec);

					ClientResponse response = mock(ClientResponse.class);
					Mockito.when(headersUriSpec.exchange()).thenReturn(Mono.just(response));
					Mockito.when(bodyUriSpec.exchange()).thenReturn(Mono.just(response));
					Mockito.when(response.statusCode()).thenReturn(HttpStatus.ACCEPTED);

					headersUriSpecMap.putIfAbsent(host, headersUriSpec);
					bodyUriSpecMap.putIfAbsent(host, bodyUriSpec);
					responseMap.putIfAbsent(host, response);

					return webClient;
				});
			}
		}

		@Override
		public HttpHeaders getDefaultHeaders() {
			return HttpHeaders.EMPTY;
		}

		@Override
		public WebClientProvider withDefaultHeaders(HttpHeaders headers) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Consumer<Throwable> getErrorListener() {
			return errorListener;
		}

		@Override
		public WebClientProvider withErrorListener(Consumer<Throwable> errorListener) {
			throw new UnsupportedOperationException();
		}

		public Send when(String host) {
			return new CallbackImpl(get(host), headersUriSpecMap.get(host), bodyUriSpecMap.get(host), responseMap.get(host));
		}

		public interface Client {
			WebClient client();
		}

		public interface Send extends Receive, Client {

			Receive get(Consumer<RequestHeadersUriSpec> headerSpec);

			Receive exchange(Consumer<RequestBodyUriSpec> bodySpec);

			default Receive receiveJsonFromFile(String file) {

				return receive(Receive::json) //
						.body(Receive.fromPath(file));
			}

			default Receive receiveInfo() {

				return receiveJsonFromFile("info") //
						.receive(Receive::ok);

			}

			default Receive receiveIndexCreated() {

				return receiveJsonFromFile("index-ok-created") //
						.receive(Receive::ok);
			}

			default Receive receiveIndexUpdated() {

				return receiveJsonFromFile("index-ok-updated") //
						.receive(Receive::ok);
			}

			default Receive receiveSearchOk() {

				return receiveJsonFromFile("search-ok-no-hits") //
						.receive(Receive::ok);
			}

			default Receive receiveGetByIdNotFound() {

				return receiveJsonFromFile("get-by-id-no-hit") //
						.receive(response -> {
							Mockito.when(response.statusCode()).thenReturn(HttpStatus.ACCEPTED, HttpStatus.NOT_FOUND);
						});
			}

			default Receive receiveGetById() {

				return receiveJsonFromFile("get-by-id-ok") //
						.receive(Receive::ok);
			}

			default Receive receiveUpdateOk() {

				return receiveJsonFromFile("update-ok-updated") //
						.receive(Receive::ok);
			}

			default Receive receiveDeleteOk() {

				return receiveJsonFromFile("update-ok-deleted") //
						.receive(Receive::ok);
			}

			default Receive updateFail() {

				return receiveJsonFromFile("update-error-not-found") //
						.receive(response -> {
							Mockito.when(response.statusCode()).thenReturn(HttpStatus.ACCEPTED, HttpStatus.NOT_FOUND);
						});
			}

		}

		public interface Receive {

			Receive receive(Consumer<ClientResponse> response);

			default Receive body(String json) {
				return body(() -> json.getBytes(StandardCharsets.UTF_8));
			}

			default Receive body(Supplier<byte[]> json) {
				return body(json.get());
			}

			default Receive body(Resource resource) {

				return body(() -> {
					try {
						return StreamUtils.copyToByteArray(resource.getInputStream());
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				});
			}

			default Receive body(byte[] bytes) {
				return receive(response -> Mockito.when(response.body(any())).thenReturn(Mono.just(bytes)));
			}

			static void ok(ClientResponse response) {
				Mockito.when(response.statusCode()).thenReturn(HttpStatus.ACCEPTED);
			}

			static void error(ClientResponse response) {
				Mockito.when(response.statusCode()).thenReturn(HttpStatus.INTERNAL_SERVER_ERROR);
			}

			static void notFound(ClientResponse response) {
				Mockito.when(response.statusCode()).thenReturn(HttpStatus.NOT_FOUND);
			}

			static void json(ClientResponse response) {

				ClientResponse.Headers headers = Mockito.mock(ClientResponse.Headers.class);
				Mockito.when(headers.contentType()).thenReturn(Optional.of(MediaType.APPLICATION_JSON));

				Mockito.when(response.headers()).thenReturn(headers);
			}

			static Resource fromPath(String filename) {
				return new ClassPathResource("/org/springframework/data/elasticsearch/client/" + filename + ".json");
			}
		}

		class CallbackImpl implements Send, Receive {

			WebClient client;
			RequestHeadersUriSpec headersUriSpec;
			RequestBodyUriSpec bodyUriSpec;
			ClientResponse responseDelegate;

			public CallbackImpl(WebClient client, RequestHeadersUriSpec headersUriSpec, RequestBodyUriSpec bodyUriSpec,
					ClientResponse responseDelegate) {

				this.client = client;
				this.headersUriSpec = headersUriSpec;
				this.bodyUriSpec = bodyUriSpec;
				this.responseDelegate = responseDelegate;
			}

			@Override
			public Receive get(Consumer<RequestHeadersUriSpec> uriSpec) {

				uriSpec.accept(headersUriSpec);
				return this;
			}

			@Override
			public Receive exchange(Consumer<RequestBodyUriSpec> bodySpec) {

				bodySpec.accept(this.bodyUriSpec);
				return this;
			}

			@Override
			public Receive receive(Consumer<ClientResponse> response) {

				response.accept(responseDelegate);
				return this;
			}

			@Override
			public WebClient client() {
				return client;
			}

		}
	}
}
