/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

import java.io.File;

import akka.http.impl.util.JavaAccessors;
import akka.http.scaladsl.model.HttpEntity;
import akka.http.scaladsl.model.HttpEntity$;
import akka.util.ByteString;
import akka.stream.javadsl.Source;

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
        return HttpEntity$.MODULE$.apply((akka.http.scaladsl.model.ContentType) contentType, string);
    }

    public static HttpEntityStrict create(ContentType contentType, byte[] bytes) {
        return HttpEntity$.MODULE$.apply((akka.http.scaladsl.model.ContentType) contentType, bytes);
    }

    public static HttpEntityStrict create(ContentType contentType, ByteString bytes) {
        return HttpEntity$.MODULE$.apply((akka.http.scaladsl.model.ContentType) contentType, bytes);
    }

    public static UniversalEntity create(ContentType contentType, File file) {
        return JavaAccessors.HttpEntity(contentType, file);
    }

    public static UniversalEntity create(ContentType contentType, File file, int chunkSize) {
        return HttpEntity$.MODULE$.apply((akka.http.scaladsl.model.ContentType) contentType, file, chunkSize);
    }

    public static HttpEntityDefault create(ContentType contentType, long contentLength, Source<ByteString, Object> data) {
        return new akka.http.scaladsl.model.HttpEntity.Default((akka.http.scaladsl.model.ContentType) contentType, contentLength, data.asScala());
    }

    public static HttpEntity.Chunked create(ContentType contentType, Source<ByteString, Object> data) {
        return HttpEntity.Chunked$.MODULE$.fromData((akka.http.scaladsl.model.ContentType) contentType, data.asScala());
    }

    public static HttpEntityCloseDelimited createCloseDelimited(ContentType contentType, Source<ByteString, Object> data) {
        return new akka.http.scaladsl.model.HttpEntity.CloseDelimited((akka.http.scaladsl.model.ContentType) contentType, data.asScala());
    }

    public static HttpEntityIndefiniteLength createIndefiniteLength(ContentType contentType, Source<ByteString, Object> data) {
        return new akka.http.scaladsl.model.HttpEntity.IndefiniteLength((akka.http.scaladsl.model.ContentType) contentType, data.asScala());
    }

    public static HttpEntityChunked createChunked(ContentType contentType, Source<ByteString, Object> data) {
        return akka.http.scaladsl.model.HttpEntity.Chunked$.MODULE$.fromData(
                (akka.http.scaladsl.model.ContentType) contentType,
                data.asScala());
    }
}
