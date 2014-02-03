/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.contrib.pattern;

import akka.testkit.AkkaJUnitActorSystemResource;
import org.junit.ClassRule;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class DistributedPubSubMediatorTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("DistributedPubSubMediatorTest");

  private final ActorSystem system = actorSystemResource.getSystem();


  @Test
  public void demonstrateUsage() {
    //#start-subscribers
    system.actorOf(Props.create(Subscriber.class), "subscriber1");
    //another node
    system.actorOf(Props.create(Subscriber.class), "subscriber2");
    system.actorOf(Props.create(Subscriber.class), "subscriber3");
    //#start-subscribers

    //#publish-message
    //somewhere else
    ActorRef publisher = system.actorOf(Props.create(Publisher.class), "publisher");
    // after a while the subscriptions are replicated
    publisher.tell("hello", null);
    //#publish-message
  }

  static//#subscriber
  public class Subscriber extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    public Subscriber() {
      ActorRef mediator = 
        DistributedPubSubExtension.get(getContext().system()).mediator();
      // subscribe to the topic named "content"
      mediator.tell(new DistributedPubSubMediator.Subscribe("content", getSelf()), 
        getSelf());
    }

    public void onReceive(Object msg) {
      if (msg instanceof String)
        log.info("Got: {}", msg);
      else if (msg instanceof DistributedPubSubMediator.SubscribeAck)
        log.info("subscribing");
      else
        unhandled(msg);
    }
  }

  //#subscriber

  static//#publisher
  public class Publisher extends UntypedActor {

    // activate the extension
    ActorRef mediator = 
      DistributedPubSubExtension.get(getContext().system()).mediator();

    public void onReceive(Object msg) {
      if (msg instanceof String) {
        String in = (String) msg;
        String out = in.toUpperCase();
        mediator.tell(new DistributedPubSubMediator.Publish("content", out), 
          getSelf());
      } else {
        unhandled(msg);
      }
    }
  }
  //#publisher
}
