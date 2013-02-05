/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.io

import akka.actor.{ ActorRef, Props, Actor }
import akka.io.UdpFF._
import akka.routing.RandomRouter
import akka.io.SelectionHandler.{ KickStartFailed, KickStartCommand }
import akka.io.IO.SelectorBasedManager

/**
 * UdpFFManager is a facade for simple fire-and-forget style UDP operations
 *
 * UdpFFManager is obtainable by calling {{{ IO(UdpFF) }}} (see [[akka.io.IO]] and [[akka.io.UdpFF]])
 *
 * *Warning!* UdpFF uses [[java.nio.channels.DatagramChannel#send]] to deliver datagrams, and as a consequence if a
 * security manager  has been installed then for each datagram it will verify if the target address and port number are
 * permitted. If this performance overhead is undesirable use the connection style Udp extension.
 *
 * == Bind and send ==
 *
 * To bind and listen to a local address, a [[akka.io.UdpFF..Bind]] command must be sent to this actor. If the binding
 * was successful, the sender of the [[akka.io.UdpFF.Bind]] will be notified with a [[akka.io.UdpFF.Bound]]
 * message. The sender of the [[akka.io.UdpFF.Bound]] message is the Listener actor (an internal actor responsible for
 * listening to server events). To unbind the port an [[akka.io.Tcp.Unbind]] message must be sent to the Listener actor.
 *
 * If the bind request is rejected because the Udp system is not able to register more channels (see the nr-of-selectors
 * and max-channels configuration options in the akka.io.udpFF section of the configuration) the sender will be notified
 * with a [[akka.io.UdpFF.CommandFailed]] message. This message contains the original command for reference.
 *
 * The handler provided in the [[akka.io.UdpFF.Bind]] message will receive inbound datagrams to the bound port
 * wrapped in [[akka.io.UdpFF.Received]] messages which contain the payload of the datagram and the sender address.
 *
 * UDP datagrams can be sent by sending [[akka.io.UdpFF.Send]] messages to the Listener actor. The sender port of the
 * outbound datagram will be the port to which the Listener is bound.
 *
 * == Simple send ==
 *
 * UdpFF provides a simple method of sending UDP datagrams if no reply is expected. To acquire the Sender actor
 * a SimpleSend message has to be sent to the manager. The sender of the command will be notified by a SimpleSendReady
 * message that the service is available. UDP datagrams can be sent by sending [[akka.io.UdpFF.Send]] messages to the
 * sender of SimpleSendReady. All the datagrams will contain an ephemeral local port as sender and answers will be
 * discarded.
 *
 */
private[io] class UdpFFManager(udpFF: UdpFFExt) extends SelectorBasedManager(udpFF.settings, udpFF.settings.NrOfSelectors) {

  // FIXME: fix close overs
  lazy val anonymousSender: ActorRef = context.actorOf(
    props = Props(new UdpFFSender(udpFF, selectorPool)),
    name = "simplesend")

  def receive = kickStartReceive {
    case Bind(handler, endpoint, options) ⇒
      val commander = sender
      Props(new UdpFFListener(selectorPool, handler, endpoint, commander, udpFF, options))
  } orElse {
    case SimpleSender ⇒ anonymousSender forward SimpleSender
  }

}
