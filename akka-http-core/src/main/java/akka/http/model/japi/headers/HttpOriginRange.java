/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.headers.HttpOriginRange$;
import akka.http.model.japi.Util;

public abstract class HttpOriginRange {
    public abstract boolean matches(HttpOrigin origin);

    public static final HttpOriginRange ALL = akka.http.model.headers.HttpOriginRange.$times$.MODULE$;
    public static HttpOriginRange create(HttpOrigin... origins) {
        return HttpOriginRange$.MODULE$.apply(Util.<HttpOrigin, akka.http.model.headers.HttpOrigin>convertArray(origins));
    }
}
