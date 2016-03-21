/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

/**
 * Represents an Http status-code and message. See {@link StatusCodes} for the set of predefined
 * status-codes.
 *
 * @see StatusCodes for convenience access to often used values.
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

    /**
     * Returns if the status-code is a redirection status code.
     */
    public abstract boolean isRedirection();
}
