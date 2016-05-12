/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.NotUsed;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.japi.JavaPartialFunction;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Source;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;

public class WebSocketRoutingExample extends AllDirectives {

  //#websocket-route
  public Route createRoute() {
    return
      path("greeter", () ->
        handleWebSocketMessages(greeter())
      );
  }
  //#websocket-route

  /**
   * A handler that treats incoming messages as a name,
   * and responds with a greeting to that name
   */
  public static Flow<Message, Message, NotUsed> greeter() {
    return
      Flow.<Message>create()
        .collect(new JavaPartialFunction<Message, Message>() {
          @Override
          public Message apply(Message msg, boolean isCheck) throws Exception {
            if (isCheck) {
              if (msg.isText()) return null;
              else throw noMatch();
            } else return handleTextMessage(msg.asTextMessage());
          }
        });
  }

  public static TextMessage handleTextMessage(TextMessage msg) {
    if (msg.isStrict()) // optimization that directly creates a simple response...
    {
      return TextMessage.create("Hello " + msg.getStrictText());
    } else // ... this would suffice to handle all text messages in a streaming fashion
    {
      return TextMessage.create(Source.single("Hello ").concat(msg.getStreamedText()));
    }
  }
}
