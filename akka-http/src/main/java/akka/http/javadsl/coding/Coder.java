/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.coding;

import java.util.concurrent.CompletionStage;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.headers.ContentEncoding;
import akka.http.scaladsl.coding.Deflate$;
import akka.http.scaladsl.coding.Gzip$;
import akka.http.scaladsl.coding.NoCoding$;
import akka.http.scaladsl.model.HttpMessage;
import akka.stream.Materializer;
import akka.util.ByteString;
import scala.compat.java8.FutureConverters;

/**
 * A coder is an implementation of the predefined encoders/decoders defined for HTTP.
 */
public enum Coder {
    NoCoding(NoCoding$.MODULE$), Deflate(Deflate$.MODULE$), Gzip(Gzip$.MODULE$);

    private akka.http.scaladsl.coding.Coder underlying;

    Coder(akka.http.scaladsl.coding.Coder underlying) {
        this.underlying = underlying;
    }

    public HttpResponse encodeMessage(HttpResponse message) {
        boolean shouldApply = (boolean) underlying.messageFilter().apply((HttpMessage) message);
        if (shouldApply && message.getHeader(ContentEncoding.class).isPresent()) {
            return message.transformEntityDataBytes(underlying.newEncodeTransformer());
        }
        return message;
    }

    public HttpRequest encodeMessage(HttpRequest message) {
        boolean shouldApply = (boolean) underlying.messageFilter().apply((HttpMessage) message);
        if (shouldApply && message.getHeader(ContentEncoding.class).isPresent()) {
            return message.transformEntityDataBytes(underlying.newEncodeTransformer());
        }
        return message;
    }

    public ByteString encode(ByteString input) {
        return underlying.encode(input);
    }

    public HttpResponse decodeMessage(HttpResponse message) {
        if (message.getHeader(ContentEncoding.class).isPresent()) {
            return message.transformEntityDataBytes(underlying.decoderFlow());
        }
        return message;
    }

    public HttpRequest decodeMessage(HttpRequest message) {
        if (message.getHeader(ContentEncoding.class).isPresent()) {
            return message.transformEntityDataBytes(underlying.decoderFlow());
        }
        return message;
    }

    public CompletionStage<ByteString> decode(ByteString input, Materializer mat) {
        return FutureConverters.toJava(underlying.decode(input, mat));
    }
    public akka.http.scaladsl.coding.Coder _underlyingScalaCoder() {
        return underlying;
    }
}
