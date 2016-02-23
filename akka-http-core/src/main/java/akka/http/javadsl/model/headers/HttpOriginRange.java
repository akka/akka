/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.scaladsl.model.headers.HttpOriginRange$;
import akka.http.impl.util.Util;

public abstract class HttpOriginRange {
    public abstract boolean matches(HttpOrigin origin);

    public static final HttpOriginRange ALL = akka.http.scaladsl.model.headers.HttpOriginRange.$times$.MODULE$;
    public static HttpOriginRange create(HttpOrigin... origins) {
        return HttpOriginRange$.MODULE$.apply(Util.<HttpOrigin, akka.http.scaladsl.model.headers.HttpOrigin>convertArray(origins));
    }
}
