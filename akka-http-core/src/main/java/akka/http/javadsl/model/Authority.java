/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model;

import java.nio.charset.Charset;

/**
 * Represents a hostname, port and user info.
 */
public abstract class Authority {

  /**
   * Host in a URI
   */
  public abstract Host host();

  /**
   * A port number that may be `0` to signal the default port of for scheme.
   * In general what you want is not the value of this field but {@link Uri::port::}
   */
  public abstract int port();

  /**
   * The percent decoded userinfo. According to https://tools.ietf.org/html/rfc3986#section-3.2.1
   * the "user:password" syntax is deprecated and implementations are encouraged to ignore any characters
   * after the colon (`:`). Therefore, it is not guaranteed that future versions of this class will
   * preserve full userinfo between parsing and rendering (even if it might do so right now).
   */
  public abstract String userinfo();

  /**
   * Returns a Authority created by parsing the given string representation.
   */
  public static Authority create(String authority) {
    return akka.http.scaladsl.model.Uri.Authority$.MODULE$.parse(
      akka.parboiled2.ParserInput$.MODULE$.apply(authority),
      Charset.forName("UTF8"),
      akka.http.scaladsl.model.Uri$ParsingMode$Relaxed$.MODULE$
    );
  }
}
