/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

public final class HttpEncodings {
    private HttpEncodings() {}

    public static final HttpEncoding CHUNKED = akka.http.model.headers.HttpEncodings.chunked();
    public static final HttpEncoding COMPRESS = akka.http.model.headers.HttpEncodings.compress();
    public static final HttpEncoding DEFLATE = akka.http.model.headers.HttpEncodings.deflate();
    public static final HttpEncoding GZIP = akka.http.model.headers.HttpEncodings.gzip();
    public static final HttpEncoding IDENTITY = akka.http.model.headers.HttpEncodings.identity();
    public static final HttpEncoding X_COMPRESS = akka.http.model.headers.HttpEncodings.x$minuscompress();
    public static final HttpEncoding X_ZIP = akka.http.model.headers.HttpEncodings.x$minuszip();
}
