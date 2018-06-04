/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns

import java.util.concurrent.TimeUnit

import akka.actor.{ Actor, ActorLogging, Deploy, Props }
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.io.{ Dns, DnsExt, PeriodicCacheCleanup }
import akka.routing.FromConfig

import scala.concurrent.duration.Duration

final class AsyncDnsManager(val ext: DnsExt) extends Actor with RequiresMessageQueue[UnboundedMessageQueueSemantics] with ActorLogging {
  import AsyncDnsManager._
  implicit val ec = context.dispatcher

  private var oldProtocolWarningLoggedTimes = 0

  private val resolver = {
    val props: Props = FromConfig.props(Props(ext.provider.actorClass, ext.cache, ext.Settings.ResolverConfig).withDeploy(Deploy.local).withDispatcher(ext.Settings.Dispatcher))
    context.actorOf(props, ext.Settings.Resolver)
  }
  private val cacheCleanup = ext.cache match {
    case cleanup: PeriodicCacheCleanup ⇒ Some(cleanup)
    case _                             ⇒ None
  }

  private val cleanupTimer = cacheCleanup map { _ ⇒
    val interval = Duration(ext.Settings.ResolverConfig.getDuration("cache-cleanup-interval", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
    context.system.scheduler.schedule(interval, interval, self, AsyncDnsManager.CacheCleanup)
  }

  override def receive = {
    case r: DnsProtocol.Resolve ⇒
      log.debug("Resolution request for {} {}from {}", r.name, r.mode, sender())
      resolver.forward(r)

    case r @ Dns.Resolve(name) ⇒
      // adapt legacy protocol to new protocol
      log.debug("Resolution request for {} from {}", name, sender())
      warnAboutOldProtocolUse(name)
      val adapted = DnsProtocol.resolve(name)
      resolver.forward(adapted)

    case CacheCleanup ⇒
      for (c ← cacheCleanup)
        c.cleanup()
  }

  private def warnAboutOldProtocolUse(name: String): Unit = {
    val warnAtMostTimes = 10
    if (oldProtocolWarningLoggedTimes < warnAtMostTimes) {
      oldProtocolWarningLoggedTimes += 1
      log.warning("Received Dns.Resolve({}) message while Async DNS resolver active. Please use the new API [akka.io.dns.DnsProtocol] to issue resolve requests. " +
        "(This warning will be logged at most {} times)", name, warnAtMostTimes)
    }
  }

  override def postStop(): Unit = {
    for (t ← cleanupTimer) t.cancel()
  }
}

object AsyncDnsManager {
  private case object CacheCleanup
}
