/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.{ Inet4Address, Inet6Address, InetAddress, InetSocketAddress }

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorRefFactory }
import akka.annotation.InternalApi
import akka.io.dns.CachePolicy.Ttl
import akka.io.dns.DnsProtocol.{ Ip, RequestType, Srv }
import akka.io.dns.internal.DnsClient._
import akka.io.dns._
import akka.pattern.{ ask, pipe }
import akka.util.{ Helpers, Timeout }

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Try
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
    case DnsProtocol.Resolve(name, mode) ⇒
      resolve(name, mode, resolvers) pipeTo sender()
  }

  private def resolve(name: String, requestType: RequestType, resolvers: List[ActorRef]): Future[DnsProtocol.Resolved] =
    if (isInetAddress(name)) {
      Future.fromTry {
        Try {
          val address = InetAddress.getByName(name) // only checks validity, since known to be IP address
          val record = address match {
            case _: Inet4Address           ⇒ ARecord(name, Ttl.effectivelyForever, address)
            case ipv6address: Inet6Address ⇒ AAAARecord(name, Ttl.effectivelyForever, ipv6address)
          }
          DnsProtocol.Resolved(name, record :: Nil)
        }
      }
    } else {
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

  private def sendQuestion(resolver: ActorRef, message: DnsQuestion): Future[Answer] = {
    val result = (resolver ? message).mapTo[Answer]
    result.failed.foreach { _ ⇒ resolver ! DropRequest(message.id) }
    result
  }

  private def resolve(name: String, requestType: RequestType, resolver: ActorRef): Future[DnsProtocol.Resolved] = {
    log.debug("Attempting to resolve {} with {}", name, resolver)
    val caseFoldedName = Helpers.toRootLowerCase(name)
    requestType match {
      case Ip(ipv4, ipv6) ⇒
        val ipv4Recs: Future[Answer] = if (ipv4)
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
          if (ipv4Records.rrs.nonEmpty) {
            val minTtl4 = ipv4Records.rrs.map(_.ttl).min
            cache.put((name, Ipv4Type), ipv4Records, minTtl4)
          }
          ipv6Recs.map(ipv6Records ⇒ {
            if (ipv6Records.rrs.nonEmpty) {
              val minTtl6 = ipv6Records.rrs.map(_.ttl).min
              cache.put((name, Ipv6Type), ipv6Records, minTtl6)
            }
            ipv4Records.rrs ++ ipv6Records.rrs
          }).map(recs ⇒ DnsProtocol.Resolved(name, recs))
        })

      case Srv ⇒
        cache.get((name, SrvType)) match {
          case Some(r) ⇒
            Future.successful(DnsProtocol.Resolved(name, r.rrs, r.additionalRecs))
          case None ⇒
            sendQuestion(resolver, SrvQuestion(nextId(), caseFoldedName))
              .map(answer ⇒ {
                if (answer.rrs.nonEmpty) {
                  val minTtl = answer.rrs.map(_.ttl).min
                  cache.put((name, SrvType), answer, minTtl)
                }
                DnsProtocol.Resolved(name, answer.rrs, answer.additionalRecs)
              })
        }

    }
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

  private val Empty = Future.successful(Answer(-1, immutable.Seq.empty[ResourceRecord], immutable.Seq.empty[ResourceRecord]))

  sealed trait QueryType
  final case object Ipv4Type extends QueryType
  final case object Ipv6Type extends QueryType
  final case object SrvType extends QueryType

  case class ResolveFailedException(msg: String) extends Exception(msg)
}
