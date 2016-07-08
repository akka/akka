/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 * Model for the `Connection` header.
 * Specification: https://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.10
 */
public abstract class Connection extends akka.http.scaladsl.model.HttpHeader {
  public abstract Iterable<String> getTokens();

  public static Connection create(String... directives) {
    return new akka.http.scaladsl.model.headers.Connection(akka.http.impl.util.Util.convertArray(directives));
  }
}
