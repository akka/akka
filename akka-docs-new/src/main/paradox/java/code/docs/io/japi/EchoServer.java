/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.io.japi;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.Props;

import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

public class EchoServer {

  public static void main(String[] args) throws InterruptedException {
    final Config config = ConfigFactory.parseString("akka.loglevel=DEBUG");
    final ActorSystem system = ActorSystem.create("EchoServer", config);
    try {
      final CountDownLatch latch = new CountDownLatch(1);
      final ActorRef watcher = system.actorOf(Props.create(Watcher.class, latch), "watcher");
      final ActorRef nackServer = system.actorOf(Props.create(EchoManager.class, EchoHandler.class), "nack");
      final ActorRef ackServer = system.actorOf(Props.create(EchoManager.class, SimpleEchoHandler.class), "ack");
      watcher.tell(nackServer, ActorRef.noSender());
      watcher.tell(ackServer, ActorRef.noSender());
      latch.await(10, TimeUnit.MINUTES);
    } finally {
      system.terminate();
    }
  }

}
