/*
 * Copyright 2025 soloncode
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

package org.noear.soloncode.sdk.transport;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.soloncode.sdk.exceptions.TransportException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SSL/TLS 测试：JDK HttpsServer + keytool 生成的自签证书（离线可重复）。
 *
 * <p>覆盖：trustStore 信任自签证书 / 未配置时握手失败 / trustAll 跳过校验 /
 * skipHostnameVerify（服务端证书 CN 与访问主机名不一致）/ mTLS（服务端要求客户端证书）。</p>
 */
class HttpTransportSslTest {

	@TempDir
	static Path certDir;

	static final String PASSWORD = "changeit";

	/** 服务端密钥库（CN=localhost） */
	static Path serverKeystore;

	/** 信任库：只含 CN=localhost 服务端自签证书 */
	static Path trustStore;

	/** 主机名不匹配场景的服务端密钥库（CN=other-host） */
	static Path otherHostKeystore;

	/** mTLS 客户端证书库 */
	static Path clientKeystore;

	/** mTLS 服务端信任库（含客户端证书） */
	static Path serverTrustOfClient;

	private HttpsServer server;

	private String baseUrl;

	private final List<String> hits = new CopyOnWriteArrayList<>();

	@BeforeAll
	static void generateCerts() throws Exception {
		serverKeystore = certDir.resolve("server.jks");
		trustStore = certDir.resolve("trust.jks");
		otherHostKeystore = certDir.resolve("other-host.jks");
		clientKeystore = certDir.resolve("client.jks");
		serverTrustOfClient = certDir.resolve("server-trust-client.jks");

		Path localhostCrt = certDir.resolve("localhost.crt");
		Path clientCrt = certDir.resolve("client.crt");
		keytool("-genkeypair -alias server -keyalg RSA -keysize 2048 -validity 1 -dname CN=localhost"
				+ " -keystore " + serverKeystore + " -storepass " + PASSWORD + " -keypass " + PASSWORD);
		keytool("-exportcert -alias server -keystore " + serverKeystore + " -storepass " + PASSWORD
				+ " -rfc -file " + localhostCrt);
		keytool("-importcert -noprompt -alias server -file " + localhostCrt + " -keystore " + trustStore
				+ " -storepass " + PASSWORD);

		keytool("-genkeypair -alias other -keyalg RSA -keysize 2048 -validity 1 -dname CN=other-host"
				+ " -keystore " + otherHostKeystore + " -storepass " + PASSWORD + " -keypass " + PASSWORD);

		keytool("-genkeypair -alias client -keyalg RSA -keysize 2048 -validity 1 -dname CN=client"
				+ " -keystore " + clientKeystore + " -storepass " + PASSWORD + " -keypass " + PASSWORD);
		keytool("-exportcert -alias client -keystore " + clientKeystore + " -storepass " + PASSWORD
				+ " -rfc -file " + clientCrt);
		keytool("-importcert -noprompt -alias client -file " + clientCrt + " -keystore "
				+ serverTrustOfClient + " -storepass " + PASSWORD);
	}

	private static void keytool(String args) throws IOException, InterruptedException {
		String keytool = System.getProperty("java.home") + "/bin/keytool";
		List<String> cmd = new ArrayList<>();
		cmd.add(keytool);
		for (String a : args.trim().split("\\s+")) {
			cmd.add(a);
		}
		Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		try (InputStream in = p.getInputStream()) {
			byte[] buf = new byte[4096];
			int n;
			while ((n = in.read(buf)) != -1) {
				out.write(buf, 0, n);
			}
		}
		int code = p.waitFor();
		assertThat(code).as("keytool %s failed: %s", args, out.toString()).isZero();
	}

	/**
	 * 起 HTTPS 服务。
	 * @param keystore 服务端证书库
	 * @param requireClientAuth mTLS：要求客户端证书（服务端信任库含 client 证书）
	 */
	private void startServer(Path keystore, boolean requireClientAuth) throws Exception {
		server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
		try (InputStream in = Files.newInputStream(keystore)) {
			ks.load(in, PASSWORD.toCharArray());
		}
		KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		kmf.init(ks, PASSWORD.toCharArray());

		TrustManagerFactory tmf = null;
		if (requireClientAuth) {
			KeyStore ts = KeyStore.getInstance(KeyStore.getDefaultType());
			try (InputStream in = Files.newInputStream(serverTrustOfClient)) {
				ts.load(in, PASSWORD.toCharArray());
			}
			tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
			tmf.init(ts);
		}

		SSLContext ctx = SSLContext.getInstance("TLS");
		ctx.init(kmf.getKeyManagers(), tmf == null ? null : tmf.getTrustManagers(), new SecureRandom());
		server.setHttpsConfigurator(new HttpsConfigurator(ctx) {
			@Override
			public void configure(HttpsParameters params) {
				// 规范写法：直接 setNeedClientAuth 在 JDK8 的 HttpsServer 上不生效，
				// 必须经 setSSLParameters 整体下发
				javax.net.ssl.SSLParameters sp = getSSLContext().getDefaultSSLParameters();
				if (requireClientAuth) {
					sp.setNeedClientAuth(true);
				}
				params.setSSLParameters(sp);
			}
		});
		server.createContext("/web/run", exchange -> {
			hits.add(exchange.getRequestURI().toString());
			readBody(exchange);
			exchange.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
			exchange.sendResponseHeaders(200, 0);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write("data: {\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"tls-ok\",\"session_id\":\"ssl1\"}\n\n"
						.getBytes(StandardCharsets.UTF_8));
				os.flush();
			}
			catch (Exception ignored) {
			}
		});
		server.start();
		baseUrl = "https://localhost:" + server.getAddress().getPort() + "/web/run";
	}

	@AfterEach
	void stopServer() {
		if (server != null) {
			server.stop(0);
		}
	}

	private static String readBody(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		try (InputStream in = exchange.getRequestBody()) {
			byte[] chunk = new byte[4096];
			int n;
			while ((n = in.read(chunk)) != -1) {
				buffer.write(chunk, 0, n);
			}
		}
		return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
	}

	private void runToCompletion(HttpTransport transport) throws Exception {
		transport.startSession("hi", CLIOptions.builder().build(), m -> {
		}, null, null);
		transport.waitForCompletion(Duration.ofSeconds(10));
		transport.close();
	}

	@Test
	void selfSignedCertFailsWithoutTrustStore() throws Exception {
		startServer(serverKeystore, false);
		HttpTransport transport = new HttpTransport(baseUrl, null, null, Duration.ofMinutes(10));
		assertThatThrownBy(() -> transport.startSession("hi", CLIOptions.builder().build(), m -> {
		}, null, null)).isInstanceOf(TransportException.class);
		transport.close();
	}

	@Test
	void customTrustStoreAcceptsSelfSignedCert() throws Exception {
		startServer(serverKeystore, false);
		HttpTransport transport = new HttpTransport(baseUrl, null, null,
				HttpOptions.tls().trustStore(trustStore, PASSWORD), Duration.ofMinutes(10));
		runToCompletion(transport);
		assertThat(transport.getSessionId()).isEqualTo("ssl1");
		assertThat(hits).hasSize(1);
	}

	@Test
	void trustAllAcceptsSelfSignedCert() throws Exception {
		startServer(serverKeystore, false);
		HttpTransport transport = new HttpTransport(baseUrl, null, null,
				HttpOptions.tls().trustAll(true), Duration.ofMinutes(10));
		runToCompletion(transport);
		assertThat(transport.getSessionId()).isEqualTo("ssl1");
	}

	@Test
	void hostnameMismatchFailsWithoutSkip() throws Exception {
		// 证书 CN=other-host，URL 主机名 localhost → 证书链可过（trustStore 信任它），
		// 但主机名校验失败
		startServer(otherHostKeystore, false);

		// other-host 的证书不在 trustStore 里 → 先补一张信任（否则会先在链校验挂掉）
		Path otherTrust = certDir.resolve("trust-other.jks");
		Path otherCrt = certDir.resolve("other-host.crt");
		keytool("-exportcert -alias other -keystore " + otherHostKeystore + " -storepass " + PASSWORD
				+ " -rfc -file " + otherCrt);
		keytool("-importcert -noprompt -alias other -file " + otherCrt + " -keystore " + otherTrust
				+ " -storepass " + PASSWORD);

		HttpTransport transport = new HttpTransport(baseUrl, null, null,
				HttpOptions.tls().trustStore(otherTrust, PASSWORD), Duration.ofMinutes(10));
		assertThatThrownBy(() -> transport.startSession("hi", CLIOptions.builder().build(), m -> {
		}, null, null)).isInstanceOf(TransportException.class);
		transport.close();
	}

	@Test
	void skipHostnameVerifyAcceptsMismatchedCert() throws Exception {
		startServer(otherHostKeystore, false);
		// trustAll 同时跳过链校验与主机名校验（配置最少路径）
		HttpTransport transport = new HttpTransport(baseUrl, null, null,
				HttpOptions.tls().trustAll(true).skipHostnameVerify(true), Duration.ofMinutes(10));
		runToCompletion(transport);
		assertThat(transport.getSessionId()).isEqualTo("ssl1");
	}

	@Test
	void mTlsServerRequiresClientCert() throws Exception {
		startServer(serverKeystore, true);
		// 无客户端证书：握手被拒（needClientAuth，Connection reset → TransportException）
		HttpTransport noCert = new HttpTransport(baseUrl, null, null,
				HttpOptions.tls().trustStore(trustStore, PASSWORD), Duration.ofMinutes(10));
		assertThatThrownBy(() -> noCert.startSession("hi", CLIOptions.builder().build(), m -> {
		}, null, null)).isInstanceOf(TransportException.class);
		noCert.close();
		assertThat(hits).isEmpty();

		// 带客户端证书 + 信任服务端：成功
		HttpTransport withCert = new HttpTransport(baseUrl, null, null,
				HttpOptions.tls().trustStore(trustStore, PASSWORD).keyStore(clientKeystore, PASSWORD),
				Duration.ofMinutes(10));
		runToCompletion(withCert);
		assertThat(withCert.getSessionId()).isEqualTo("ssl1");
		assertThat(hits).hasSize(1);
	}

	@Test
	void missingTrustStoreFileFailsAtConstruction() {
		assertThatThrownBy(() -> new HttpTransport("https://localhost:1/web/run", null, null,
				HttpOptions.tls().trustStore("/nonexistent/ca.jks", "x"), Duration.ofMinutes(1)))
						.isInstanceOf(TransportException.class)
						.hasMessageContaining("SSL");
	}

	@Test
	void badTrustStorePasswordFailsAtConstruction() {
		assertThatThrownBy(() -> new HttpTransport("https://localhost:1/web/run", null, null,
				HttpOptions.tls().trustStore(trustStore, "wrong-password"), Duration.ofMinutes(1)))
						.isInstanceOf(TransportException.class)
						.hasMessageContaining("SSL");
	}

}
