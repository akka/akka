/**
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.impl.util.Util;

/**
 *  Model for the `Sec-WebSocket-Protocol` header.
 */
public abstract class SecWebSocketProtocol extends akka.http.scaladsl.model.HttpHeader {
  public abstract Iterable<String> getProtocols();

  public static SecWebSocketProtocol create(String... protocols) {
    return new akka.http.scaladsl.model.headers.Sec$minusWebSocket$minusProtocol(Util.convertArray(protocols));
  }

}

