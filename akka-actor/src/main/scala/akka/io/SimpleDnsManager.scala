package akka.io

import java.util.concurrent.TimeUnit

import akka.actor.{ ActorLogging, Actor, Deploy, Props }
import akka.dispatch.{ RequiresMessageQueue, UnboundedMessageQueueSemantics }
import akka.routing.FromConfig

import scala.concurrent.duration.Duration

class SimpleDnsManager(val ext: DnsExt) extends Actor with RequiresMessageQueue[UnboundedMessageQueueSemantics] with ActorLogging {

  import context._

  private val resolver = actorOf(FromConfig.props(Props(ext.provider.actorClass, ext.cache, ext.Settings.ResolverConfig).withDeploy(Deploy.local).withDispatcher(ext.Settings.Dispatcher)), ext.Settings.Resolver)
  private val cacheCleanup = ext.cache match {
    case cleanup: PeriodicCacheCleanup ⇒ Some(cleanup)
    case _                             ⇒ None
  }

  private val cleanupTimer = cacheCleanup map { _ ⇒
    val interval = Duration(ext.Settings.ResolverConfig.getDuration("cache-cleanup-interval", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)
    system.scheduler.schedule(interval, interval, self, SimpleDnsManager.CacheCleanup)
  }

  override def receive = {
    case r @ Dns.Resolve(name) ⇒
      log.debug("Resolution request for {} from {}", name, sender())
      resolver.forward(r)
    case SimpleDnsManager.CacheCleanup ⇒
      for (c ← cacheCleanup)
        c.cleanup()
  }

  override def postStop(): Unit = {
    for (t ← cleanupTimer) t.cancel()
  }
}

object SimpleDnsManager {
  private case object CacheCleanup
}
