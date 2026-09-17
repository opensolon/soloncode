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

import java.nio.file.Path;
import java.time.Duration;

/**
 * 通讯通道声明：回答「用哪条通道连到 soloncode 无头执行端」。
 *
 * <p>与「可执行文件在哪」（{@link #stdio(String)} 的参数）是两个不同维度的问题：
 * 通道是必选项（有默认值），可执行文件路径只是 stdio 通道下的可选微调。</p>
 *
 * <h2>用法</h2>
 * <pre>{@code
 * TransportSpec.stdio()                              // 默认：本机常驻 stream，自动发现 CLI
 * TransportSpec.stdio("/usr/local/bin/soloncode")    // 常驻 stream，指定可执行文件
 * TransportSpec.stdioOneShot("/usr/local/bin/soloncode") // 兼容：每轮 run
 * TransportSpec.http("http://127.0.0.1:18080/web/run", token, "my-project")
 * TransportSpec.http(url, token, workspace, HttpOptions.proxy("proxy.corp", 3128))  // 代理/SSL
 * }</pre>
 *
 * <p>默认 stdio spec 创建可复用的常驻传输实例；one-shot stdio 与 HTTP 则每轮新建。</p>
 *
 * <h2>HTTP 通道</h2>
 * <p>把同一组选项投递到服务端的 {@code /web/run} 端点（{@code soloncode web} 启动），
 * 以 SSE 接收与 CLI stream-json 同构的事件流，解析层复用。详见
 * {@code soloncode-cli/docs/run-headless-mode-http.md}。</p>
 *
 * <p>HTTP 通道下 {@code workingDirectory} 无意义（工作目录在服务端，由 workspace 标识
 * 决定），客户端 builder 层用 {@code workspace()} 替代并做互斥校验。</p>
 *
 * @see Transport
 * @see StdioTransport
 * @see HttpTransport
 */
public abstract class TransportSpec {

	TransportSpec() {
	}

	/**
	 * 本机子进程通道，CLI 可执行文件由 {@link org.noear.soloncode.sdk.config.SolonCodeCliDiscovery}
	 * 自动发现。
	 * @return stdio 通道声明
	 */
	public static TransportSpec stdio() {
		return new StdioSpec(null, true);
	}

	/**
	 * 本机子进程通道，使用指定的 CLI 可执行文件。
	 * @param cliPath soloncode 可执行文件路径；传 null 等价于 {@link #stdio()}
	 * @return stdio 通道声明
	 */
	public static TransportSpec stdio(String cliPath) {
		return new StdioSpec(cliPath, true);
	}

	/**
	 * 本机一次性子进程通道。每轮启动 {@code soloncode run}，用于兼容旧 CLI 或需要
	 * 进程级隔离的场景；默认 {@link #stdio()} 使用常驻 {@code soloncode stream}。
	 */
	public static TransportSpec stdioOneShot() {
		return new StdioSpec(null, false);
	}

	/** 使用指定 CLI 的一次性 {@code soloncode run} 通道。 */
	public static TransportSpec stdioOneShot(String cliPath) {
		return new StdioSpec(cliPath, false);
	}

	/**
	 * HTTP 通道：投递到服务端 {@code /web/run} 端点（SSE 接收同构事件流）。
	 *
	 * <p>工作目录在服务端：{@code workingDirectory} 参数被忽略，实际执行环境由
	 * {@code workspace} 标识（{@code /web/workspace/list} 返回的 name/id）决定。</p>
	 *
	 * @param url {@code /web/run} 完整 URL，如 {@code http://127.0.0.1:18080/web/run}
	 * @param token Bearer token（服务端 {@code ~/.soloncode/run.token}）；null 表示不带鉴权头
	 * @param workspace 服务端工作区标识；null 表示服务端默认工作区
	 * @return http 通道声明
	 */
	public static TransportSpec http(String url, String token, String workspace) {
		return new HttpSpec(url, token, workspace, null);
	}

	/**
	 * HTTP 通道（带网络层选项：代理与 SSL/TLS）。
	 *
	 * @param url {@code /web/run} 完整 URL
	 * @param token Bearer token；null 表示不带鉴权头
	 * @param workspace 服务端工作区标识；null 表示服务端默认工作区
	 * @param options 网络层选项；null 等价于默认直连（无代理、JVM 默认 SSL）
	 * @return http 通道声明
	 * @see HttpOptions
	 */
	public static TransportSpec http(String url, String token, String workspace, HttpOptions options) {
		return new HttpSpec(url, token, workspace, options);
	}

	/**
	 * HTTP 通道（无 token、服务端默认工作区）。
	 * @param url {@code /web/run} 完整 URL
	 * @return http 通道声明
	 * @see #http(String, String, String)
	 */
	public static TransportSpec http(String url) {
		return new HttpSpec(url, null, null, null);
	}

	/**
	 * 创建一个传输实例，承载一轮执行。
	 * @param workingDirectory 工作目录
	 * @param timeout 默认超时
	 * @return 新的传输实例
	 */
	public abstract Transport create(Path workingDirectory, Duration timeout);

	/**
	 * 通道描述，用于日志与诊断。
	 * @return 形如 {@code stdio} / {@code stdio(/usr/local/bin/soloncode)} 的描述
	 */
	public abstract String describe();

	/**
	 * 是否为 HTTP 通道（builder 层据此做 workspace/workingDirectory 互斥校验）。
	 */
	public boolean isHttp() {
		return false;
	}

	/** 当前通道是否可在一个连接内承载多轮。 */
	public boolean isPersistent() {
		return false;
	}

	/**
	 * 补全 HTTP 通道的 token 与 workspace（未传 null 的项沿用现值）；非 http 通道抛
	 * {@link IllegalStateException}。builder 层链式调用 {@code http(url)} 后再
	 * {@code authToken(...)} / {@code workspace(...)} 的落地入口。
	 */
	public TransportSpec withHttpCredentials(String token, String workspace) {
		throw new IllegalStateException("not an http transport spec: " + describe());
	}

	/**
	 * 补全 HTTP 通道的网络层选项（代理/SSL）；非 http 通道抛 {@link IllegalStateException}。
	 * builder 层 {@code httpOptions(...)} 的落地入口。传 null 清除网络层配置。
	 */
	public TransportSpec withHttpOptions(HttpOptions options) {
		throw new IllegalStateException("not an http transport spec: " + describe());
	}

	@Override
	public String toString() {
		return describe();
	}

	/**
	 * HTTP 通道：POST /web/run，SSE 接收事件流。
	 */
	static final class HttpSpec extends TransportSpec {

		private final String url;

		private final String token;

		private final String workspace;

		private final HttpOptions options;

		HttpSpec(String url, String token, String workspace, HttpOptions options) {
			if (url == null || url.trim().isEmpty()) {
				throw new IllegalArgumentException("url must not be null or empty");
			}
			this.url = url.trim();
			this.token = (token != null && !token.trim().isEmpty()) ? token.trim() : null;
			this.workspace = (workspace != null && !workspace.trim().isEmpty()) ? workspace.trim() : null;
			this.options = options;
		}

		String url() {
			return url;
		}

		String token() {
			return token;
		}

		String workspace() {
			return workspace;
		}

		@Override
		public boolean isHttp() {
			return true;
		}

		@Override
		public TransportSpec withHttpCredentials(String token, String workspace) {
			return new HttpSpec(url, token != null ? token : this.token, workspace != null ? workspace : this.workspace,
					options);
		}

		@Override
		public TransportSpec withHttpOptions(HttpOptions options) {
			return new HttpSpec(url, token, workspace, options);
		}

		@Override
		public Transport create(Path workingDirectory, Duration timeout) {
			// workingDirectory 在 HTTP 通道下无意义（服务端由 workspace 标识决定），忽略
			return new HttpTransport(url, token, workspace, options, timeout);
		}

		@Override
		public String describe() {
			String extra = options != null && !options.isDefault() ? " " + options : "";
			return "http(" + url + (extra.isEmpty() ? "" : " " + extra) + ")";
		}

	}

	/**
	 * stdio 常驻通道：在本机拉起 {@code soloncode stream} 子进程。
	 */
	static final class StdioSpec extends TransportSpec {

		private final String cliPath;

		private final boolean persistent;

		StdioSpec(String cliPath, boolean persistent) {
			this.cliPath = (cliPath != null && !cliPath.trim().isEmpty()) ? cliPath : null;
			this.persistent = persistent;
		}

		/**
		 * 指定的 CLI 可执行文件路径，未指定时为 null（交由自动发现）。
		 * @return 路径或 null
		 */
		String cliPath() {
			return cliPath;
		}

		@Override
		public boolean isPersistent() {
			return persistent;
		}

		@Override
		public Transport create(Path workingDirectory, Duration timeout) {
			return new StdioTransport(workingDirectory, timeout, cliPath, persistent);
		}

		@Override
		public String describe() {
			String mode = persistent ? "stdio-stream" : "stdio-run";
			return cliPath == null ? mode : mode + "(" + cliPath + ")";
		}

	}

}
