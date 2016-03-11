/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
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

    public static final HttpEntity.Strict EMPTY = HttpEntity$.MODULE$.Empty();

    public static HttpEntity.Strict create(String string) {
        return HttpEntity$.MODULE$.apply(string);
    }

    public static HttpEntity.Strict create(byte[] bytes) {
        return HttpEntity$.MODULE$.apply(bytes);
    }

    public static HttpEntity.Strict create(ByteString bytes) {
        return HttpEntity$.MODULE$.apply(bytes);
    }

    public static HttpEntity.Strict create(ContentType.NonBinary contentType, String string) {
        return HttpEntity$.MODULE$.apply((akka.http.scaladsl.model.ContentType.NonBinary) contentType, string);
    }

    public static HttpEntity.Strict create(ContentType contentType, byte[] bytes) {
        return HttpEntity$.MODULE$.apply((akka.http.scaladsl.model.ContentType) contentType, bytes);
    }

    public static HttpEntity.Strict create(ContentType contentType, ByteString bytes) {
        return HttpEntity$.MODULE$.apply((akka.http.scaladsl.model.ContentType) contentType, bytes);
    }

    public static UniversalEntity create(ContentType contentType, File file) {
        return JavaAccessors.HttpEntity(contentType, file);
    }

    public static UniversalEntity create(ContentType contentType, File file, int chunkSize) {
        return HttpEntity$.MODULE$.apply((akka.http.scaladsl.model.ContentType) contentType, file, chunkSize);
    }

    public static HttpEntity.Default create(ContentType contentType, long contentLength, Source<ByteString, Object> data) {
        return new akka.http.scaladsl.model.HttpEntity.Default((akka.http.scaladsl.model.ContentType) contentType, contentLength, data.asScala());
    }

    public static HttpEntity.Chunked create(ContentType contentType, Source<ByteString, Object> data) {
        return HttpEntity.Chunked$.MODULE$.fromData((akka.http.scaladsl.model.ContentType) contentType, data.asScala());
    }

    public static HttpEntity.CloseDelimited createCloseDelimited(ContentType contentType, Source<ByteString, Object> data) {
        return new akka.http.scaladsl.model.HttpEntity.CloseDelimited((akka.http.scaladsl.model.ContentType) contentType, data.asScala());
    }

    public static HttpEntity.IndefiniteLength createIndefiniteLength(ContentType contentType, Source<ByteString, Object> data) {
        return new akka.http.scaladsl.model.HttpEntity.IndefiniteLength((akka.http.scaladsl.model.ContentType) contentType, data.asScala());
    }

    public static HttpEntity.Chunked createChunked(ContentType contentType, Source<ByteString, Object> data) {
        return akka.http.scaladsl.model.HttpEntity.Chunked$.MODULE$.fromData(
                (akka.http.scaladsl.model.ContentType) contentType,
                data.asScala());
    }
}
