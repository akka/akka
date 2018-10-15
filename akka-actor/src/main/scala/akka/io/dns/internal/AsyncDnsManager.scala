/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io.dns.internal

import java.net.{ InetAddress, InetSocketAddress }
import java.util.concurrent.TimeUnit

import akka.actor.{ Actor, ActorLogging, ActorRefFactory, Deploy, Props, Timers }
import akka.annotation.InternalApi
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.io.dns.{ AAAARecord, ARecord, DnsProtocol, DnsSettings }
import akka.io.dns.internal.AsyncDnsManager.CacheCleanup
import akka.io.{ Dns, DnsExt, PeriodicCacheCleanup }
import akka.routing.FromConfig
import akka.util.Timeout

import scala.collection.immutable
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
  import akka.pattern.ask
  import akka.pattern.pipe

  implicit val ec = context.dispatcher

  private var oldProtocolWarningLoggedTimes = 0

  val settings = new DnsSettings(ext.system, ext.Settings.ResolverConfig)
  implicit val timeout = Timeout(settings.ResolveTimeout)

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
      val adapted = DnsProtocol.Resolve(name)
      val reply = (resolver ? adapted).mapTo[DnsProtocol.Resolved]
        .map { asyncResolved ⇒
          val ips = asyncResolved.records.collect {
            case a: ARecord    ⇒ a.ip
            case a: AAAARecord ⇒ a.ip
          }
          Dns.Resolved(asyncResolved.name, ips)
        }
      reply pipeTo sender

    case CacheCleanup ⇒
      cacheCleanup.foreach(_.cleanup())
  }
}

