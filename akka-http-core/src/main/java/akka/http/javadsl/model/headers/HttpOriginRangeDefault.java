package akka.http.javadsl.model.headers;

import akka.http.impl.util.Util;
import akka.http.scaladsl.model.headers.HttpOriginRange$;


public abstract class HttpOriginRangeDefault extends HttpOriginRange {
    public static akka.http.scaladsl.model.headers.HttpOriginRange.Default create(HttpOrigin... origins) {
        return HttpOriginRange$.MODULE$.apply(Util.convertArray(origins));
    }
}
