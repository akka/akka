/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package sample.persistence;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;
import akka.japi.pf.ReceiveBuilder;
import akka.persistence.*;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

public class ConversationRecoveryExample {
  public static String PING = "PING";
  public static String PONG = "PONG";

  public static class Ping extends AbstractProcessor {
    final ActorRef pongChannel = context().actorOf(Channel.props(), "pongChannel");
    int counter = 0;

    public Ping() {
      receive(ReceiveBuilder.
          match(ConfirmablePersistent.class, cp -> cp.payload().equals(PING), cp -> {
            counter += 1;
            System.out.println(String.format("received ping %d times", counter));
            cp.confirm();
            if (!recoveryRunning()) {
              try {
                Thread.sleep(1000);
              } catch (InterruptedException e) {
                e.printStackTrace();
              }
            }
            pongChannel.tell(Deliver.create(cp.withPayload(PONG), sender().path()), self());
          }).
          match(String.class,
            s -> s.equals("init"),
            s -> pongChannel.tell(Deliver.create(Persistent.create(PONG), sender().path()), self())).build()
      );
    }
  }

  public static class Pong extends AbstractProcessor {
    private final ActorRef pingChannel = context().actorOf(Channel.props(), "pingChannel");
    private       int      counter     = 0;

    public Pong() {
      receive(ReceiveBuilder.
        match(ConfirmablePersistent.class, cp -> cp.payload().equals(PONG), cp -> {
          counter += 1;
          System.out.println(String.format("received pong %d times", counter));
          cp.confirm();
          if (!recoveryRunning()) {
            try {
              Thread.sleep(1000);
            } catch (InterruptedException e) {
              e.printStackTrace();
            }
          }
          pingChannel.tell(Deliver.create(cp.withPayload(PING), sender().path()), self());
        }).build()
      );
    }
  }

  public static void main(String... args) throws Exception {
    final ActorSystem system = ActorSystem.create("example");

    final ActorRef ping = system.actorOf(Props.create(Ping.class), "ping");
    final ActorRef pong = system.actorOf(Props.create(Pong.class), "pong");

    ping.tell("init", pong);
  }
}
