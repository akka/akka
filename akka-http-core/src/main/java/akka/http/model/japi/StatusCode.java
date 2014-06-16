/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

/**
 * Represents an Http status-code and message. See {@link StatusCodes} for the set of predefined
 * status-codes.
 */
public abstract class StatusCode {
    /**
     * Returns the numeric code of this status code.
     */
    public abstract int intValue();

    /**
     * Returns the reason message for this status code.
     */
    public abstract String reason();

    /**
     * Returns the default message to be included as the content of an Http response
     * with this status-code.
     */
    public abstract String defaultMessage();

    /**
     * Returns if the status-code represents success.
     */
    public abstract boolean isSuccess();

    /**
     * Returns if the status-code represents failure.
     */
    public abstract boolean isFailure();

    /**
     * Returns if a response with this status-code is allowed to be accompanied with
     * a non-empty entity.
     */
    public abstract boolean allowsEntity();
}
