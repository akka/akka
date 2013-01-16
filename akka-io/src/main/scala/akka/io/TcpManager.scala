/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import scala.concurrent.Future
import scala.concurrent.duration._
import akka.actor.{ ActorLogging, Actor, Props }
import akka.routing.RandomRouter
import akka.util.Timeout
import akka.pattern.{ ask, pipe }
import Tcp._

/**
 * TcpManager is a facade for accepting commands ([[akka.io.Tcp.Command]]) to open client or server TCP connections.
 *
 * TcpManager is obtainable by calling {{{ IO(Tcp) }}} (see [[akka.io.IO]] and [[akka.io.Tcp]])
 *
 * == Bind ==
 *
 * To bind and listen to a local address, a [[akka.io.Tcp.Bind]] command must be sent to this actor. If the binding
 * was successful, the sender of the [[akka.io.Tcp.Bind]] will be notified with a [[akka.io.Tcp.Bound]]
 * message. The sender of the [[akka.io.Tcp.Bound]] message is the Listener actor (an internal actor responsible for
 * listening to server events). To unbind the port an [[akka.io.Tcp.Unbind]] message must be sent to the Listener actor.
 *
 * If the bind request is rejected because the Tcp system is not able to register more channels (see the nr-of-selectors
 * and max-channels configuration options in the akka.io.tcp section of the configuration) the sender will be notified
 * with a [[akka.io.Tcp.CommandFailed]] message. This message contains the original command for reference.
 *
 * When an inbound TCP connection is established, the handler will be notified by a [[akka.io.Tcp.Connected]] message.
 * The sender of this message is the Connection actor (an internal actor representing the TCP connection). At this point
 * the procedure is the same as for outbound connections (see section below).
 *
 * == Connect ==
 *
 * To initiate a connection to a remote server, a [[akka.io.Tcp.Connect]] message must be sent to this actor. If the
 * connection succeeds, the sender will be notified with a [[akka.io.Tcp.Connected]] message. The sender of the
 * [[akka.io.Tcp.Connected]] message is the Connection actor (an internal actor representing the TCP connection). Before
 * starting to use the connection, a handler must be registered to the Connection actor by sending a [[akka.io.Tcp.Register]]
 * command message. After a handler has been registered, all incoming data will be sent to the handler in the form of
 * [[akka.io.Tcp.Received]] messages. To write data to the connection, a [[akka.io.Tcp.Write]] message must be sent
 * to the Connection actor.
 *
 * If the connect request is rejected because the Tcp system is not able to register more channels (see the nr-of-selectors
 * and max-channels configuration options in the akka.io.tcp section of the configuration) the sender will be notified
 * with a [[akka.io.Tcp.CommandFailed]] message. This message contains the original command for reference.
 *
 */
class TcpManager extends Actor with ActorLogging {
  val settings = Tcp(context.system).Settings
  val selectorNr = Iterator.from(0)

  val selectorPool = context.actorOf(
    props = Props(new TcpSelector(self)).withRouter(RandomRouter(settings.NrOfSelectors)),
    name = selectorNr.next().toString)

  def receive = {
    case RegisterIncomingConnection(channel, handler, options) ⇒
      selectorPool ! CreateConnection(channel, handler, options)

    case c: Connect ⇒
      selectorPool forward c

    case b: Bind ⇒
      selectorPool forward b

    case Reject(command, 0, commander) ⇒
      log.warning("Command '{}' failed since all {} selectors are at capacity", command, context.children.size)
      commander ! CommandFailed(command)

    case Reject(command, retriesLeft, commander) ⇒
      log.warning("Command '{}' rejected by {} with {} retries left, retrying...", command, sender, retriesLeft)
      selectorPool ! Retry(command, retriesLeft - 1, commander)

    case GetStats ⇒
      import context.dispatcher
      implicit val timeout: Timeout = 1 second span
      val seqFuture = Future.traverse(context.children)(_.ask(GetStats).mapTo[SelectorStats])
      seqFuture.map(s ⇒ Stats(s.map(_.channelsOpen).sum, s.map(_.channelsClosed).sum, s.toSeq)) pipeTo sender
  }
}
