/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.util.JavaAccessors;
import akka.http.scaladsl.model.HttpEntity$;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import scala.collection.immutable.List;
import scala.collection.immutable.Nil$;

import java.io.File;
import java.nio.file.Path;
import java.util.Map;

import static akka.http.impl.util.Util.convertMapToScala;
import static akka.http.impl.util.Util.convertArray;

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

    public static Multipart.FormData fromParts(Multipart.FormData.BodyPart ... parts) {
        return akka.http.scaladsl.model.Multipart.FormData$.MODULE$.applyNonStrict(convertArray(parts));
    }

    public static Multipart.FormData.Strict fromParts(Multipart.FormData.BodyPart.Strict ... parts) {
        return akka.http.scaladsl.model.Multipart.FormData$.MODULE$.applyStrict(convertArray(parts));
    }

    public static Multipart.FormData.BodyPart create(String name, BodyPartEntity entity, Map<String, String> additionalDispositionParams) {
        List nil = Nil$.MODULE$;
        return akka.http.scaladsl.model.Multipart$FormData$BodyPart$.MODULE$.apply(name, (akka.http.scaladsl.model.BodyPartEntity) entity,
                convertMapToScala(additionalDispositionParams), nil);
    }

    public static Multipart.FormData.BodyPart.Strict createStrict(String name, HttpEntity.Strict entity, Map<String, String> additionalDispositionParams) {
        List nil = Nil$.MODULE$;
        return akka.http.scaladsl.model.Multipart$FormData$BodyPart$StrictBuilder$.MODULE$.apply(name, (akka.http.scaladsl.model.HttpEntity.Strict) entity,
                convertMapToScala(additionalDispositionParams), nil);
    }

    /**
     * @deprecated Will be removed in Akka 3.x, use {@link #create(ContentType, Path)} instead.
     */
    @Deprecated
    public static UniversalEntity create(ContentType contentType, File file) {
        return JavaAccessors.HttpEntity(contentType, file);
    }

    public static UniversalEntity create(ContentType contentType, Path file) {
        return JavaAccessors.HttpEntity(contentType, file);
    }

    /**
     * @deprecated Will be removed in Akka 3.x, use {@link #create(ContentType, Path, int)} instead.
     */
    @Deprecated
    public static UniversalEntity create(ContentType contentType, File file, int chunkSize) {
        return HttpEntity$.MODULE$.apply((akka.http.scaladsl.model.ContentType) contentType, file, chunkSize);
    }

    public static UniversalEntity create(ContentType contentType, Path file, int chunkSize) {
        return HttpEntity$.MODULE$.fromPath((akka.http.scaladsl.model.ContentType) contentType, file, chunkSize);
    }

    public static HttpEntity.Default create(ContentType contentType, long contentLength, Source<ByteString, ?> data) {
        return new akka.http.scaladsl.model.HttpEntity.Default((akka.http.scaladsl.model.ContentType) contentType, contentLength, toScala(data));
    }

    public static HttpEntity.Chunked create(ContentType contentType, Source<ByteString, ?> data) {
        return akka.http.scaladsl.model.HttpEntity.Chunked$.MODULE$.fromData((akka.http.scaladsl.model.ContentType) contentType, toScala(data));
    }

    public static HttpEntity.CloseDelimited createCloseDelimited(ContentType contentType, Source<ByteString, ?> data) {
        return new akka.http.scaladsl.model.HttpEntity.CloseDelimited((akka.http.scaladsl.model.ContentType) contentType, toScala(data));
    }

    public static HttpEntity.IndefiniteLength createIndefiniteLength(ContentType contentType, Source<ByteString, ?> data) {
        return new akka.http.scaladsl.model.HttpEntity.IndefiniteLength((akka.http.scaladsl.model.ContentType) contentType, toScala(data));
    }

    public static HttpEntity.Chunked createChunked(ContentType contentType, Source<ByteString, ?> data) {
        return akka.http.scaladsl.model.HttpEntity.Chunked$.MODULE$.fromData(
                (akka.http.scaladsl.model.ContentType) contentType,
                toScala(data));
    }

    private static akka.stream.scaladsl.Source<ByteString,Object> toScala(Source<ByteString, ?> javaSource) {
        return (akka.stream.scaladsl.Source<ByteString,Object>)javaSource.asScala();
    }
}
