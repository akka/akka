/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

import akka.pattern.Patterns;
import akka.testkit.javadsl.TestKit;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.camel.CamelMessage;

public class ProducerTestBase {
  public void tellJmsProducer() {
    //#TellProducer
    ActorSystem system = ActorSystem.create("some-system");
    Props props = Props.create(Orders.class);
    ActorRef producer = system.actorOf(props, "jmsproducer");
    producer.tell("<order amount=\"100\" currency=\"PLN\" itemId=\"12345\"/>",
        ActorRef.noSender());
    //#TellProducer
    TestKit.shutdownActorSystem(system);
  }

  @SuppressWarnings("unused")
  public void askProducer() {
    //#AskProducer
    ActorSystem system = ActorSystem.create("some-system");
    Props props = Props.create(FirstProducer.class);
    ActorRef producer = system.actorOf(props,"myproducer");
    CompletionStage<Object> future = Patterns.ask(producer, "some request",
                                                  Duration.ofMillis(1000L));
    //#AskProducer
    system.stop(producer);
    TestKit.shutdownActorSystem(system);
  }

  public void correlate(){
    //#Correlate
    ActorSystem system = ActorSystem.create("some-system");
    Props props = Props.create(Orders.class);
    ActorRef producer = system.actorOf(props,"jmsproducer");
    Map<String,Object> headers = new HashMap<String, Object>();
    headers.put(CamelMessage.MessageExchangeId(),"123");
    producer.tell(new CamelMessage("<order amount=\"100\" currency=\"PLN\" " +
      "itemId=\"12345\"/>",headers), ActorRef.noSender());
    //#Correlate
    system.stop(producer);
    TestKit.shutdownActorSystem(system);
  }
}
