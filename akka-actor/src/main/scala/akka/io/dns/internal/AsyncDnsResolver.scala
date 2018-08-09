/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.{ InetAddress, InetSocketAddress }
import java.nio.charset.StandardCharsets

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorRefFactory, Props }
import akka.annotation.InternalApi
import akka.io.dns.DnsProtocol.{ Ip, RequestType, Srv }
import akka.io.dns.internal.DnsClient._
import akka.io.dns.{ ARecord, DnsProtocol, DnsSettings, ResourceRecord }
import akka.pattern.{ ask, pipe }
import akka.util.{ Helpers, Timeout }

import scala.collection.immutable.Seq
import scala.collection.{ breakOut, immutable }
import scala.concurrent.Future
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi
private[io] final class AsyncDnsResolver(
  settings:      DnsSettings,
  cache:         AsyncDnsCache,
  clientFactory: (ActorRefFactory, List[InetSocketAddress]) ⇒ List[ActorRef]) extends Actor with ActorLogging {

  import AsyncDnsResolver._

  implicit val ec = context.dispatcher

  // For ask to DNS Client
  implicit val timeout = Timeout(settings.ResolveTimeout)

  val nameServers = settings.NameServers

  log.debug("Using name servers [{}]", nameServers)

  private var requestId: Short = 0
  private def nextId(): Short = {
    requestId = (requestId + 1).toShort
    requestId
  }

  private val resolvers: List[ActorRef] = clientFactory(context, nameServers)

  override def receive: Receive = {
    case DnsProtocol.Resolve(name, _) if isInetAddress(name) ⇒
      log.warning("Tried to resolve ip [{}], assuming resolved.", name)
      alreadyResolvedIp(name) pipeTo sender()
    case DnsProtocol.Resolve(name, mode) ⇒
      resolve(name, mode, resolvers) pipeTo sender()
  }

  private def resolve(name: String, requestType: RequestType, resolvers: List[ActorRef]): Future[DnsProtocol.Resolved] = {
    resolvers match {
      case Nil ⇒
        Future.failed(ResolveFailedException(s"Timed out resolving $name with nameservers: $nameServers"))
      case head :: tail ⇒ resolve(name, requestType, head).recoverWith {
        case NonFatal(t) ⇒
          log.error(t, "Resolve failed. Trying next name server")
          resolve(name, requestType, tail)
      }
    }
  }

  private def alreadyResolvedIp(knownToBeIp: String): Future[DnsProtocol.Resolved] = {
    if (isInetAddress(knownToBeIp))
      Future {
        val address = InetAddress.getByName(knownToBeIp) // only checks validity, since known to be IP address
        val record = ARecord(knownToBeIp, Int.MaxValue, address)
        DnsProtocol.Resolved(knownToBeIp, record :: Nil)
      }
    else
      Future.failed(new IllegalArgumentException("Attempted to emit Resolved for known-to-be IP address, " +
        s"yet argument was not an IP address, was: ${knownToBeIp}"))
  }

  private def sendQuestion(resolver: ActorRef, message: DnsQuestion): Future[Seq[ResourceRecord]] = {
    val result = (resolver ? message).mapTo[Answer].map(_.rrs)
    result.onFailure {
      case NonFatal(_) ⇒ resolver ! DropRequest(message.id)
    }
    result
  }

  private def resolve(name: String, requestType: RequestType, resolver: ActorRef): Future[DnsProtocol.Resolved] = {
    log.debug("Attempting to resolve {} with {}", name, resolver)
    val caseFoldedName = Helpers.toRootLowerCase(name)
    val recs: Future[Seq[ResourceRecord]] = requestType match {
      case Ip(ipv4, ipv6) ⇒
        val ipv4Recs = if (ipv4)
          cache.get((name, Ipv4Type)) match {
            case Some(r) ⇒
              log.debug("Ipv4 cached {}", r)
              Future.successful(r)
            case None ⇒
              sendQuestion(resolver, Question4(nextId(), caseFoldedName))
          }
        else
          Empty

        val ipv6Recs = if (ipv6)
          cache.get((name, Ipv6Type)) match {
            case Some(r) ⇒
              log.debug("Ipv6 cached {}", r)
              Future.successful(r)
            case None ⇒
              sendQuestion(resolver, Question6(nextId(), caseFoldedName))
          }
        else
          Empty

        ipv4Recs.flatMap(ipv4Records ⇒ {
          // TODO, do we want config to specify a max for this?
          if (ipv4Records.nonEmpty) {
            val minTtl4 = ipv4Records.minBy(_.ttl).ttl
            cache.put((name, Ipv4Type), ipv4Records, minTtl4)
          }
          ipv6Recs.map(ipv6Records ⇒ {
            if (ipv6Records.nonEmpty) {
              val minTtl6 = ipv6Records.minBy(_.ttl).ttl
              cache.put((name, Ipv6Type), ipv6Records, minTtl6)
            }
            ipv4Records ++ ipv6Records
          })
        })
      case Srv ⇒
        cache.get((name, Ipv4Type)) match {
          case Some(r) ⇒ Future.successful(r)
          case None ⇒
            sendQuestion(resolver, SrvQuestion(nextId(), caseFoldedName))
        }

    }

    recs.map(result ⇒ DnsProtocol.Resolved(name, result))
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[io] object AsyncDnsResolver {

  private val ipv4Address =
    """^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$""".r

  private val ipv6Address =
    """^\s*((([0-9A-Fa-f]{1,4}:){7}([0-9A-Fa-f]{1,4}|:))|(([0-9A-Fa-f]{1,4}:){6}(:[0-9A-Fa-f]{1,4}|((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){5}(((:[0-9A-Fa-f]{1,4}){1,2})|:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){4}(((:[0-9A-Fa-f]{1,4}){1,3})|((:[0-9A-Fa-f]{1,4})?:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){3}(((:[0-9A-Fa-f]{1,4}){1,4})|((:[0-9A-Fa-f]{1,4}){0,2}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){2}(((:[0-9A-Fa-f]{1,4}){1,5})|((:[0-9A-Fa-f]{1,4}){0,3}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){1}(((:[0-9A-Fa-f]{1,4}){1,6})|((:[0-9A-Fa-f]{1,4}){0,4}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(:(((:[0-9A-Fa-f]{1,4}){1,7})|((:[0-9A-Fa-f]{1,4}){0,5}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:)))(%.+)?\s*$""".r

  private def isInetAddress(name: String): Boolean =
    ipv4Address.findAllMatchIn(name).nonEmpty ||
      ipv6Address.findAllMatchIn(name).nonEmpty

  private val Empty = Future.successful(immutable.Seq.empty[ResourceRecord])

  sealed trait QueryType
  final case object Ipv4Type extends QueryType
  final case object Ipv6Type extends QueryType
  final case object SrvType extends QueryType

  case class ResolveFailedException(msg: String) extends Exception(msg)
}
