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

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * HTTP 通道的网络层选项：代理与 SSL/TLS。
 *
 * <p>仅作用于 {@link HttpTransport}（stdio 通道没有网络层概念）。所有字段可选，
 * 缺省即「直连 + JVM 默认 SSL 上下文」，与未引入本类之前的行为完全一致。</p>
 *
 * <h2>代理</h2>
 * <pre>{@code
 * // 无认证 HTTP 代理
 * HttpOptions.proxy("proxy.corp.example", 3128)
 *
 * // 带认证的 HTTP 代理（Proxy-Authorization: Basic，每连接都带）
 * HttpOptions.proxy("proxy.corp.example", 3128).proxyAuth("user", "pass")
 *
 * // SOCKS5 代理
 * HttpOptions.proxy("proxy.corp.example", 1080, ProxyType.SOCKS)
 * }</pre>
 *
 * <p>HTTP 代理认证用请求头携带（HttpURLConnection 对 CONNECT 隧道下的
 * Proxy-Authorization 处理不可靠，SDK 每个连接显式带上，对正向代理/中间人审计
 * 代理都成立）；SOCKS 认证需通过 {@code java.net.Authenticator.setDefault}
 * 注册（SOCKS 认证不走 HTTP 头），SDK 不越权改 JVM 全局状态。</p>
 *
 * <h2>SSL</h2>
 * <pre>{@code
 * // 自签证书 / 私有 CA：把 CA 证书导入 JKS 信任库（keytool -importcert）
 * HttpOptions.tls().trustStore("/path/ca-trust.jks", "changeit")
 *
 * // 客户端证书（mTLS，服务端要求客户端身份）：PKCS12
 * HttpOptions.tls().keyStore("/path/client.p12", "changeit")
 *
 * // 私有部署、跳过证书校验（危险，仅限内网联调）
 * HttpOptions.tls().trustAll(true)
 *
 * // 容器/PaaS 环境 IP 直连（证书 SAN 与 IP 不一致时跳过主机名校验）
 * HttpOptions.tls().skipHostnameVerify(true)
 * }</pre>
 *
 * <p>trustAll 与 trustStore 互斥（同时设置 build 时抛异常）；跳过校验类选项
 * 会打 WARN 日志提醒风险。</p>
 *
 * <h2>自定义请求头</h2>
 * <pre>{@code
 * // 单个头
 * HttpOptions.create().header("X-Tenant-Id", "t-1024")
 *
 * // 批量（链式追加，同名覆盖且忽略大小写）
 * HttpOptions.create().headers(map).header("X-Trace-Id", traceId)
 * }</pre>
 *
 * <p>自定义头会带到 {@code /web/run} 与 {@code /web/run/interrupt} 两条链路上，
 * 用于网关路由、租户标识、链路追踪等扩展场景。协议关键头
 * （{@code Content-Type}/{@code Accept}/{@code Content-Length}）与由 SDK
 * 自行管理的 {@code Proxy-Authorization} 不允许覆盖，设置即抛异常；
 * {@code Authorization} 允许覆盖 authToken（会打 WARN），便于对接前置网关。</p>
 *
 * <h2>组合</h2>
 * <pre>{@code
 * SolonCodeClient.builder()
 *     .http("https://run.internal.example/web/run")
 *     .authToken(token)
 *     .httpOptions(HttpOptions.proxy("proxy.corp.example", 3128)
 *             .proxyAuth("user", "pass")
 *             .trustStore(caPath, caPass)
 *             .header("X-Tenant-Id", "t-1024"))
 *     .build();
 * }</pre>
 *
 * @see TransportSpec#http(String, String, String, HttpOptions)
 * @see org.noear.soloncode.sdk.SolonCodeClient.Builder#httpOptions(HttpOptions)
 */
public final class HttpOptions {

	/** 代理类型。 */
	public enum ProxyType {

		/** HTTP 正向代理（含 HTTPS 的 CONNECT 隧道）。 */
		HTTP,

		/** SOCKS4/5 代理（java.net SOCKS 实现）。 */
		SOCKS
	}

	// ============================================================
	// Proxy
	// ============================================================

	private final String proxyHost;

	private final int proxyPort;

	private final ProxyType proxyType;

	/** Basic 认证头（仅 HTTP 代理）；形如 {@code Basic xxx} */
	private final String proxyAuthHeader;

	// ============================================================
	// TLS
	// ============================================================

	/** 自定义信任库（自签/私有 CA） */
	private final Path trustStorePath;

	private final char[] trustStorePassword;

	/** 客户端证书（mTLS） */
	private final Path keyStorePath;

	private final char[] keyStorePassword;

	/** 信任所有证书（危险） */
	private final boolean trustAll;

	/** 跳过主机名校验（危险） */
	private final boolean skipHostnameVerify;

	// ============================================================
	// Headers
	// ============================================================

	/**
	 * 协议关键头 / SDK 自管头：不允许用自定义头覆盖，否则 SSE 流式契约或代理认证会被悄悄破坏。
	 */
	private static final Set<String> RESERVED_HEADERS;

	static {
		Set<String> reserved = new LinkedHashSet<>();
		reserved.add("content-type");
		reserved.add("accept");
		reserved.add("content-length");
		reserved.add("proxy-authorization");
		RESERVED_HEADERS = Collections.unmodifiableSet(reserved);
	}

	/** 值需要脱敏的头名（toString 不输出明文）。 */
	private static final Set<String> SENSITIVE_HEADERS;

	static {
		Set<String> sensitive = new LinkedHashSet<>();
		sensitive.add("authorization");
		sensitive.add("cookie");
		sensitive.add("set-cookie");
		SENSITIVE_HEADERS = Collections.unmodifiableSet(sensitive);
	}

	/** 自定义请求头（大小写不敏感，键序稳定）；永不为 null */
	private final Map<String, String> headers;

	private HttpOptions(Builder builder) {
		this.proxyHost = builder.proxyHost;
		this.proxyPort = builder.proxyPort;
		this.proxyType = builder.proxyType;
		this.proxyAuthHeader = builder.proxyAuthHeader;
		this.trustStorePath = builder.trustStorePath;
		this.trustStorePassword = clone(builder.trustStorePassword);
		this.keyStorePath = builder.keyStorePath;
		this.keyStorePassword = clone(builder.keyStorePassword);
		this.trustAll = builder.trustAll;
		this.skipHostnameVerify = builder.skipHostnameVerify;
		this.headers = Collections.unmodifiableMap(newHeaderMap(builder.headers));
		validate();
	}

	/** 大小写不敏感 + 键序稳定的头容器（TreeMap + CASE_INSENSITIVE_ORDER）。 */
	private static Map<String, String> newHeaderMap(Map<String, String> src) {
		Map<String, String> map = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
		if (src != null) {
			map.putAll(src);
		}
		return map;
	}

	private static char[] clone(char[] src) {
		return src == null ? null : src.clone();
	}

	// ============================================================
	// Factories
	// ============================================================

	/**
	 * HTTP 正向代理。
	 * @param host 代理主机
	 * @param port 代理端口（1-65535）
	 * @return 选项
	 */
	public static HttpOptions proxy(String host, int port) {
		return proxy(host, port, ProxyType.HTTP);
	}

	/**
	 * 代理（指定类型：HTTP / SOCKS）。
	 * @param host 代理主机
	 * @param port 代理端口（1-65535）
	 * @param type 代理类型；null 视为 HTTP
	 * @return 选项
	 */
	public static HttpOptions proxy(String host, int port, ProxyType type) {
		return new Builder().proxy(host, port, type).build();
	}

	/**
	 * 中立起点（无任何配置），用于链式追加 TLS / 自定义头。
	 * <pre>{@code HttpOptions.create().header("X-Tenant-Id", "t-1024")}</pre>
	 * @return 选项
	 */
	public static HttpOptions create() {
		return new Builder().build();
	}

	/**
	 * 只有 TLS 配置（不配代理）；等价于 {@link #create()}，保留语义化命名。
	 * @return 选项
	 */
	public static HttpOptions tls() {
		return new Builder().build();
	}

	// ============================================================
	// Withers（返回新实例，不可变）
	// ============================================================

	/**
	 * HTTP 代理的 Basic 认证（返回新实例）。
	 * @param user 代理用户名；传 null 清除认证
	 * @param password 代理密码；传 null 清除认证
	 * @return 新选项实例
	 */
	public HttpOptions proxyAuth(String user, String password) {
		return toBuilder().proxyAuth(user, password).build();
	}

	/**
	 * 指定自定义信任库（返回新实例）。
	 * @param trustStorePath JKS/PKCS12 信任库路径
	 * @param password 信任库密码；JKS 无密码可传 null
	 * @return 新选项实例
	 */
	public HttpOptions trustStore(Path trustStorePath, String password) {
		return toBuilder().trustStore(trustStorePath, password).build();
	}

	/**
	 * 指定自定义信任库（字符串路径重载）。
	 * @param trustStorePath JKS/PKCS12 信任库路径
	 * @param password 信任库密码
	 * @return 新选项实例
	 */
	public HttpOptions trustStore(String trustStorePath, String password) {
		return trustStore(Paths.get(trustStorePath), password);
	}

	/**
	 * 指定客户端证书 / mTLS（返回新实例）。
	 * @param keyStorePath PKCS12/JKS 密钥库路径
	 * @param password 密钥库密码
	 * @return 新选项实例
	 */
	public HttpOptions keyStore(Path keyStorePath, String password) {
		return toBuilder().keyStore(keyStorePath, password).build();
	}

	/**
	 * 指定客户端证书 / mTLS（字符串路径重载）。
	 * @param keyStorePath PKCS12/JKS 密钥库路径
	 * @param password 密钥库密码
	 * @return 新选项实例
	 */
	public HttpOptions keyStore(String keyStorePath, String password) {
		return keyStore(Paths.get(keyStorePath), password);
	}

	/**
	 * 信任所有证书（危险，仅限内网联调；返回新实例）。
	 * @param trustAll true 表示跳过证书链校验
	 * @return 新选项实例
	 */
	public HttpOptions trustAll(boolean trustAll) {
		return toBuilder().trustAll(trustAll).build();
	}

	/**
	 * 跳过主机名校验（危险，IP 直连私有部署时用；返回新实例）。
	 * @param skipHostnameVerify true 表示不校验证书主机名
	 * @return 新选项实例
	 */
	public HttpOptions skipHostnameVerify(boolean skipHostnameVerify) {
		return toBuilder().skipHostnameVerify(skipHostnameVerify).build();
	}

	/**
	 * 追加一个自定义请求头（返回新实例）。同名头忽略大小写覆盖。
	 * @param name 头名；不可为空，不可为协议关键头
	 * @param value 头值；不可为 null
	 * @return 新选项实例
	 */
	public HttpOptions header(String name, String value) {
		return toBuilder().header(name, value).build();
	}

	/**
	 * 批量追加自定义请求头（返回新实例）。同名头忽略大小写覆盖，已有头保留。
	 * @param headers 头集合；null 视为空
	 * @return 新选项实例
	 */
	public HttpOptions headers(Map<String, String> headers) {
		return toBuilder().headers(headers).build();
	}

	// ============================================================
	// Accessors
	// ============================================================

	/** @return 代理主机；未配置为 null */
	public String proxyHost() {
		return proxyHost;
	}

	/** @return 代理端口；未配置为 -1 */
	public int proxyPort() {
		return proxyPort;
	}

	/** @return 代理类型；未配置为 null */
	public ProxyType proxyType() {
		return proxyType;
	}

	/** @return 代理 Basic 认证头（已编码，形如 {@code Basic xxx}）；未配置为 null */
	public String proxyAuthHeader() {
		return proxyAuthHeader;
	}

	/** @return 自定义信任库路径；未配置为 null */
	public Path trustStorePath() {
		return trustStorePath;
	}

	/** @return 自定义信任库密码；未配置为 null */
	public char[] trustStorePassword() {
		return clone(trustStorePassword);
	}

	/** @return 客户端证书路径；未配置为 null */
	public Path keyStorePath() {
		return keyStorePath;
	}

	/** @return 客户端证书密码；未配置为 null */
	public char[] keyStorePassword() {
		return clone(keyStorePassword);
	}

	/** @return 是否信任所有证书 */
	public boolean trustAll() {
		return trustAll;
	}

	/** @return 是否跳过主机名校验 */
	public boolean skipHostnameVerify() {
		return skipHostnameVerify;
	}

	/** @return 自定义请求头（不可修改，大小写不敏感）；未配置为空 Map */
	public Map<String, String> headers() {
		return headers;
	}

	/** @return 是否没有任何代理、TLS 或自定义头配置（全空即默认直连） */
	public boolean isDefault() {
		return proxyHost == null && trustStorePath == null && keyStorePath == null && !trustAll
				&& !skipHostnameVerify && headers.isEmpty();
	}

	// ============================================================
	// equals/hashCode/toString（认证头/密码不进入，防泄漏）
	// ============================================================

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof HttpOptions)) {
			return false;
		}
		HttpOptions that = (HttpOptions) o;
		return proxyPort == that.proxyPort && trustAll == that.trustAll
				&& skipHostnameVerify == that.skipHostnameVerify
				&& Objects.equals(proxyHost, that.proxyHost)
				&& proxyType == that.proxyType
				&& Objects.equals(trustStorePath, that.trustStorePath)
				&& Objects.equals(keyStorePath, that.keyStorePath)
				&& headers.equals(that.headers);
	}

	@Override
	public int hashCode() {
		return Objects.hash(proxyHost, proxyPort, proxyType, trustStorePath, keyStorePath, trustAll,
				skipHostnameVerify, headers);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("HttpOptions{");
		if (proxyHost != null) {
			sb.append("proxy=").append(proxyHost).append(':').append(proxyPort);
			sb.append('(').append(proxyType == ProxyType.SOCKS ? "socks" : "http").append(')');
			if (proxyAuthHeader != null) {
				sb.append(", proxyAuth=true");
			}
		}
		if (trustStorePath != null) {
			sb.append(", trustStore=").append(trustStorePath);
		}
		if (keyStorePath != null) {
			sb.append(", keyStore=").append(keyStorePath);
		}
		if (trustAll) {
			sb.append(", trustAll=true");
		}
		if (skipHostnameVerify) {
			sb.append(", skipHostnameVerify=true");
		}
		if (!headers.isEmpty()) {
			sb.append(", headers={");
			boolean first = true;
			for (Map.Entry<String, String> e : headers.entrySet()) {
				if (!first) {
					sb.append(", ");
				}
				first = false;
				sb.append(e.getKey()).append('=');
				// 敏感头只输出存在性，不输出明文（防日志泄露）
				sb.append(isSensitive(e.getKey()) ? "***" : e.getValue());
			}
			sb.append('}');
		}
		if (sb.length() == "HttpOptions{".length()) {
			sb.append("default");
		}
		return sb.append('}').toString();
	}

	private static boolean isSensitive(String name) {
		return SENSITIVE_HEADERS.contains(name.toLowerCase());
	}

	// ============================================================
	// Validation & Builder
	// ============================================================

	private void validate() {
		if (proxyHost != null && (proxyPort <= 0 || proxyPort > 65535)) {
			throw new IllegalArgumentException("proxy port must be in 1-65535: " + proxyPort);
		}
		if (proxyAuthHeader != null && proxyHost == null) {
			throw new IllegalArgumentException("proxyAuth requires a proxy (call proxy(host, port) first)");
		}
		if (proxyAuthHeader != null && proxyType == ProxyType.SOCKS) {
			throw new IllegalArgumentException(
					"proxyAuth is only supported for HTTP proxies; configure SOCKS authentication with a JVM Authenticator");
		}
		if (trustAll && trustStorePath != null) {
			throw new IllegalArgumentException("trustAll and trustStore are mutually exclusive");
		}
	}

	private Builder toBuilder() {
		Builder b = new Builder();
		b.proxyHost = this.proxyHost;
		b.proxyPort = this.proxyPort;
		b.proxyType = this.proxyType;
		b.proxyAuthHeader = this.proxyAuthHeader;
		b.trustStorePath = this.trustStorePath;
		b.trustStorePassword = clone(this.trustStorePassword);
		b.keyStorePath = this.keyStorePath;
		b.keyStorePassword = clone(this.keyStorePassword);
		b.trustAll = this.trustAll;
		b.skipHostnameVerify = this.skipHostnameVerify;
		b.headers = newHeaderMap(this.headers);
		return b;
	}

	/** 建造者（包内可见，公共入口是静态工厂 + wither）。 */
	static final class Builder {

		private String proxyHost;

		private int proxyPort = -1;

		private ProxyType proxyType = ProxyType.HTTP;

		private String proxyAuthHeader;

		private Path trustStorePath;

		private char[] trustStorePassword;

		private Path keyStorePath;

		private char[] keyStorePassword;

		private boolean trustAll;

		private boolean skipHostnameVerify;

		private Map<String, String> headers = newHeaderMap(null);

		Builder header(String name, String value) {
			if (name == null || name.trim().isEmpty()) {
				throw new IllegalArgumentException("header name must not be empty");
			}
			if (value == null) {
				throw new IllegalArgumentException("header value must not be null: " + name);
			}
			String key = name.trim();
			if (RESERVED_HEADERS.contains(key.toLowerCase())) {
				throw new IllegalArgumentException(
						"header is managed by the SDK and must not be overridden: " + key);
			}
			this.headers.put(key, value);
			return this;
		}

		Builder headers(Map<String, String> headers) {
			if (headers != null) {
				for (Map.Entry<String, String> e : headers.entrySet()) {
					header(e.getKey(), e.getValue());
				}
			}
			return this;
		}

		Builder proxy(String host, int port, ProxyType type) {
			this.proxyHost = host;
			this.proxyPort = port;
			this.proxyType = type == null ? ProxyType.HTTP : type;
			// 切换代理时清除残留认证（避免把认证带到新代理上）
			this.proxyAuthHeader = null;
			return this;
		}

		Builder proxyAuth(String user, String password) {
			if (user == null || password == null) {
				this.proxyAuthHeader = null;
				return this;
			}
			String raw = user + ":" + password;
			this.proxyAuthHeader = "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
			return this;
		}

		Builder trustStore(Path path, String password) {
			if (path == null) {
				this.trustStorePath = null;
				this.trustStorePassword = null;
				return this;
			}
			this.trustStorePath = path;
			this.trustStorePassword = password != null ? password.toCharArray() : null;
			return this;
		}

		Builder keyStore(Path path, String password) {
			if (path == null) {
				this.keyStorePath = null;
				this.keyStorePassword = null;
				return this;
			}
			this.keyStorePath = path;
			this.keyStorePassword = password != null ? password.toCharArray() : null;
			return this;
		}

		Builder trustAll(boolean trustAll) {
			this.trustAll = trustAll;
			return this;
		}

		Builder skipHostnameVerify(boolean skip) {
			this.skipHostnameVerify = skip;
			return this;
		}

		HttpOptions build() {
			return new HttpOptions(this);
		}

	}

}
