/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.io.japi;

import java.net.InetSocketAddress;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.SupervisorStrategy;
import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.io.Tcp;
import akka.io.Tcp.Bind;
import akka.io.Tcp.Bound;
import akka.io.Tcp.CommandFailed;
import akka.io.Tcp.Connected;
import akka.io.TcpMessage;

public class EchoManager extends AbstractActor {

  final LoggingAdapter log = Logging
      .getLogger(getContext().system(), self());

  final Class<?> handlerClass;

  public EchoManager(Class<?> handlerClass) {
    this.handlerClass = handlerClass;
  }

  @Override
  public SupervisorStrategy supervisorStrategy() {
    return SupervisorStrategy.stoppingStrategy();
  }

  @Override
  public void preStart() throws Exception {
    //#manager
    final ActorRef tcpManager = Tcp.get(getContext().system()).manager();
    //#manager
    tcpManager.tell(
        TcpMessage.bind(self(), new InetSocketAddress("localhost", 0), 100),
        self());
  }

  @Override
  public void postRestart(Throwable arg0) throws Exception {
    // do not restart
    getContext().stop(self());
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(Bound.class, msg -> {
        log.info("listening on [{}]", msg.localAddress());
      })
      .match(Tcp.CommandFailed.class, failed -> {
        if (failed.cmd() instanceof Bind) {
          log.warning("cannot bind to [{}]", ((Bind) failed.cmd()).localAddress());
          getContext().stop(self());
        } else {
          log.warning("unknown command failed [{}]", failed.cmd());
        }
      })
      .match(Connected.class, conn -> {
        log.info("received connection from [{}]", conn.remoteAddress());
        final ActorRef connection = sender();
        final ActorRef handler = getContext().actorOf(
            Props.create(handlerClass, connection, conn.remoteAddress()));
        //#echo-manager
        connection.tell(TcpMessage.register(handler,
            true, // <-- keepOpenOnPeerClosed flag
            true), self());
        //#echo-manager
      })
      .build();
  }

}
