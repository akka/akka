/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend

import akka.actor.Actor
import akka.actor.Props
import akka.actor.Timers
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.adapter._
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.io.IO
import akka.io.Udp
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.stream.scaladsl.Tcp
import akka.util.ByteString

import java.net.InetSocketAddress
import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object AkkaIOBehavior {

  def apply(whenDone: ActorRef[String]): Behavior[String] = Behaviors.setup { context =>
    implicit val system: ActorSystem[_] = context.system
    implicit val ec: ExecutionContext = context.executionContext

    // FIXME ideally we'd cover TLS as well?
    val tcpHost = "127.0.0.1"
    val tcpPort = 1337
    val tcp = Tcp(context.system)

    // TCP echo server
    tcp
      .bind(tcpHost, tcpPort)
      .runWith(Sink.foreach(incomingConnection => incomingConnection.handleWith(Flow[ByteString])))

    // pass bytes over tcp, collect response, send to self
    Source
      .single(ByteString.fromString("TCP works"))
      .via(tcp.outgoingConnection(tcpHost, tcpPort))
      .runWith(Sink.fold(ByteString.empty)(_ ++ _))
      .map(allTheBytes => context.self ! allTheBytes.utf8String)

    // UDP
    val udpHost = "127.0.0.1"
    val udpPort = 1338
    val udpAddress = new InetSocketAddress(udpHost, udpPort)
    context.toClassic.actorOf(Props(new UdpListener(udpAddress, context.self)))
    context.toClassic.actorOf(Props(new UdpSender(udpAddress)))

    var waitingForMessages = Set("TCP works", "UDP works")
    Behaviors.receiveMessage[String] { msg =>
      context.log.info("IO check got {}", msg)
      waitingForMessages = waitingForMessages - msg
      if (waitingForMessages.isEmpty) {
        whenDone ! "IO subsystem works"
        Behaviors.stopped
      } else {
        context.log.info("IO check still waiting for {}", waitingForMessages)
        Behaviors.same
      }

    }
  }

  class UdpListener(address: InetSocketAddress, forwardTo: ActorRef[String]) extends Actor {
    import context.system
    IO(Udp) ! Udp.Bind(self, address)

    def receive = {
      case Udp.Bound(local) =>
        context.become(ready(sender()))
    }

    def ready(socket: akka.actor.ActorRef): Receive = {
      case Udp.Received(data, _) =>
        forwardTo ! data.utf8String
      case Udp.Unbind  => socket ! Udp.Unbind
      case Udp.Unbound => context.stop(self)
    }
  }

  class UdpSender(remote: InetSocketAddress) extends Actor with Timers {
    import context.system
    IO(Udp) ! Udp.SimpleSender

    def receive = {
      case Udp.SimpleSenderReady =>
        // keep sending until stopped
        timers.startTimerAtFixedRate("key", "tick", 50.millis)
        context.become(ready(sender()))
    }

    def ready(send: akka.actor.ActorRef): Receive = {
      case msg: String =>
        send ! Udp.Send(ByteString.fromString("UDP works"), remote)
    }
  }

}
