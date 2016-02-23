/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.scaladsl.model.headers.HttpEncodingRange$;

public abstract class HttpEncodingRange {
    public abstract float qValue();
    public abstract boolean matches(HttpEncoding encoding);

    public abstract HttpEncodingRange withQValue(float qValue);

    public static final HttpEncodingRange ALL = akka.http.scaladsl.model.headers.HttpEncodingRange.$times$.MODULE$;
    public static HttpEncodingRange create(HttpEncoding encoding) {
        return HttpEncodingRange$.MODULE$.apply((akka.http.scaladsl.model.headers.HttpEncoding) encoding);
    }
}
