/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.scaladsl.model.headers.HttpOriginRange$;
import akka.http.impl.util.Util;

/**
 * @see HttpOriginRanges for convenience access to often used values.
 */
public abstract class HttpOriginRange {
  public abstract boolean matches(HttpOrigin origin);

  public static HttpOriginRange create(HttpOrigin... origins) {
    return HttpOriginRange$.MODULE$.apply(Util.<HttpOrigin, akka.http.scaladsl.model.headers.HttpOrigin>convertArray(origins));
  }

  /**
   * @deprecated because of troublesome initialisation order (with regards to scaladsl class implementing this class).
   * In some edge cases this field could end up containing a null value.
   * Will be removed in Akka HTTP 11.x, use {@link HttpEncodingRanges#ALL} instead.
   */
  @Deprecated
  public static final HttpOriginRange ALL = akka.http.scaladsl.model.headers.HttpOriginRange.$times$.MODULE$;
}
