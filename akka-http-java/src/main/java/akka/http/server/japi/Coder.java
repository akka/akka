/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.japi;

import akka.http.coding.Deflate$;
import akka.http.coding.Gzip$;
import akka.http.coding.NoCoding$;
import akka.util.ByteString;

/**
 * A coder is an implementation of the predefined encoders/decoders defined for HTTP.
 */
public enum Coder {
    NoCoding(NoCoding$.MODULE$), Deflate(Deflate$.MODULE$), Gzip(Gzip$.MODULE$);

    private akka.http.coding.Coder underlying;

    Coder(akka.http.coding.Coder underlying) {
        this.underlying = underlying;
    }

    public ByteString encode(ByteString input) {
        return underlying.encode(input);
    }
    public ByteString decode(ByteString input) {
        return underlying.decode(input);
    }
    public akka.http.coding.Coder _underlyingScalaCoder() {
        return underlying;
    }
}
