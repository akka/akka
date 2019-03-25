/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.discovery.dns

import java.net.InetAddress

import scala.concurrent.duration._

import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import akka.event.Logging
import akka.io.{ Dns, IO }
import akka.pattern.ask
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

import akka.discovery._
import akka.io.dns.DnsProtocol.{ Ip, Srv }
import akka.io.dns.{ AAAARecord, ARecord, DnsProtocol, SRVRecord }
import scala.collection.{ immutable => im }
import scala.util.Failure
import scala.util.Success

import akka.io.dns.internal.AsyncDnsCache
import akka.io.dns.internal.AsyncDnsManager
import akka.util.OptionVal
import akka.util.Timeout

/**
 * INTERNAL API
 */
@InternalApi
private object DnsServiceDiscovery {
  def srvRecordsToResolved(srvRequest: String, resolved: DnsProtocol.Resolved): Resolved = {
    val ips: Map[String, im.Seq[InetAddress]] =
      resolved.additionalRecords.foldLeft(Map.empty[String, im.Seq[InetAddress]]) {
        case (acc, a: ARecord) =>
          acc.updated(a.name, a.ip +: acc.getOrElse(a.name, Nil))
        case (acc, a: AAAARecord) =>
          acc.updated(a.name, a.ip +: acc.getOrElse(a.name, Nil))
        case (acc, _) =>
          acc
      }

    val addresses = resolved.records.flatMap {
      case srv: SRVRecord =>
        val addresses = ips.getOrElse(srv.target, Nil).map(ip => ResolvedTarget(srv.target, Some(srv.port), Some(ip)))
        if (addresses.isEmpty) {
          im.Seq(ResolvedTarget(srv.target, Some(srv.port), None))
        } else {
          addresses
        }
      case _ => im.Seq.empty[ResolvedTarget]
    }

    Resolved(srvRequest, addresses)
  }

}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] class DnsServiceDiscovery(system: ExtendedActorSystem) extends ServiceDiscovery {

  import DnsServiceDiscovery._

  import ServiceDiscovery._

  private val log = Logging(system, getClass)
  private val dns = if (system.settings.config.getString("akka.io.dns.resolver") == "async-dns") {
    log.debug("using system resolver as it is set to async-dns")
    IO(Dns)(system)
  } else {
    log.debug("system resolver is not async-dns. Loading isolated resolver")
    Dns(system).loadAsyncDns("SD-DNS")
  }

  // updated from ask AsyncDnsManager.GetCache, but doesn't have to volatile since will still work when unset
  // (eventually visible)
  private var asyncDnsCache: OptionVal[AsyncDnsCache] = OptionVal.None

  import system.dispatcher

  dns.ask(AsyncDnsManager.GetCache)(Timeout(30.seconds)).onComplete {
    case Success(cache: AsyncDnsCache) =>
      asyncDnsCache = OptionVal.Some(cache)
    case Success(other) =>
      log.error("Expected AsyncDnsCache but got [{}]", other.getClass.getName)
    case Failure(e) =>
      log.error(e, "Couldn't retrieve DNS cache: {}")
  }

  private def cleanIpString(ipString: String): String =
    if (ipString.startsWith("/")) ipString.tail else ipString

  override def lookup(lookup: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] = {
    if (lookup.portName.isDefined && lookup.protocol.isDefined)
      lookupSrv(lookup, resolveTimeout)
    else
      lookupIp(lookup, resolveTimeout)
  }

  private def lookupSrv(lookup: Lookup, resolveTimeout: FiniteDuration) = {
    val srvRequest = s"_${lookup.portName.get}._${lookup.protocol.get}.${lookup.serviceName}"
    log.debug("Lookup [{}] translated to SRV query [{}] as contains portName and protocol", lookup, srvRequest)
    val mode = Srv

    def askResolve(): Future[Resolved] = {
      dns.ask(DnsProtocol.Resolve(srvRequest, mode))(resolveTimeout).map {
        case resolved: DnsProtocol.Resolved =>
          log.debug("{} lookup result: {}", mode, resolved)
          srvRecordsToResolved(srvRequest, resolved)
        case resolved =>
          log.warning("Resolved UNEXPECTED (resolving to Nil): {}", resolved.getClass)
          Resolved(srvRequest, Nil)
      }
    }

    asyncDnsCache match {
      case OptionVal.Some(cache) =>
        cache.get((srvRequest, mode)) match {
          case Some(resolved) =>
            log.debug("{} lookup cached: {}", mode, resolved)
            Future.successful(srvRecordsToResolved(srvRequest, resolved))
          case None =>
            askResolve()
        }
      case OptionVal.None =>
        askResolve()

    }
  }

  private def lookupIp(lookup: Lookup, resolveTimeout: FiniteDuration) = {
    log.debug("Lookup[{}] translated to A/AAAA lookup as does not have portName and protocol", lookup)
    val mode = Ip()

    def ipRecordsToResolved(resolved: DnsProtocol.Resolved): Resolved = {
      val addresses = resolved.records.collect {
        case a: ARecord    => ResolvedTarget(cleanIpString(a.ip.getHostAddress), None, Some(a.ip))
        case a: AAAARecord => ResolvedTarget(cleanIpString(a.ip.getHostAddress), None, Some(a.ip))
      }
      Resolved(lookup.serviceName, addresses)
    }

    def askResolve(): Future[Resolved] = {
      dns.ask(DnsProtocol.Resolve(lookup.serviceName, mode))(resolveTimeout).map {
        case resolved: DnsProtocol.Resolved =>
          log.debug("{} lookup result: {}", mode, resolved)
          ipRecordsToResolved(resolved)
        case resolved =>
          log.warning("Resolved UNEXPECTED (resolving to Nil): {}", resolved.getClass)
          Resolved(lookup.serviceName, Nil)

      }
    }

    asyncDnsCache match {
      case OptionVal.Some(cache) =>
        cache.get((lookup.serviceName, mode)) match {
          case Some(resolved) =>
            log.debug("{} lookup cached: {}", mode, resolved)
            Future.successful(ipRecordsToResolved(resolved))
          case None =>
            askResolve()
        }
      case OptionVal.None =>
        askResolve()

    }

  }
}
