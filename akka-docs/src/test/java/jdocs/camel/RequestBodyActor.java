/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;
//#RequestProducerTemplate
import akka.actor.AbstractActor;
import akka.camel.Camel;
import akka.camel.CamelExtension;
import org.apache.camel.ProducerTemplate;

public class RequestBodyActor extends AbstractActor {
  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .matchAny(message -> {
        Camel camel = CamelExtension.get(getContext().getSystem());
        ProducerTemplate template = camel.template();
        getSender().tell(template.requestBody("direct:news", message), getSelf());
      })
      .build();
  }
}
//#RequestProducerTemplate