/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.remote.testconductor

import akka.actor.{ Actor, ActorRef, LoggingFSM, Timeout, UntypedChannel }
import akka.event.EventHandler
import RemoteConnection.getAddrString
import akka.util.duration._
import TestConductorProtocol._
import akka.NoStackTrace
import org.jboss.netty.channel.{ Channel, SimpleChannelUpstreamHandler, ChannelHandlerContext, ChannelStateEvent, MessageEvent }
import com.eaio.uuid.UUID

object Player extends BarrierSync {

  private val server = Actor.actorOf[ClientFSM]

  override def enter(name: String*) {
    EventHandler.debug(this, "entering barriers " + name.mkString("(", ", ", ")"))
    implicit val timeout = Timeout(30 seconds)
    name foreach { b ⇒
      (server ? EnterBarrier(b)).get
      EventHandler.debug(this, "passed barrier " + b)
    }
  }
}

object ClientFSM {
  sealed trait State
  case object Connecting extends State
  case object Connected extends State

  case class Data(channel: Channel, msg: Either[List[ClientOp], (String, UntypedChannel)])

  class ConnectionFailure(msg: String) extends RuntimeException(msg) with NoStackTrace
  case object Disconnected
}

class ClientFSM extends Actor with LoggingFSM[ClientFSM.State, ClientFSM.Data] {
  import ClientFSM._
  import akka.actor.FSM._

  val name = System.getProperty("akka.testconductor.name", (new UUID).toString)
  val host = System.getProperty("akka.testconductor.host", "localhost")
  val port = Integer.getInteger("akka.testconductor.port", 4545)
  val handler = new PlayerHandler(self)

  val myself = Actor.remote.address

  startWith(Connecting, Data(RemoteConnection(Client, host, port, handler), Left(Nil)))

  when(Connecting, stateTimeout = 10 seconds) {
    case Event(msg: ClientOp, Data(channel, Left(msgs))) ⇒
      stay using Data(channel, Left(msg :: msgs))
    case Event(Connected, Data(channel, Left(msgs))) ⇒
      val hello = Hello.newBuilder.setName(name).setHost(myself.getAddress.getHostAddress).setPort(myself.getPort).build
      channel.write(Wrapper.newBuilder.setHello(hello).build)
      msgs.reverse foreach sendMsg(channel)
      goto(Connected) using Data(channel, Left(Nil))
    case Event(_: ConnectionFailure, _) ⇒
      // System.exit(1)
      stop
    case Event(StateTimeout, _) ⇒
      EventHandler.error(this, "connect timeout to TestConductor")
      // System.exit(1)
      stop
  }

  when(Connected) {
    case Event(Disconnected, _) ⇒
      EventHandler.info(this, "disconnected from TestConductor")
      throw new ConnectionFailure("disconnect")
    case Event(msg: EnterBarrier, Data(channel, _)) ⇒
      sendMsg(channel)(msg)
      stay using Data(channel, Right((msg.name, self.channel)))
    case Event(msg: Wrapper, Data(channel, Right((barrier, sender)))) if msg.getAllFields.size == 1 ⇒
      if (msg.hasBarrier) {
        val b = msg.getBarrier.getName
        if (b != barrier) {
          sender.sendException(new RuntimeException("wrong barrier " + b + " received while waiting for " + barrier))
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

class PlayerHandler(fsm: ActorRef) extends SimpleChannelUpstreamHandler {

  import ClientFSM._

  override def channelConnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    EventHandler.debug(this, "connected to " + getAddrString(channel))
    while (!fsm.isRunning) Thread.sleep(100)
    fsm ! Connected
  }

  override def channelDisconnected(ctx: ChannelHandlerContext, event: ChannelStateEvent) = {
    val channel = event.getChannel
    EventHandler.debug(this, "disconnected from " + getAddrString(channel))
    fsm.stop()
  }

  override def messageReceived(ctx: ChannelHandlerContext, event: MessageEvent) = {
    val channel = event.getChannel
    EventHandler.debug(this, "message from " + getAddrString(channel) + ": " + event.getMessage)
    event.getMessage match {
      case msg: Wrapper if msg.getAllFields.size == 1 ⇒
        fsm ! msg
      case msg ⇒
        EventHandler.info(this, "server " + getAddrString(channel) + " sent garbage '" + msg + "', disconnecting")
        channel.close()
    }
  }
}

