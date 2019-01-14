/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;
// #Consumer4
import akka.camel.CamelMessage;
import akka.camel.javaapi.UntypedConsumerActor;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.util.concurrent.TimeUnit;

public class Consumer4 extends UntypedConsumerActor {
  private static final FiniteDuration timeout = Duration.create(500, TimeUnit.MILLISECONDS);

  @Override
  public FiniteDuration replyTimeout() {
    return timeout;
  }

  public String getEndpointUri() {
    return "jetty:http://localhost:8877/camel/default";
  }

  public void onReceive(Object message) {
    if (message instanceof CamelMessage) {
      CamelMessage camelMessage = (CamelMessage) message;
      String body = camelMessage.getBodyAs(String.class, getCamelContext());
      getSender().tell(String.format("Hello %s", body), getSelf());
    } else unhandled(message);
  }
}
// #Consumer4
