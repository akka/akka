/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server;

import java.util.concurrent.CompletionStage;

import akka.http.scaladsl.coding.Deflate$;
import akka.http.scaladsl.coding.Gzip$;
import akka.http.scaladsl.coding.NoCoding$;
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

    public ByteString encode(ByteString input) {
        return underlying.encode(input);
    }
    public CompletionStage<ByteString> decode(ByteString input, Materializer mat) {
        return FutureConverters.toJava(underlying.decode(input, mat));
    }
    public akka.http.scaladsl.coding.Coder _underlyingScalaCoder() {
        return underlying;
    }
}
