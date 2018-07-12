/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

import akka.actor.{ Actor, ActorLogging, ActorRefFactory, Deploy, Props, Timers }
import akka.annotation.InternalApi
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.io.dns.{ DnsProtocol, DnsSettings }
import akka.io.dns.internal.AsyncDnsManager.CacheCleanup
import akka.io.{ Dns, DnsExt, PeriodicCacheCleanup }
import akka.routing.FromConfig

import scala.concurrent.duration.Duration

/**
 * INTERNAL API
 */
@InternalApi
private[io] object AsyncDnsManager {
  private case object CacheCleanup
}

/**
 * INTERNAL API
 */
@InternalApi
private[io] final class AsyncDnsManager(val ext: DnsExt) extends Actor
  with RequiresMessageQueue[UnboundedMessageQueueSemantics] with ActorLogging with Timers {

  implicit val ec = context.dispatcher

  private var oldProtocolWarningLoggedTimes = 0

  val settings = new DnsSettings(ext.system, ext.Settings.ResolverConfig)

  private val resolver = {
    val props: Props = FromConfig.props(Props(ext.provider.actorClass, settings, ext.cache, (factory: ActorRefFactory, dns: List[InetSocketAddress]) ⇒ {
      dns.map(ns ⇒ factory.actorOf(Props(new DnsClient(ns))))
    }).withDeploy(Deploy.local).withDispatcher(ext.Settings.Dispatcher))
    context.actorOf(props, ext.Settings.Resolver)
  }

  private val cacheCleanup = ext.cache match {
    case cleanup: PeriodicCacheCleanup ⇒ Some(cleanup)
    case _                             ⇒ None
  }

  override def preStart(): Unit = {
    cacheCleanup.foreach { _ ⇒
      val interval = Duration(ext.Settings.ResolverConfig.getDuration("cache-cleanup-interval", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
      timers.startPeriodicTimer(CacheCleanup, CacheCleanup, interval)
    }
  }

  override def receive: Receive = {
    case r: DnsProtocol.Resolve ⇒
      log.debug("Resolution request for {} {} from {}", r.name, r.requestType, sender())
      resolver.forward(r)

    case Dns.Resolve(name) ⇒
      // adapt legacy protocol to new protocol
      log.debug("Resolution request for {} from {}", name, sender())
      warnAboutOldProtocolUse(name)
      val adapted = DnsProtocol.Resolve(name)
      resolver.forward(adapted)

    case CacheCleanup ⇒
      cacheCleanup.foreach(_.cleanup())
  }

  private def warnAboutOldProtocolUse(name: String): Unit = {
    val warnAtMostTimes = 10
    if (oldProtocolWarningLoggedTimes < warnAtMostTimes) {
      oldProtocolWarningLoggedTimes += 1
      log.warning("Received Dns.Resolve({}) message while Async DNS resolver active. Please use the new API [akka.io.dns.DnsProtocol] to issue resolve requests. " +
        "(This warning will be logged at most {} times)", name, warnAtMostTimes)
    }
  }
}

