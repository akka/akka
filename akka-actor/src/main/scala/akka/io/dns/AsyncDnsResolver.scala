/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import akka.actor.{ Actor, ActorLogging }
import akka.io.SimpleDnsCache
import akka.io.dns.protocol.DnsRecordType
import com.typesafe.config.Config
import akka.pattern.pipe

import scala.concurrent.Future

final class AsyncDnsResolver(cache: SimpleDnsCache, config: Config) extends Actor with ActorLogging {
  implicit val ec = context.dispatcher

  override def receive: Receive = {
    case m: DnsProtocol.Protocol ⇒ onReceive(m)
  }

  def onReceive(m: DnsProtocol.Protocol): Unit = m match {
    case DnsProtocol.Resolve(name, mode) ⇒
      resolve(name, mode) pipeTo sender()
  }

  def resolve(str: String, types: Set[DnsRecordType]): Future[DnsProtocol.Resolved] = {
    ???
  }
}

object AsyncDnsResolver {

  /** SRV lookups start with `_` so we use this heurestic to issue an SRV lookup TODO DO WE REALLY */
  private[dns] def isLikelySrvLookup(name: String): Boolean =
    name.startsWith("_")
}
