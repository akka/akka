/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import java.nio.channels.{ SocketChannel, SelectionKey, ServerSocketChannel }
import java.net.InetSocketAddress
import scala.annotation.tailrec
import scala.util.control.NonFatal
import akka.actor._
import akka.io.SelectionHandler._
import akka.io.Tcp._
import akka.dispatch.{ UnboundedMessageQueueSemantics, RequiresMessageQueue }

/**
 * INTERNAL API
 */
private[io] object TcpListener {

  case class RegisterIncoming(channel: SocketChannel) extends HasFailureMessage with NoSerializationVerificationNeeded {
    def failureMessage = FailedRegisterIncoming(channel)
  }

  case class FailedRegisterIncoming(channel: SocketChannel) extends NoSerializationVerificationNeeded

}

/**
 * INTERNAL API
 */
private[io] class TcpListener(selectorRouter: ActorRef,
                              tcp: TcpExt,
                              channelRegistry: ChannelRegistry,
                              bindCommander: ActorRef,
                              bind: Bind)
  extends Actor with ActorLogging with RequiresMessageQueue[UnboundedMessageQueueSemantics] {

  import TcpListener._
  import tcp.Settings._

  context.watch(bind.handler) // sign death pact

  val channel = ServerSocketChannel.open
  channel.configureBlocking(false)

  val localAddress =
    try {
      val socket = channel.socket
      bind.options.foreach(_.beforeServerSocketBind(socket))
      socket.bind(bind.localAddress, bind.backlog)
      val ret = socket.getLocalSocketAddress match {
        case isa: InetSocketAddress ⇒ isa
        case x                      ⇒ throw new IllegalArgumentException(s"bound to unknown SocketAddress [$x]")
      }
      channelRegistry.register(channel, SelectionKey.OP_ACCEPT)
      log.debug("Successfully bound to {}", ret)
      ret
    } catch {
      case NonFatal(e) ⇒
        bindCommander ! bind.failureMessage
        log.debug("Bind failed for TCP channel on endpoint [{}]: {}", bind.localAddress, e)
        context.stop(self)
    }

  override def supervisorStrategy = SelectionHandler.connectionSupervisorStrategy

  def receive: Receive = {
    case registration: ChannelRegistration ⇒
      bindCommander ! Bound(channel.socket.getLocalSocketAddress.asInstanceOf[InetSocketAddress])
      context.become(bound(registration))
  }

  def bound(registration: ChannelRegistration): Receive = {
    case ChannelAcceptable ⇒
      acceptAllPending(registration, BatchAcceptLimit)

    case FailedRegisterIncoming(socketChannel) ⇒
      log.warning("Could not register incoming connection since selector capacity limit is reached, closing connection")
      try socketChannel.close()
      catch {
        case NonFatal(e) ⇒ log.debug("Error closing socket channel: {}", e)
      }

    case Unbind ⇒
      log.debug("Unbinding endpoint {}", localAddress)
      channel.close()
      sender ! Unbound
      log.debug("Unbound endpoint {}, stopping listener", localAddress)
      context.stop(self)
  }

  @tailrec final def acceptAllPending(registration: ChannelRegistration, limit: Int): Unit = {
    val socketChannel =
      if (limit > 0) {
        try channel.accept()
        catch {
          case NonFatal(e) ⇒ { log.error(e, "Accept error: could not accept new connection"); null }
        }
      } else null
    if (socketChannel != null) {
      log.debug("New connection accepted")
      socketChannel.configureBlocking(false)
      def props(registry: ChannelRegistry) =
        Props(classOf[TcpIncomingConnection], tcp, socketChannel, registry, bind.handler, bind.options)
      selectorRouter ! WorkerForCommand(RegisterIncoming(socketChannel), self, props)
      acceptAllPending(registration, limit - 1)
    } else registration.enableInterest(SelectionKey.OP_ACCEPT)
  }

  override def postStop() {
    try {
      if (channel.isOpen) {
        log.debug("Closing serverSocketChannel after being stopped")
        channel.close()
      }
    } catch {
      case NonFatal(e) ⇒ log.debug("Error closing ServerSocketChannel: {}", e)
    }
  }
}
