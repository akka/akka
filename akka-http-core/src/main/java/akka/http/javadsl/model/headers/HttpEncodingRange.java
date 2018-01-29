/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.scaladsl.model.headers.HttpEncodingRange$;

/**
 * @see HttpEncodingRanges for convenience access to often used values.
 */
public abstract class HttpEncodingRange {
    public abstract float qValue();
    public abstract boolean matches(HttpEncoding encoding);

    public abstract HttpEncodingRange withQValue(float qValue);

    public static HttpEncodingRange create(HttpEncoding encoding) {
        return HttpEncodingRange$.MODULE$.apply((akka.http.scaladsl.model.headers.HttpEncoding) encoding);
    }
}
