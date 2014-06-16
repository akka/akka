/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.headers.HttpEncodingRange$;

public abstract class HttpEncodingRange {
    public abstract float qValue();
    public abstract boolean matches(HttpEncoding encoding);

    public abstract HttpEncodingRange withQValue(float qValue);

    public static final HttpEncodingRange ALL = akka.http.model.headers.HttpEncodingRange.$times$.MODULE$;
    public static HttpEncodingRange create(HttpEncoding encoding) {
        return HttpEncodingRange$.MODULE$.apply((akka.http.model.headers.HttpEncoding) encoding);
    }
}
