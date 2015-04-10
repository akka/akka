/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import java.io.File;

import akka.util.ByteString;
import akka.stream.scaladsl.Source;
import akka.http.model.HttpEntity$;

/** Constructors for HttpEntity instances */
public final class HttpEntities {
    private HttpEntities() {}

    public static HttpEntityStrict create(String string) {
        return HttpEntity$.MODULE$.apply(string);
    }

    public static HttpEntityStrict create(byte[] bytes) {
        return HttpEntity$.MODULE$.apply(bytes);
    }

    public static HttpEntityStrict create(ByteString bytes) {
        return HttpEntity$.MODULE$.apply(bytes);
    }

    public static HttpEntityStrict create(ContentType contentType, String string) {
        return HttpEntity$.MODULE$.apply((akka.http.model.ContentType) contentType, string);
    }

    public static HttpEntityStrict create(ContentType contentType, byte[] bytes) {
        return HttpEntity$.MODULE$.apply((akka.http.model.ContentType) contentType, bytes);
    }

    public static HttpEntityStrict create(ContentType contentType, ByteString bytes) {
        return HttpEntity$.MODULE$.apply((akka.http.model.ContentType) contentType, bytes);
    }

    public static UniversalEntity create(ContentType contentType, File file) {
        return HttpEntity$.MODULE$.apply((akka.http.model.ContentType) contentType, file);
    }

    public static HttpEntityDefault create(ContentType contentType, long contentLength, Source<ByteString, Object> data) {
        return new akka.http.model.HttpEntity.Default((akka.http.model.ContentType) contentType, contentLength, data);
    }

    public static HttpEntityCloseDelimited createCloseDelimited(ContentType contentType, Source<ByteString, Object> data) {
        return new akka.http.model.HttpEntity.CloseDelimited((akka.http.model.ContentType) contentType, data);
    }

    public static HttpEntityIndefiniteLength createIndefiniteLength(ContentType contentType, Source<ByteString, Object> data) {
        return new akka.http.model.HttpEntity.IndefiniteLength((akka.http.model.ContentType) contentType, data);
    }

    public static HttpEntityChunked createChunked(ContentType contentType, Source<ByteString, Object> data) {
        return akka.http.model.HttpEntity.Chunked$.MODULE$.fromData(
                (akka.http.model.ContentType) contentType,
                data);
    }
}
