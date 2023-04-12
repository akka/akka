/*
 * Copyright (C) 2018-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.{ Inet4Address, Inet6Address, InetAddress, InetSocketAddress }

import scala.collection.immutable
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

import akka.actor.{ Actor, ActorLogging, ActorRef, ActorRefFactory, Status }
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.io.SimpleDnsCache
import akka.io.dns._
import akka.io.dns.CachePolicy.{ Never, Ttl }
import akka.io.dns.DnsProtocol.{ Ip, RequestType, Srv }
import akka.io.dns.internal.DnsClient._
import akka.pattern.ask
import akka.pattern.AskTimeoutException
import akka.util.{ Helpers, Timeout }
import akka.util.PrettyDuration._

/**
 * INTERNAL API
 */
@InternalApi
private[io] final class AsyncDnsResolver(
    settings: DnsSettings,
    cache: SimpleDnsCache,
    clientFactory: (ActorRefFactory, List[InetSocketAddress]) => List[ActorRef])
    extends Actor
    with ActorLogging {

  import AsyncDnsResolver._

  // avoid ever looking up localhost by pre-populating cache
  {
    val loopback = InetAddress.getLoopbackAddress
    val (ipv4Address, ipv6Address) = loopback match {
      case ipv6: Inet6Address => (InetAddress.getByName("127.0.0.1"), ipv6)
      case ipv4: Inet4Address => (ipv4, InetAddress.getByName("::1"))
      case unknown            => throw new IllegalArgumentException(s"Loopback address was [$unknown]")
    }
    cache.put(
      "localhost" -> Ip(),
      DnsProtocol.Resolved("localhost", ARecord("localhost", Ttl.effectivelyForever, loopback) :: Nil),
      Ttl.effectivelyForever)
    cache.put(
      "localhost" -> Ip(ipv6 = false, ipv4 = true),
      DnsProtocol.Resolved("localhost", ARecord("localhost", Ttl.effectivelyForever, ipv4Address) :: Nil),
      Ttl.effectivelyForever)
    cache.put(
      "localhost" -> Ip(ipv6 = true, ipv4 = false),
      DnsProtocol.Resolved("localhost", ARecord("localhost", Ttl.effectivelyForever, ipv6Address) :: Nil),
      Ttl.effectivelyForever)

  }

  // For ask to DNS Client
  implicit val timeout: Timeout = Timeout(settings.ResolveTimeout)

  val nameServers = settings.NameServers

  val positiveCachePolicy = settings.PositiveCachePolicy
  val negativeCachePolicy = settings.NegativeCachePolicy
  log.debug(
    "Using name servers [{}] and search domains [{}] with ndots={}",
    nameServers,
    settings.SearchDomains,
    settings.NDots)

  private var requestId: Short = 0

  private def nextId(): Short = {
    requestId = (requestId + 1).toShort
    requestId
  }

  private val resolvers: List[ActorRef] = clientFactory(context, nameServers)

  // only supports DnsProtocol, not the deprecated Dns protocol
  // AsyncDnsManager converts between the protocols to support the deprecated protocol
  override def receive: Receive = {
    case DnsProtocol.Resolve(name, mode) =>
      cache.get((name, mode)) match {
        case Some(resolved) =>
          log.debug("{} cached {}", mode, resolved)
          sender() ! resolved
        case None =>
          val replyTo = sender()
          resolveWithResolvers(name, mode, resolvers).onComplete {
            _ match {
              case Success(resolved) =>
                if (resolved.records.nonEmpty) {
                  val minTtl = (positiveCachePolicy +: resolved.records.map(_.ttl)).min
                  cache.put((name, mode), resolved, minTtl) // thread-safe structure, safe in callback
                } else if (negativeCachePolicy != Never) {
                  cache.put((name, mode), resolved, negativeCachePolicy) // thread-safe structure, safe in callback
                }

                log.debug("{} resolved {}", mode, resolved)
                replyTo ! resolved

              case Failure(f) =>
                replyTo ! Status.Failure(f)
            }
          }(ExecutionContexts.parasitic)
      }

    case Internal.Resolve(name, requestType, resolver, promise) =>
      resolvePromise(name, requestType, resolver, promise)
  }

  private def resolveWithResolvers(
      name: String,
      requestType: RequestType,
      resolvers: List[ActorRef]): Future[DnsProtocol.Resolved] =
    if (isInetAddress(name)) {
      Future.fromTry {
        Try {
          val address = InetAddress.getByName(name) // only checks validity, since known to be IP address
          val record = address match {
            case _: Inet4Address           => ARecord(name, Ttl.effectivelyForever, address)
            case ipv6address: Inet6Address => AAAARecord(name, Ttl.effectivelyForever, ipv6address)
            case unexpected                => throw new IllegalArgumentException(s"Unexpected address: $unexpected")
          }
          DnsProtocol.Resolved(name, record :: Nil)
        }
      }
    } else {
      resolvers match {
        case Nil =>
          Future.failed(ResolveFailedException(s"Failed to resolve $name with nameservers: $nameServers"))
        case head :: tail =>
          resolveWithSearch(name, requestType, head, settings, self).recoverWith {
            case NonFatal(t) =>
              t match {
                case _: AskTimeoutException =>
                  log.info("Resolve of {} timed out after {}. Trying next name server", name, timeout.duration.pretty)
                case _ =>
                  log.info("Resolve of {} failed. Trying next name server {}", name, t.getMessage)
              }
              resolveWithResolvers(name, requestType, tail)
          }(ExecutionContexts.parasitic)
      }
    }

  private def resolvePromise(
      name: String,
      requestType: RequestType,
      resolver: ActorRef,
      promise: Promise[DnsProtocol.Resolved]): Unit = {
    log.debug("Attempting to resolve {} with {}", name, resolver)
    val caseFoldedName = Helpers.toRootLowerCase(name)
    requestType match {
      case Ip(ipv4, ipv6) =>
        val ipv4Recs =
          if (ipv4) sendQuestion(resolver, Question4(nextId(), caseFoldedName))
          else Empty

        val ipv6Recs =
          if (ipv6) sendQuestion(resolver, Question6(nextId(), caseFoldedName))
          else Empty

        promise.completeWith(ipv4Recs.flatMap { v4 =>
          ipv6Recs.map { v6 =>
            DnsProtocol.Resolved(name, v4.rrs ++ v6.rrs, v4.additionalRecs ++ v6.additionalRecs)
          }(ExecutionContexts.parasitic)
        }(ExecutionContexts.parasitic))

      case Srv =>
        promise.completeWith(sendQuestion(resolver, SrvQuestion(nextId(), caseFoldedName)).map { answer =>
          DnsProtocol.Resolved(name, answer.rrs, answer.additionalRecs)
        }(ExecutionContexts.parasitic))
    }
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object AsyncDnsResolver {

  private val ipv4Address =
    """^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$""".r

  private val ipv6Address =
    """^\s*((([0-9A-Fa-f]{1,4}:){7}([0-9A-Fa-f]{1,4}|:))|(([0-9A-Fa-f]{1,4}:){6}(:[0-9A-Fa-f]{1,4}|((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){5}(((:[0-9A-Fa-f]{1,4}){1,2})|:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){4}(((:[0-9A-Fa-f]{1,4}){1,3})|((:[0-9A-Fa-f]{1,4})?:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){3}(((:[0-9A-Fa-f]{1,4}){1,4})|((:[0-9A-Fa-f]{1,4}){0,2}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){2}(((:[0-9A-Fa-f]{1,4}){1,5})|((:[0-9A-Fa-f]{1,4}){0,3}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){1}(((:[0-9A-Fa-f]{1,4}){1,6})|((:[0-9A-Fa-f]{1,4}){0,4}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(:(((:[0-9A-Fa-f]{1,4}){1,7})|((:[0-9A-Fa-f]{1,4}){0,5}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:)))(%.+)?\s*$""".r

  private[akka] def isIpv4Address(name: String): Boolean =
    ipv4Address.findAllMatchIn(name).nonEmpty

  private[akka] def isIpv6Address(name: String): Boolean =
    ipv6Address.findAllMatchIn(name).nonEmpty

  private def isInetAddress(name: String): Boolean =
    isIpv4Address(name) || isIpv6Address(name)

  private val Empty =
    Future.successful(Answer(-1, immutable.Seq.empty[ResourceRecord], immutable.Seq.empty[ResourceRecord]))

  case class ResolveFailedException(msg: String) extends Exception(msg)

  // Internal commands to avoid calling private methods in future callbacks
  private[akka] object Internal {
    case class Resolve(
        name: String,
        requestType: RequestType,
        resolver: ActorRef,
        promise: Promise[DnsProtocol.Resolved])
  }

  // These methods are not in the class so that we can be sure they don't access actor state
  private def resolveWithSearch(
      name: String,
      requestType: RequestType,
      resolver: ActorRef,
      settings: DnsSettings,
      self: ActorRef): Future[DnsProtocol.Resolved] = {
    if (settings.SearchDomains.nonEmpty) {
      val nameWithSearch = settings.SearchDomains.map(sd => name + "." + sd)
      // ndots is a heuristic used to try and work out whether the name passed in is a fully qualified domain name,
      // or a name relative to one of the search names. The idea is to prevent the cost of doing a lookup that is
      // obviously not going to resolve. So, if a host has less than ndots dots in it, then we don't try and resolve it,
      // instead, we go directly to the search domains, or at least that's what the man page for resolv.conf says. In
      // practice, Linux appears to implement something slightly different, if the name being searched contains less
      // than ndots dots, then it should be searched last, rather than first. This means if the heuristic wrongly
      // identifies a domain as being relative to the search domains, it will still be looked up if it doesn't resolve
      // at any of the search domains, albeit with the latency of having to have done all the searches first.
      val toResolve = if (name.count(_ == '.') >= settings.NDots) {
        name :: nameWithSearch
      } else {
        nameWithSearch :+ name
      }
      resolveFirst(toResolve, requestType, resolver, self)
    } else {
      val selfCmd = Internal.Resolve(name, requestType, resolver, Promise())
      self ! selfCmd
      selfCmd.promise.future
    }
  }

  private def resolveFirst(
      searchNames: List[String],
      requestType: RequestType,
      resolver: ActorRef,
      self: ActorRef): Future[DnsProtocol.Resolved] =
    searchNames.headOption match {
      case Some(searchName) =>
        val selfCmd = Internal.Resolve(searchName, requestType, resolver, Promise())
        self ! selfCmd

        if (searchNames.tail.isEmpty) {
          selfCmd.promise.future
        } else {
          selfCmd.promise.future.flatMap { resolved =>
            if (resolved.records.isEmpty) resolveFirst(searchNames.tail, requestType, resolver, self)
            else selfCmd.promise.future
          }(ExecutionContexts.parasitic)
        }

      case None =>
        // can't happen
        Future.failed(new IllegalStateException("Failed to 'resolveFirst': 'searchNames' must not be empty"))
    }

  private def sendQuestion(resolver: ActorRef, message: DnsQuestion)(implicit timeout: Timeout): Future[Answer] = {
    val result = (resolver ? message).mapTo[Answer]
    result.failed.foreach { _ =>
      resolver ! DropRequest(message.id)
    }(ExecutionContexts.parasitic)
    result
  }
}
