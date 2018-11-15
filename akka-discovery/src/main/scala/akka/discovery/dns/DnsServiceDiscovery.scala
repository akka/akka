/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.discovery.dns

import java.net.InetAddress

import akka.AkkaVersion
import akka.actor.ActorSystem
import akka.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import akka.event.Logging
import akka.io.{ Dns, IO }
import akka.pattern.ask

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import akka.discovery._
import akka.io.dns.DnsProtocol.{ Ip, Srv }
import akka.io.dns.{ AAAARecord, ARecord, DnsProtocol, SRVRecord }

import scala.collection.{ immutable ⇒ im }

object DnsServiceDiscovery {
  def srvRecordsToResolved(srvRequest: String, resolved: DnsProtocol.Resolved): Resolved = {
    val ips: Map[String, im.Seq[InetAddress]] =
      resolved.additionalRecords.foldLeft(Map.empty[String, im.Seq[InetAddress]]) {
        case (acc, a: ARecord) ⇒
          acc.updated(a.name, a.ip +: acc.getOrElse(a.name, Nil))
        case (acc, a: AAAARecord) ⇒
          acc.updated(a.name, a.ip +: acc.getOrElse(a.name, Nil))
        case (acc, _) ⇒
          acc
      }

    val addresses = resolved.records.flatMap {
      case srv: SRVRecord ⇒
        val addresses = ips.getOrElse(srv.target, Nil).map(ip ⇒ ResolvedTarget(srv.target, Some(srv.port), Some(ip)))
        if (addresses.isEmpty) {
          im.Seq(ResolvedTarget(srv.target, Some(srv.port), None))
        } else {
          addresses
        }
      case other ⇒ im.Seq.empty[ResolvedTarget]
    }

    Resolved(srvRequest, addresses)
  }

}

/**
 * Looks for A records for a given service.
 */
class DnsServiceDiscovery(system: ActorSystem) extends ServiceDiscovery {

  import DnsServiceDiscovery._

  // TODO: Switch to creating an async resolver directly
  // Required for async dns, 15 for additional records
  require(
    system.settings.config.getString("akka.io.dns.resolver") == "async-dns",
    "Akka discovery DNS requires akka.io.dns.resolver to be set to async-dns")

  import ServiceDiscovery._

  private val log = Logging(system, getClass)
  private val dns = IO(Dns)(system)

  import system.dispatcher

  private def cleanIpString(ipString: String): String =
    if (ipString.startsWith("/")) ipString.tail else ipString

  override def lookup(lookup: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] = {
    lookup match {
      case Lookup(name, Some(portName), Some(protocol)) ⇒
        val srvRequest = s"_$portName._$protocol.$name"
        log.debug("Lookup [{}] translated to SRV query [{}] as contains portName and protocol", lookup, srvRequest)
        dns.ask(DnsProtocol.Resolve(srvRequest, Srv))(resolveTimeout).map {
          case resolved: DnsProtocol.Resolved ⇒
            log.debug("Resolved Dns.Resolved: {}", resolved)
            srvRecordsToResolved(srvRequest, resolved)
          case resolved ⇒
            log.warning("Resolved UNEXPECTED (resolving to Nil): {}", resolved.getClass)
            Resolved(srvRequest, Nil)
        }
      case _ ⇒
        log.debug("Lookup[{}] translated to A/AAAA lookup as does not have portName and protocol", lookup)
        dns.ask(DnsProtocol.Resolve(lookup.serviceName, Ip()))(resolveTimeout).map {
          case resolved: DnsProtocol.Resolved ⇒
            log.debug("Resolved Dns.Resolved: {}", resolved)
            val addresses = resolved.records.collect {
              case a: ARecord    ⇒ ResolvedTarget(cleanIpString(a.ip.getHostAddress), None, Some(a.ip))
              case a: AAAARecord ⇒ ResolvedTarget(cleanIpString(a.ip.getHostAddress), None, Some(a.ip))
            }
            Resolved(lookup.serviceName, addresses)
          case resolved ⇒
            log.warning("Resolved UNEXPECTED (resolving to Nil): {}", resolved.getClass)
            Resolved(lookup.serviceName, Nil)

        }
    }
  }

}
