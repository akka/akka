/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.impl.util.Util;
import akka.http.scaladsl.model.headers.HttpOriginRange$;


public interface HttpOriginRangeDefault extends HttpOriginRange {
    static akka.http.scaladsl.model.headers.HttpOriginRange.Default create(HttpOrigin... origins) {
        return HttpOriginRange$.MODULE$.apply(Util.convertArray(origins));
    }
}
