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
import org.noear.soloncode.sdk.util.PrimitiveSafeCreator;

import java.util.Objects;

/**
 * Rate limit information from a {@code rate_limit_event} server-sent event. Contains the
 * current rate limit status, quota type, and reset timing.
 */
@ONodeAttr(creator = PrimitiveSafeCreator.class)
public final class RateLimitInfo {

	@ONodeAttr(name = "status")
	private final String status;

	@ONodeAttr(name = "resetsAt")
	private final long resetsAt;

	@ONodeAttr(name = "rateLimitType")
	private final String rateLimitType;

	@ONodeAttr(name = "overageStatus")
	private final String overageStatus;

	@ONodeAttr(name = "overageDisabledReason")
	private final String overageDisabledReason;

	@ONodeAttr(name = "isUsingOverage")
	private final boolean isUsingOverage;

	public RateLimitInfo(@ONodeAttr(name = "status") String status, @ONodeAttr(name = "resetsAt") long resetsAt,
			@ONodeAttr(name = "rateLimitType") String rateLimitType, @ONodeAttr(name = "overageStatus") String overageStatus,
			@ONodeAttr(name = "overageDisabledReason") String overageDisabledReason,
			@ONodeAttr(name = "isUsingOverage") boolean isUsingOverage) {
		this.status = status;
		this.resetsAt = resetsAt;
		this.rateLimitType = rateLimitType;
		this.overageStatus = overageStatus;
		this.overageDisabledReason = overageDisabledReason;
		this.isUsingOverage = isUsingOverage;
	}

	public String status() {
		return status;
	}

	public long resetsAt() {
		return resetsAt;
	}

	public String rateLimitType() {
		return rateLimitType;
	}

	public String overageStatus() {
		return overageStatus;
	}

	public String overageDisabledReason() {
		return overageDisabledReason;
	}

	public boolean isUsingOverage() {
		return isUsingOverage;
	}

	/**
	 * Whether the request was allowed through the rate limit.
	 */
	public boolean isAllowed() {
		return "allowed".equals(status);
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof RateLimitInfo)) {
			return false;
		}
		RateLimitInfo that = (RateLimitInfo) o;
		return resetsAt == that.resetsAt && isUsingOverage == that.isUsingOverage
				&& Objects.equals(status, that.status) && Objects.equals(rateLimitType, that.rateLimitType)
				&& Objects.equals(overageStatus, that.overageStatus)
				&& Objects.equals(overageDisabledReason, that.overageDisabledReason);
	}

	@Override
	public int hashCode() {
		return Objects.hash(status, resetsAt, rateLimitType, overageStatus, overageDisabledReason, isUsingOverage);
	}

	@Override
	public String toString() {
		return "RateLimitInfo[status=" + status + ", resetsAt=" + resetsAt + ", rateLimitType=" + rateLimitType
				+ ", overageStatus=" + overageStatus + ", overageDisabledReason=" + overageDisabledReason
				+ ", isUsingOverage=" + isUsingOverage + "]";
	}
}
