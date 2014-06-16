/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

/**
 * Contains constants of the supported Http protocols.
 */
public final class HttpProtocols {
    private HttpProtocols() {}

    final HttpProtocol HTTP_1_0 = akka.http.model.HttpProtocols.HTTP$div1$u002E0();
    final HttpProtocol HTTP_1_1 = akka.http.model.HttpProtocols.HTTP$div1$u002E1();
}
