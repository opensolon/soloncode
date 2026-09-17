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

import org.junit.jupiter.api.Test;
import org.noear.soloncode.sdk.exceptions.TransportException;

import java.nio.file.Paths;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HttpOptions 纯单元测试：不可变性、wither 语义、校验规则、toString 脱敏。
 */
class HttpOptionsTest {

	@Test
	void emptyOptionsIsDefault() {
		HttpOptions opts = HttpOptions.tls();
		assertThat(opts.isDefault()).isTrue();
		assertThat(opts.toString()).isEqualTo("HttpOptions{default}");
		assertThat(opts.proxyHost()).isNull();
		assertThat(opts.proxyPort()).isEqualTo(-1);
		assertThat(opts.trustStorePath()).isNull();
		assertThat(opts.keyStorePath()).isNull();
		assertThat(opts.trustAll()).isFalse();
		assertThat(opts.skipHostnameVerify()).isFalse();
	}

	@Test
	void proxyFactorySetsHttpTypeByDefault() {
		HttpOptions opts = HttpOptions.proxy("proxy.corp", 3128);
		assertThat(opts.proxyHost()).isEqualTo("proxy.corp");
		assertThat(opts.proxyPort()).isEqualTo(3128);
		assertThat(opts.proxyType()).isEqualTo(HttpOptions.ProxyType.HTTP);
		assertThat(opts.proxyAuthHeader()).isNull();
		assertThat(opts.isDefault()).isFalse();
		assertThat(opts.toString()).isEqualTo("HttpOptions{proxy=proxy.corp:3128(http)}");
	}

	@Test
	void socksProxyTypeIsKept() {
		HttpOptions opts = HttpOptions.proxy("proxy.corp", 1080, HttpOptions.ProxyType.SOCKS);
		assertThat(opts.proxyType()).isEqualTo(HttpOptions.ProxyType.SOCKS);
		assertThat(opts.toString()).contains("socks");
		// null type 视为 HTTP
		assertThat(HttpOptions.proxy("h", 1, null).proxyType()).isEqualTo(HttpOptions.ProxyType.HTTP);
	}

	@Test
	void socksProxyAuthIsRejectedToPreventOriginCredentialLeak() {
		assertThatThrownBy(() -> HttpOptions.proxy("proxy.corp", 1080, HttpOptions.ProxyType.SOCKS)
				.proxyAuth("user", "pass"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("only supported for HTTP proxies");
	}

	@Test
	void proxyAuthBuildsBasicHeader() {
		HttpOptions opts = HttpOptions.proxy("proxy.corp", 3128).proxyAuth("user", "pass");
		String expected = "Basic " + Base64.getEncoder().encodeToString("user:pass".getBytes());
		assertThat(opts.proxyAuthHeader()).isEqualTo(expected);
		// toString 只标记 true，不泄漏凭据
		assertThat(opts.toString()).contains("proxyAuth=true").doesNotContain("pass");
	}

	@Test
	void proxyAuthNullArgsClearsAuth() {
		HttpOptions opts = HttpOptions.proxy("proxy.corp", 3128).proxyAuth("u", "p").proxyAuth(null, null);
		assertThat(opts.proxyAuthHeader()).isNull();
	}

	@Test
	void proxyAuthWithoutProxyIsRejected() {
		HttpOptions opts = HttpOptions.tls();
		assertThatThrownBy(() -> opts.proxyAuth("u", "p"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("requires a proxy");
	}

	@Test
	void proxyPortRangeIsEnforced() {
		assertThatThrownBy(() -> HttpOptions.proxy("h", 0)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> HttpOptions.proxy("h", 65536)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void trustAllAndTrustStoreAreMutuallyExclusive() {
		HttpOptions tls = HttpOptions.tls().trustAll(true);
		assertThatThrownBy(() -> tls.trustStore(Paths.get("/tmp/ca.jks"), "pw"))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("mutually exclusive");
	}

	@Test
	void trustStoreStringOverloadUsesPathsGet() {
		HttpOptions opts = HttpOptions.tls().trustStore("/tmp/ca.jks", "pw");
		assertThat(opts.trustStorePath()).isEqualTo(Paths.get("/tmp/ca.jks"));
		assertThat(new String(opts.trustStorePassword())).isEqualTo("pw");
		assertThat(opts.toString()).contains("trustStore=/tmp/ca.jks");
	}

	@Test
	void keyStoreStringOverloadUsesPathsGet() {
		HttpOptions opts = HttpOptions.tls().keyStore("/tmp/client.p12", "pw");
		assertThat(opts.keyStorePath()).isEqualTo(Paths.get("/tmp/client.p12"));
		assertThat(new String(opts.keyStorePassword())).isEqualTo("pw");
		assertThat(opts.toString()).contains("keyStore=/tmp/client.p12");
	}

	@Test
	void passwordAccessorsReturnDefensiveCopies() {
		HttpOptions opts = HttpOptions.tls().trustStore("/tmp/ca.jks", "pw");
		char[] first = opts.trustStorePassword();
		first[0] = 'X';
		assertThat(new String(opts.trustStorePassword())).isEqualTo("pw");
	}

	@Test
	void nullPathClearsStoreConfig() {
		HttpOptions opts = HttpOptions.tls().trustStore(Paths.get("/tmp/ca.jks"), "pw")
				.trustStore((java.nio.file.Path) null, null);
		assertThat(opts.trustStorePath()).isNull();
		assertThat(opts.trustStorePassword()).isNull();
	}

	@Test
	void withersReturnNewInstances() {
		HttpOptions base = HttpOptions.proxy("proxy.corp", 3128);
		HttpOptions withAuth = base.proxyAuth("u", "p");
		HttpOptions withTrustAll = base.trustAll(true);

		// base 不被修改（不可变）
		assertThat(base.proxyAuthHeader()).isNull();
		assertThat(base.trustAll()).isFalse();
		assertThat(withAuth.proxyAuthHeader()).isNotNull();
		assertThat(withAuth.trustAll()).isFalse();
		assertThat(withTrustAll.trustAll()).isTrue();
		assertThat(withTrustAll.proxyAuthHeader()).isNull();
	}

	@Test
	void flagsRenderedInToString() {
		HttpOptions opts = HttpOptions.tls().trustAll(true).skipHostnameVerify(true);
		assertThat(opts.toString()).contains("trustAll=true").contains("skipHostnameVerify=true");
		assertThat(opts.isDefault()).isFalse();
	}

	@Test
	void equalsAndHashCodeIgnoreSecrets() {
		HttpOptions a = HttpOptions.proxy("p", 1).proxyAuth("u1", "x");
		HttpOptions b = HttpOptions.proxy("p", 1).proxyAuth("u2", "y");
		// 认证不同但结构字段相同 → equals（秘密不参与）
		assertThat(a).isEqualTo(b);
		assertThat(a.hashCode()).isEqualTo(b.hashCode());

		HttpOptions c = HttpOptions.proxy("p", 2);
		assertThat(a).isNotEqualTo(c);
	}

	@Test
	void nullPasswordIsAllowedForJksWithoutPassword() {
		HttpOptions opts = HttpOptions.tls().trustStore("/tmp/ca.jks", null);
		assertThat(opts.trustStorePassword()).isNull();
	}

}
