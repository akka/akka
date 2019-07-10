/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.util.concurrent.TimeUnit

import akka.actor.{ Actor, ActorLogging, Deploy, Props }
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.event.Logging
import akka.routing.FromConfig

import scala.concurrent.duration.Duration

class SimpleDnsManager(val ext: DnsExt)
    extends Actor
    with RequiresMessageQueue[UnboundedMessageQueueSemantics]
    with ActorLogging {

  import context._

  private val resolver = actorOf(
    FromConfig.props(
      Props(ext.provider.actorClass, ext.cache, ext.Settings.ResolverConfig)
        .withDeploy(Deploy.local)
        .withDispatcher(ext.Settings.Dispatcher)),
    ext.Settings.Resolver)

  private val inetDnsEnabled = ext.provider.actorClass == classOf[InetAddressDnsResolver]

  private val cacheCleanup = ext.cache match {
    case cleanup: PeriodicCacheCleanup => Some(cleanup)
    case _                             => None
  }

  private val cleanupTimer = cacheCleanup.map { _ =>
    val interval = Duration(
      ext.Settings.ResolverConfig.getDuration("cache-cleanup-interval", TimeUnit.MILLISECONDS),
      TimeUnit.MILLISECONDS)
    system.scheduler.scheduleWithFixedDelay(interval, interval, self, SimpleDnsManager.CacheCleanup)
  }

  override def receive: Receive = {
    case r @ Dns.Resolve(name) =>
      log.debug("Resolution request for {} from {}", name, sender())
      resolver.forward(r)

    case SimpleDnsManager.CacheCleanup =>
      cacheCleanup.foreach(_.cleanup())

    case m: dns.DnsProtocol.Resolve =>
      if (inetDnsEnabled) {
        log.error(
          "Message of [akka.io.dns.DnsProtocol.Protocol] received ({}) while inet-address dns was configured. Dropping DNS resolve request. " +
          "Only use [akka.io.dns.DnsProtocol] to create resolution requests for the Async DNS resolver (enabled by `akka.io.dns = async-dns`). " +
          "For the classic (now used) DNS resolver use [akka.io.Dns] messages.",
          Logging.simpleName(m))
      } else resolver.forward(m)
  }

  override def postStop(): Unit = {
    cleanupTimer.foreach(_.cancel())
  }
}

object SimpleDnsManager {
  private case object CacheCleanup
}
