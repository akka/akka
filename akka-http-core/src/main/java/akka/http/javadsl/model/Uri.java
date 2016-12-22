/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import java.nio.charset.Charset;

import akka.http.impl.model.JavaUri;
import akka.http.scaladsl.model.UriJavaAccessor;
import akka.parboiled2.ParserInput$;

import java.util.Optional;

/**
 * Represents an Uri. Use the `withX` methods to create modified copies of a given instance.
 */
public abstract class Uri {
  /**
   * Returns if this is an absolute Uri.
   */
  public abstract boolean isAbsolute();

  /**
   * Returns if this is a relative Uri.
   */
  public abstract boolean isRelative();

  /**
   * Returns if this is an empty Uri.
   */
  public abstract boolean isEmpty();

  /**
   * Returns the scheme of this Uri.
   */
  public abstract String scheme();

  /**
   * Returns the Host of this Uri.
   */
  public abstract Host host();

  /**
   * Returns the port of this Uri.
   */
  public abstract int port();

  /**
   * Returns the user-info of this Uri.
   */
  public abstract String userInfo();

  /**
   * Returns a String representation of the path of this Uri.
   */
  public abstract String path();

  /**
   * Returns the path segments of this Uri as an Iterable.
   */
  public abstract Iterable<String> pathSegments();

  /**
   * Returns a decoded String representation of the query of this Uri.
   */
  public abstract Optional<String> queryString(Charset charset);

  /**
   * Returns an undecoded String representation of the query of this Uri.
   */
  public abstract Optional<String> rawQueryString();

  /**
   * Returns the parsed Query instance of this Uri.
   */
  public abstract Query query();

  /**
   * Returns the parsed Query instance of this Uri using the given charset and parsing mode.
   */
  public abstract Query query(Charset charset, akka.http.scaladsl.model.Uri.ParsingMode mode);

  /**
   * Returns the fragment part of this Uri.
   */
  public abstract Optional<String> fragment();

  /**
   * Returns a copy of this instance with a new scheme.
   */
  public abstract Uri scheme(String scheme);

  /**
   * Returns a copy of this instance with a new Host.
   */
  public abstract Uri host(Host host);

  /**
   * Returns a copy of this instance with a new host.
   */
  public abstract Uri host(String host);

  /**
   * Returns a copy of this instance with a new port.
   */
  public abstract Uri port(int port);

  /**
   * Returns a copy of this instance with new user-info.
   */
  public abstract Uri userInfo(String userInfo);

  /**
   * Returns a copy of this instance with a new path.
   */
  public abstract Uri path(String path);

  /**
   * Returns a copy of this instance with a path segment added at the end.
   */
  public abstract Uri addPathSegment(String segment);

  /**
   * Returns a copy of this instance with a new query.
   */
  public abstract Uri rawQueryString(String rawQuery);

  /**
   * Returns a copy of this instance with a new query.
   */
  public abstract Uri query(Query query);

  /**
   * Returns a copy of this instance that is relative.
   */
  public abstract Uri toRelative();

  /**
   * Returns a copy of this instance with a new fragment.
   */
  public abstract Uri fragment(String fragment);

  /**
   * Returns a copy of this instance with a new optional fragment.
   */
  public abstract Uri fragment(Optional<String> fragment);

  /**
   * Returns the scheme of this instance
   */
  public abstract String getScheme();

  /**
   * Returns the host of this instance
   */
  public abstract Host getHost();

  /**
   * Returns the port of this instance
   */
  public abstract int getPort();

  /**
   * Returns the user info of this instance
   */
  public abstract String getUserInfo();

  /**
   * Returns the path of this instance
   */
  public abstract String getPathString();

  public static final akka.http.scaladsl.model.Uri.ParsingMode STRICT = UriJavaAccessor.pmStrict();
  public static final akka.http.scaladsl.model.Uri.ParsingMode RELAXED = UriJavaAccessor.pmRelaxed();

  /**
   * Creates a default Uri to be modified using the modification methods.
   */
  public static final Uri EMPTY = new JavaUri(akka.http.scaladsl.model.Uri.Empty$.MODULE$);

  /**
   * Returns a Uri created by parsing the given string representation.
   */
  public static Uri create(String uri) {
    return new JavaUri(akka.http.scaladsl.model.Uri.apply(uri));
  }

  /**
   * Returns a Uri created by parsing the given string representation with the provided parsing mode.
   */
  public static Uri create(String uri, akka.http.scaladsl.model.Uri.ParsingMode parsingMode) {
    return new JavaUri(akka.http.scaladsl.model.Uri.apply(ParserInput$.MODULE$.apply(uri), parsingMode));
  }

  /**
   * Returns a Uri created by parsing the given string representation with the provided charset and parsing mode.
   */
  public static Uri create(String uri, Charset charset, akka.http.scaladsl.model.Uri.ParsingMode parsingMode) {
    return new JavaUri(akka.http.scaladsl.model.Uri.apply(ParserInput$.MODULE$.apply(uri), charset, parsingMode));
  }

  public static interface ParsingMode {}
}
