/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.io

import java.net.InetSocketAddress
import java.nio.channels.{ SelectionKey, SocketChannel }
import scala.util.control.NonFatal
import scala.concurrent.duration._
import akka.actor.{ ReceiveTimeout, ActorRef }
import akka.io.TcpConnection.CloseInformation
import akka.io.SelectionHandler._
import akka.io.Tcp._

/**
 * An actor handling the connection state machine for an outgoing connection
 * to be established.
 *
 * INTERNAL API
 */
private[io] class TcpOutgoingConnection(
  _tcp:            TcpExt,
  channelRegistry: ChannelRegistry,
  commander:       ActorRef,
  connect:         Connect)
  extends TcpConnection(_tcp, SocketChannel.open().configureBlocking(false).asInstanceOf[SocketChannel], connect.pullMode) {

  import context._
  import connect._

  signDeathPact(commander)

  options.foreach(_.beforeConnect(channel.socket))
  localAddress.foreach(channel.socket.bind)
  channelRegistry.register(channel, 0)
  timeout foreach context.setReceiveTimeout //Initiate connection timeout if supplied

  private def stop(): Unit = stopWith(CloseInformation(Set(commander), connect.failureMessage))

  private def reportConnectFailure(thunk: ⇒ Unit): Unit = {
    try {
      thunk
    } catch {
      case NonFatal(e) ⇒
        log.debug("Could not establish connection to [{}] due to {}", remoteAddress, e)
        stop()
    }
  }

  def receive: Receive = {
    case registration: ChannelRegistration ⇒
      reportConnectFailure {
        if (remoteAddress.isUnresolved) {
          log.debug("Resolving {} before connecting", remoteAddress.getHostName)
          Dns.resolve(remoteAddress.getHostName)(system, self) match {
            case None ⇒
              context.become(resolving(registration))
            case Some(resolved) ⇒
              register(new InetSocketAddress(resolved.addr, remoteAddress.getPort), registration)
          }
        } else {
          register(remoteAddress, registration)
        }
      }
  }

  def resolving(registration: ChannelRegistration): Receive = {
    case resolved: Dns.Resolved ⇒
      reportConnectFailure {
        register(new InetSocketAddress(resolved.addr, remoteAddress.getPort), registration)
      }
  }

  def register(address: InetSocketAddress, registration: ChannelRegistration): Unit = {
    reportConnectFailure {
      log.debug("Attempting connection to [{}]", address)
      if (channel.connect(address))
        completeConnect(registration, commander, options)
      else {
        registration.enableInterest(SelectionKey.OP_CONNECT)
        context.become(connecting(registration, tcp.Settings.FinishConnectRetries))
      }
    }
  }

  def connecting(registration: ChannelRegistration, remainingFinishConnectRetries: Int): Receive = {
    {
      case ChannelConnectable ⇒
        reportConnectFailure {
          if (channel.finishConnect()) {
            if (timeout.isDefined) context.setReceiveTimeout(Duration.Undefined) // Clear the timeout
            log.debug("Connection established to [{}]", remoteAddress)
            completeConnect(registration, commander, options)
          } else {
            if (remainingFinishConnectRetries > 0) {
              context.system.scheduler.scheduleOnce(1.millisecond) {
                channelRegistry.register(channel, SelectionKey.OP_CONNECT)
              }(context.dispatcher)
              context.become(connecting(registration, remainingFinishConnectRetries - 1))
            } else {
              log.debug("Could not establish connection because finishConnect " +
                "never returned true (consider increasing akka.io.tcp.finish-connect-retries)")
              stop()
            }
          }
        }

      case ReceiveTimeout ⇒
        if (timeout.isDefined) context.setReceiveTimeout(Duration.Undefined) // Clear the timeout
        log.debug("Connect timeout expired, could not establish connection to [{}]", remoteAddress)
        stop()
    }
  }
}
