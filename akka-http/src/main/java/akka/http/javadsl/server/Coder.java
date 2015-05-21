/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server;

import akka.http.scaladsl.coding.Deflate$;
import akka.http.scaladsl.coding.Gzip$;
import akka.http.scaladsl.coding.NoCoding$;
import akka.stream.FlowMaterializer;
import akka.util.ByteString;
import scala.concurrent.Future;

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
    public Future<ByteString> decode(ByteString input, FlowMaterializer mat) {
        return underlying.decode(input, mat);
    }
    public akka.http.scaladsl.coding.Coder _underlyingScalaCoder() {
        return underlying;
    }
}
