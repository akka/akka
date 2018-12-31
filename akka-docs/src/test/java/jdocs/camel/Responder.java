/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;
//#CustomRoute
import akka.actor.UntypedAbstractActor;
import akka.camel.CamelMessage;
import akka.dispatch.Mapper;

public class Responder extends UntypedAbstractActor{

  public void onReceive(Object message) {
    if (message instanceof CamelMessage) {
      CamelMessage camelMessage = (CamelMessage) message;
      getSender().tell(createResponse(camelMessage), getSelf());
    } else
      unhandled(message);
  }

  private CamelMessage createResponse(CamelMessage msg) {
    return msg.mapBody(new Mapper<String,String>() {
      @Override
      public String apply(String body) {
        return String.format("received %s", body);
      }
    });
  }
}
//#CustomRoute