/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

/**
 * Represents an Http protocol (currently only HTTP/1.0 or HTTP/1.1). See {@link HttpProtocols}
 * for the predefined constants for the supported protocols.
 *
 * @see HttpProtocols for convenience access to often used values.
 */
public abstract class HttpProtocol {
    /**
     * Returns the String representation of this protocol.
     */
    public abstract String value();
}
