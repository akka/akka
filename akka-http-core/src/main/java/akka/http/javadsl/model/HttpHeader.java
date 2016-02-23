/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

/**
 * The base type representing Http headers. All actual header values will be instances
 * of one of the subtypes defined in the `headers` packages. Unknown headers will be subtypes
 * of {@link akka.http.javadsl.model.headers.RawHeader}.
 */
public abstract class HttpHeader {
    /**
     * Returns the name of the header.
     */
    public abstract String name();

    /**
     * Returns the String representation of the value of the header.
     */
    public abstract String value();

    /**
     * Returns the lower-cased name of the header.
     */
    public abstract String lowercaseName();

    /**
     * Returns true iff nameInLowerCase.equals(lowercaseName()).
     */
    public abstract boolean is(String nameInLowerCase);

    /**
     * Returns !is(nameInLowerCase).
     */
    public abstract boolean isNot(String nameInLowerCase);

    /**
     * Returns true iff the header is to be rendered in requests.
     */
    public abstract boolean renderInRequests();

    /**
     * Returns true iff the header is to be rendered in responses.
     */
    public abstract boolean renderInResponses();
}
