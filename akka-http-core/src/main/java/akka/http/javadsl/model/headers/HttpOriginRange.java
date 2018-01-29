/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
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
}
