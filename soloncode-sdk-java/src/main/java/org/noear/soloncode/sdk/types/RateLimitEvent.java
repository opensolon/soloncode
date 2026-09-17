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
 *
 * Adapted from claude-agent-sdk-java (Apache License 2.0).
 */

package org.noear.soloncode.sdk.types;

import org.noear.snack4.annotation.ONodeAttr;

import java.util.Objects;

/**
 * Server-sent event carrying rate limit status. Emitted by the SolonCode CLI during
 * streaming sessions. Contains quota information and reset timing that callers can use for
 * proactive back-off.
 */
public final class RateLimitEvent {

	@ONodeAttr(name = "type")
	private final String type;

	@ONodeAttr(name = "rate_limit_info")
	private final RateLimitInfo rateLimitInfo;

	@ONodeAttr(name = "uuid")
	private final String uuid;

	@ONodeAttr(name = "session_id")
	private final String sessionId;

	public RateLimitEvent(@ONodeAttr(name = "type") String type, @ONodeAttr(name = "rate_limit_info") RateLimitInfo rateLimitInfo,
			@ONodeAttr(name = "uuid") String uuid, @ONodeAttr(name = "session_id") String sessionId) {
		this.type = type;
		this.rateLimitInfo = rateLimitInfo;
		this.uuid = uuid;
		this.sessionId = sessionId;
	}

	public String type() {
		return type;
	}

	public RateLimitInfo rateLimitInfo() {
		return rateLimitInfo;
	}

	public String uuid() {
		return uuid;
	}

	public String sessionId() {
		return sessionId;
	}

	/**
	 * Whether the request was allowed through the rate limit.
	 */
	public boolean isAllowed() {
		return rateLimitInfo != null && rateLimitInfo.isAllowed();
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof RateLimitEvent)) {
			return false;
		}
		RateLimitEvent that = (RateLimitEvent) o;
		return Objects.equals(type, that.type) && Objects.equals(rateLimitInfo, that.rateLimitInfo)
				&& Objects.equals(uuid, that.uuid) && Objects.equals(sessionId, that.sessionId);
	}

	@Override
	public int hashCode() {
		return Objects.hash(type, rateLimitInfo, uuid, sessionId);
	}

	@Override
	public String toString() {
		return "RateLimitEvent[type=" + type + ", rateLimitInfo=" + rateLimitInfo + ", uuid=" + uuid + ", sessionId="
				+ sessionId + "]";
	}
}
