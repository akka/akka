/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.cluster.pubsub;

import com.typesafe.config.ConfigFactory;

import akka.testkit.AkkaJUnitActorSystemResource;

import org.junit.ClassRule;
import org.junit.Test;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import org.scalatest.junit.JUnitSuite;

public class DistributedPubSubMediatorTest extends JUnitSuite {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource("DistributedPubSubMediatorTest",
        ConfigFactory.parseString(
            "akka.actor.provider = \"cluster\"\n" +
            "akka.remote.netty.tcp.port=0"));

  private final ActorSystem system = actorSystemResource.getSystem();


  @Test
  public void demonstratePublishUsage() {
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

  public void demonstrateSendUsage() {
    //#start-send-destinations
    system.actorOf(Props.create(Destination.class), "destination");
    //another node
    system.actorOf(Props.create(Destination.class), "destination");
    //#start-send-destinations

    //#send-message
    //somewhere else
    ActorRef sender = system.actorOf(Props.create(Publisher.class), "sender");
    // after a while the destinations are replicated
    sender.tell("hello", null);
    //#send-message
  }

  static//#subscriber
  public class Subscriber extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    public Subscriber() {
      ActorRef mediator =
        DistributedPubSub.get(getContext().system()).mediator();
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
      DistributedPubSub.get(getContext().system()).mediator();

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

  static//#send-destination
  public class Destination extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    public Destination() {
      ActorRef mediator =
        DistributedPubSub.get(getContext().system()).mediator();
      // register to the path
      mediator.tell(new DistributedPubSubMediator.Put(getSelf()), getSelf());
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

  //#send-destination

  static//#sender
  public class Sender extends UntypedActor {

    // activate the extension
    ActorRef mediator =
      DistributedPubSub.get(getContext().system()).mediator();

    public void onReceive(Object msg) {
      if (msg instanceof String) {
        String in = (String) msg;
        String out = in.toUpperCase();
        boolean localAffinity = true;
        mediator.tell(new DistributedPubSubMediator.Send("/user/destination", out,
            localAffinity), getSelf());
      } else {
        unhandled(msg);
      }
    }
  }
  //#sender
}
