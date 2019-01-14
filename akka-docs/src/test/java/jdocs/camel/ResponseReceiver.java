/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;
// #RouteResponse
import akka.actor.UntypedAbstractActor;
import akka.camel.CamelMessage;

public class ResponseReceiver extends UntypedAbstractActor {
  public void onReceive(Object message) {
    if (message instanceof CamelMessage) {
      // do something with the forwarded response
    }
  }
}
// #RouteResponse
