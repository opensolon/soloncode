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

package org.noear.soloncode.sdk.types.control;

import org.noear.snack4.ONode;
import org.noear.snack4.Options;
import org.noear.snack4.annotation.ONodeAttr;
import org.noear.snack4.codec.CodecException;
import org.noear.snack4.codec.ObjectCreator;

import java.util.Objects;

/**
 * Control response wrapper for bidirectional communication with SolonCode CLI. The SDK sends
 * these responses back to the CLI.
 */
public final class ControlResponse {

	@ONodeAttr(name = "type")
	private final String type;

	@ONodeAttr(name = "response")
	private final ResponsePayload response;

	public ControlResponse(@ONodeAttr(name = "type") String type, @ONodeAttr(name = "response") ResponsePayload response) {
		this.type = type;
		this.response = response;
	}

	public String type() {
		return type;
	}

	public ResponsePayload response() {
		return response;
	}

	public static final String TYPE = "control_response";

	/**
	 * Create a success response.
	 */
	public static ControlResponse success(String requestId, Object responseData) {
		return new ControlResponse(TYPE, new SuccessPayload("success", requestId, responseData));
	}

	/**
	 * Create an error response.
	 */
	public static ControlResponse error(String requestId, String errorMessage) {
		return new ControlResponse(TYPE, new ErrorPayload("error", requestId, errorMessage));
	}

	/**
	 * Interface for response payload types.
	 *
	 * <p>
	 * 多态入向由 {@link PayloadCreator} 按判别字段 {@code subtype} 分派（替代 Jackson 的
	 * {@code @JsonTypeInfo/@JsonSubTypes}）；出向由子类自带的 {@code subtype} 字段落地。
	 * </p>
	 */
	@ONodeAttr(creator = PayloadCreator.class)
	public interface ResponsePayload {

		String subtype();

		String requestId();

	}

	/**
	 * {@link ResponsePayload} 的多态解码分派器。判别字段：{@code subtype}。
	 */
	public static final class PayloadCreator implements ObjectCreator<ResponsePayload> {

		@Override
		public ResponsePayload create(Options opts, ONode node, Class<?> clazz) {
			if (node == null || !node.isObject()) {
				return null;
			}

			String subtype = node.get("subtype").getString();
			if (subtype == null) {
				throw new CodecException("Missing 'subtype' in control response payload");
			}

			switch (subtype) {
				case "success":
					return node.toBean(SuccessPayload.class);
				case "error":
					return node.toBean(ErrorPayload.class);
				default:
					throw new CodecException("Unknown control response subtype: " + subtype);
			}
		}

	}

	/**
	 * Success response payload.
	 */
	public static final class SuccessPayload implements ResponsePayload {

		@ONodeAttr(name = "subtype")
		private final String subtype;

		@ONodeAttr(name = "request_id")
		private final String requestId;

		@ONodeAttr(name = "response")
		private final Object response;

		public SuccessPayload(@ONodeAttr(name = "subtype") String subtype, @ONodeAttr(name = "request_id") String requestId,
				@ONodeAttr(name = "response") Object response) {
			this.subtype = subtype;
			this.requestId = requestId;
			this.response = response;
		}

		@Override
		public String subtype() {
			return subtype;
		}

		@Override
		public String requestId() {
			return requestId;
		}

		public Object response() {
			return response;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof SuccessPayload)) {
				return false;
			}
			SuccessPayload that = (SuccessPayload) o;
			return Objects.equals(subtype, that.subtype) && Objects.equals(requestId, that.requestId)
					&& Objects.equals(response, that.response);
		}

		@Override
		public int hashCode() {
			return Objects.hash(subtype, requestId, response);
		}

		@Override
		public String toString() {
			return "SuccessPayload[subtype=" + subtype + ", requestId=" + requestId + ", response=" + response + "]";
		}
	}

	/**
	 * Error response payload.
	 */
	public static final class ErrorPayload implements ResponsePayload {

		@ONodeAttr(name = "subtype")
		private final String subtype;

		@ONodeAttr(name = "request_id")
		private final String requestId;

		@ONodeAttr(name = "error")
		private final String error;

		public ErrorPayload(@ONodeAttr(name = "subtype") String subtype, @ONodeAttr(name = "request_id") String requestId,
				@ONodeAttr(name = "error") String error) {
			this.subtype = subtype;
			this.requestId = requestId;
			this.error = error;
		}

		@Override
		public String subtype() {
			return subtype;
		}

		@Override
		public String requestId() {
			return requestId;
		}

		public String error() {
			return error;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (!(o instanceof ErrorPayload)) {
				return false;
			}
			ErrorPayload that = (ErrorPayload) o;
			return Objects.equals(subtype, that.subtype) && Objects.equals(requestId, that.requestId)
					&& Objects.equals(error, that.error);
		}

		@Override
		public int hashCode() {
			return Objects.hash(subtype, requestId, error);
		}

		@Override
		public String toString() {
			return "ErrorPayload[subtype=" + subtype + ", requestId=" + requestId + ", error=" + error + "]";
		}
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof ControlResponse)) {
			return false;
		}
		ControlResponse that = (ControlResponse) o;
		return Objects.equals(type, that.type) && Objects.equals(response, that.response);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, response);
	}

	@Override
	public String toString() {
		return "ControlResponse[type=" + type + ", response=" + response + "]";
	}
}
