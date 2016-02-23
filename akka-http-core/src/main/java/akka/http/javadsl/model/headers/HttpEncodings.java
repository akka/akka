/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

public final class HttpEncodings {
    private HttpEncodings() {}

    public static final HttpEncoding CHUNKED = akka.http.scaladsl.model.headers.HttpEncodings.chunked();
    public static final HttpEncoding COMPRESS = akka.http.scaladsl.model.headers.HttpEncodings.compress();
    public static final HttpEncoding DEFLATE = akka.http.scaladsl.model.headers.HttpEncodings.deflate();
    public static final HttpEncoding GZIP = akka.http.scaladsl.model.headers.HttpEncodings.gzip();
    public static final HttpEncoding IDENTITY = akka.http.scaladsl.model.headers.HttpEncodings.identity();
    public static final HttpEncoding X_COMPRESS = akka.http.scaladsl.model.headers.HttpEncodings.x$minuscompress();
    public static final HttpEncoding X_ZIP = akka.http.scaladsl.model.headers.HttpEncodings.x$minuszip();
}
