/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.scaladsl.model.IllegalHeaderException;

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
     * Returns true if and only if nameInLowerCase.equals(lowercaseName()).
     */
    public abstract boolean is(String nameInLowerCase);

    /**
     * Returns !is(nameInLowerCase).
     */
    public abstract boolean isNot(String nameInLowerCase);

    /**
     * Returns true if and only if the header is to be rendered in requests.
     */
    public abstract boolean renderInRequests();

    /**
     * Returns true if and only if the header is to be rendered in responses.
     */
    public abstract boolean renderInResponses();

    /**
     * Attempts to parse the given header name and value string into a header model instance.
     *
     * @throws IllegalArgumentException if parsing is unsuccessful.
     */
    public static HttpHeader parse(String name, String value) {
      final akka.http.scaladsl.model.HttpHeader.ParsingResult result =
        akka.http.scaladsl.model.HttpHeader.parse(name, value,
          akka.http.impl.model.parser.HeaderParser$.MODULE$.DefaultSettings());

      if (result instanceof akka.http.scaladsl.model.HttpHeader$ParsingResult$Ok) {
        return ((akka.http.scaladsl.model.HttpHeader$ParsingResult$Ok) result).header();
      }
      else {
        throw new IllegalHeaderException(((akka.http.scaladsl.model.HttpHeader$ParsingResult$Error)result).error());
      }
    }
}
