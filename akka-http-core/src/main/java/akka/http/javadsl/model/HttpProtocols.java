/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

/**
 * Contains constants of the supported Http protocols.
 */
public final class HttpProtocols {
    private HttpProtocols() {}

    public final static HttpProtocol HTTP_1_0 = akka.http.scaladsl.model.HttpProtocols.HTTP$div1$u002E0();
    public final static HttpProtocol HTTP_1_1 = akka.http.scaladsl.model.HttpProtocols.HTTP$div1$u002E1();
}
