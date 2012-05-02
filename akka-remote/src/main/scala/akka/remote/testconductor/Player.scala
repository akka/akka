/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import akka.actor.{ Actor, ActorRef, ActorSystem, LoggingFSM, Props }
import RemoteConnection.getAddrString
import akka.util.duration._
import TestConductorProtocol._
import org.jboss.netty.channel.{ Channel, SimpleChannelUpstreamHandler, ChannelHandlerContext, ChannelStateEvent, MessageEvent }
import com.eaio.uuid.UUID
import com.typesafe.config.ConfigFactory
import akka.util.Timeout
import akka.util.Duration
import java.util.concurrent.TimeUnit.MILLISECONDS
import akka.pattern.ask
import akka.dispatch.Await
import scala.util.control.NoStackTrace
import akka.actor.Status
import akka.event.LoggingAdapter
import akka.actor.PoisonPill
import akka.event.Logging

object Player extends BarrierSync {

  val system = ActorSystem("Player", ConfigFactory.load().getConfig("player"))

  object Settings {
    val config = system.settings.config

    implicit val BarrierTimeout = Timeout(Duration(config.getMilliseconds("barrier-timeout"), MILLISECONDS))
  }

  private val server = system.actorOf(Props[ClientFSM], "client")

  override def enter(name: String*) {
    system.log.debug("entering barriers " + name.mkString("(", ", ", ")"))
    name foreach { b ⇒
      import Settings.BarrierTimeout
      Await.result(server ? EnterBarrier(b), Duration.Inf)
      system.log.debug("passed barrier {}", b)
    }
  }
}

object ClientFSM {
  sealed trait State
  case object Connecting extends State
  case object Connected extends State

  case class Data(channel: Channel, msg: Either[List[ClientOp], (String, ActorRef)])

  class ConnectionFailure(msg: String) extends RuntimeException(msg) with NoStackTrace
  case object Disconnected
}

class ClientFSM extends Actor with LoggingFSM[ClientFSM.State, ClientFSM.Data] {
  import ClientFSM._

  val config = context.system.settings.config

  val name = config.getString("akka.testconductor.name")
  val host = config.getString("akka.testconductor.host")
  val port = config.getInt("akka.testconductor.port")
  val handler = new PlayerHandler(self, Logging(context.system, "PlayerHandler"))

  val myself = "XXX"
  val myport = 12345

  startWith(Connecting, Data(RemoteConnection(Client, host, port, handler), Left(Nil)))

  when(Connecting, stateTimeout = 10 seconds) {
    case Event(msg: ClientOp, Data(channel, Left(msgs))) ⇒
      stay using Data(channel, Left(msg :: msgs))
    case Event(Connected, Data(channel, Left(msgs))) ⇒
      val hello = Hello.newBuilder.setName(name).setHost(myself).setPort(myport).build
      channel.write(Wrapper.newBuilder.setHello(hello).build)
      msgs.reverse foreach sendMsg(channel)
      goto(Connected) using Data(channel, Left(Nil))
    case Event(_: ConnectionFailure, _) ⇒
      // System.exit(1)
      stop
    case Event(StateTimeout, _) ⇒
      log.error("connect timeout to TestConductor")
      // System.exit(1)
      stop
  }

  when(Connected) {
    case Event(Disconnected, _) ⇒
      log.info("disconnected from TestConductor")
      throw new ConnectionFailure("disconnect")
    case Event(msg: EnterBarrier, Data(channel, _)) ⇒
      sendMsg(channel)(msg)
      stay using Data(channel, Right((msg.name, sender)))
    case Event(msg: Wrapper, Data(channel, Right((barrier, sender)))) if msg.getAllFields.size == 1 ⇒
      if (msg.hasBarrier) {
        val b = msg.getBarrier.getName
        if (b != barrier) {
          sender ! Status.Failure(new RuntimeException("wrong barrier " + b + " received while waiting for " + barrier))
        } else {
          sender ! b
        }
      }
      stay using Data(channel, Left(Nil))
  }

  onTermination {
    case StopEvent(_, _, Data(channel, _)) ⇒
      channel.close()
  }

  private def sendMsg(channel: Channel)(msg: ClientOp) {
    msg match {
      case EnterBarrier(name) ⇒
        val enter = TestConductorProtocol.EnterBarrier.newBuilder.setName(name).build
        channel.write(Wrapper.newBuilder.setBarrier(enter).build)
    }
  }

}

class PlayerHandler(fsm: ActorRef, log: LoggingAdapter) extends SimpleChannelUpstreamHandler {

  import ClientFSM._

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    log.debug("connected to {}", getAddrString(channel))
    fsm ! Connected
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    log.debug("disconnected from {}", getAddrString(channel))
    fsm ! PoisonPill
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = {
    val channel = event.getChannel
    log.debug("message from {}: {}", getAddrString(channel), event.getMessage)
    event.getMessage match {
      case msg: Wrapper if msg.getAllFields.size == 1 ⇒
        fsm ! msg
      case msg ⇒
        log.info("server {} sent garbage '{}', disconnecting", getAddrString(channel), msg)
        channel.close()
    }
  }
}

