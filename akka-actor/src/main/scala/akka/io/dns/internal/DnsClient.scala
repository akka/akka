/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.{ InetAddress, InetSocketAddress }

import akka.actor.Status.Failure
import akka.actor.{ Actor, ActorLogging, ActorRef, NoSerializationVerificationNeeded, Stash }
import akka.annotation.InternalApi
import akka.io.dns.{ RecordClass, RecordType, ResourceRecord }
import akka.io.{ IO, Udp }

import scala.collection.{ immutable ⇒ im }
import scala.util.Try

/**
 * INTERNAL API
 */
@InternalApi private[akka] object DnsClient {
  sealed trait DnsQuestion {
    def id: Short
  }
  final case class SrvQuestion(id: Short, name: String) extends DnsQuestion
  final case class Question4(id: Short, name: String) extends DnsQuestion
  final case class Question6(id: Short, name: String) extends DnsQuestion
  final case class Answer(id: Short, rrs: im.Seq[ResourceRecord], additionalRecs: im.Seq[ResourceRecord] = Nil) extends NoSerializationVerificationNeeded
  final case class DropRequest(id: Short)
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class DnsClient(ns: InetSocketAddress) extends Actor with ActorLogging with Stash {

  import DnsClient._

  import context.system

  IO(Udp) ! Udp.Bind(self, new InetSocketAddress(InetAddress.getByAddress(Array.ofDim(4)), 0))

  var inflightRequests: Map[Short, ActorRef] = Map.empty

  def receive: Receive = {
    case Udp.Bound(local) ⇒
      log.debug(s"Bound to UDP address [{}]", local)
      context.become(ready(sender()))
      unstashAll()
    case _: Question4 ⇒
      stash()
    case _: Question6 ⇒
      stash()
    case _: SrvQuestion ⇒
      stash()
  }

  private def message(name: String, id: Short, recordType: RecordType): Message = {
    Message(id, MessageFlags(), im.Seq(Question(name, recordType, RecordClass.IN)))
  }

  def ready(socket: ActorRef): Receive = {
    case DropRequest(id) ⇒
      log.debug("Dropping request [{}]", id)
      inflightRequests -= id
    case Question4(id, name) ⇒
      log.debug("Resolving [{}] (A)", name)
      inflightRequests += (id -> sender())
      val msg = message(name, id, RecordType.A)
      log.debug(s"Message [{}] to [{}]: [{}]", id, ns, msg)
      socket ! Udp.Send(msg.write(), ns)

    case Question6(id, name) ⇒
      log.debug("Resolving [{}] (AAAA)", name)
      inflightRequests += (id -> sender())
      val msg = message(name, id, RecordType.AAAA)
      log.debug(s"Message to [{}]: [{}]", ns, msg)
      socket ! Udp.Send(msg.write(), ns)

    case SrvQuestion(id, name) ⇒
      log.debug("Resolving [{}] (SRV)", name)
      inflightRequests += (id -> sender())
      val msg = message(name, id, RecordType.SRV)
      log.debug(s"Message to {}: msg", ns, msg)
      socket ! Udp.Send(msg.write(), ns)

    case Udp.CommandFailed(cmd) ⇒
      log.debug("Command failed [{}]", cmd)
      cmd match {
        case send: Udp.Send ⇒
          // best effort, don't throw
          Try {
            val msg = Message.parse(send.payload)
            inflightRequests.get(msg.id).foreach { s ⇒
              s ! Failure(new RuntimeException("Send failed to nameserver"))
              inflightRequests -= msg.id
            }
          }
        case _ ⇒
          log.warning("Dns client failed to send {}", cmd)
      }
    case Udp.Received(data, remote) ⇒
      log.debug(s"Received message from [{}]: [{}]", remote, data)
      val msg = Message.parse(data)
      log.debug(s"Decoded: $msg")
      // TODO remove me when #25460 is implemented
      if (msg.flags.isTruncated) {
        log.warning("DNS response truncated and fallback to TCP is not yet implemented. See #25460")
      }
      val (recs, additionalRecs) = if (msg.flags.responseCode == ResponseCode.SUCCESS) (msg.answerRecs, msg.additionalRecs) else (Nil, Nil)
      val response = Answer(msg.id, recs, additionalRecs)
      inflightRequests.get(response.id) match {
        case Some(reply) ⇒
          reply ! response
          inflightRequests -= response.id
        case None ⇒
          log.debug("Client for id {} not found. Discarding response.", response.id)
      }

    case Udp.Unbind  ⇒ socket ! Udp.Unbind
    case Udp.Unbound ⇒ context.stop(self)
  }
}
