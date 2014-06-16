/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

/**
 * Represents an Http protocol (currently only HTTP/1.0 or HTTP/1.1). See {@link HttpProtocols}
 * for the predefined constants for the supported protocols.
 */
public abstract class HttpProtocol {
    /**
     * Returns the String representation of this protocol.
     */
    public abstract String value();
}
