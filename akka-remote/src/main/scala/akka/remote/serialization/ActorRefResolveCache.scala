/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.serialization

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.EmptyLocalActorRef
import akka.actor.ExtendedActorSystem
import akka.actor.Extension
import akka.actor.ExtensionId
import akka.actor.ExtensionIdProvider
import akka.remote.RemoteActorRefProvider
import akka.remote.artery.LruBoundedCache
import akka.util.{ Unsafe, unused }

/**
 * INTERNAL API: Thread local cache per actor system
 */
private[akka] object ActorRefResolveThreadLocalCache
  extends ExtensionId[ActorRefResolveThreadLocalCache] with ExtensionIdProvider {

  override def get(system: ActorSystem): ActorRefResolveThreadLocalCache = super.get(system)

  override def lookup = ActorRefResolveThreadLocalCache

  override def createExtension(system: ExtendedActorSystem): ActorRefResolveThreadLocalCache =
    new ActorRefResolveThreadLocalCache(system)
}

/**
 * INTERNAL API
 */
private[akka] class ActorRefResolveThreadLocalCache(val system: ExtendedActorSystem) extends Extension {

  private val provider = system.provider match {
    case r: RemoteActorRefProvider ⇒ r
    case _ ⇒ throw new IllegalArgumentException(
      "ActorRefResolveThreadLocalCache can only be used with RemoteActorRefProvider, " +
        s"not with ${system.provider.getClass}")
  }

  private val current = new ThreadLocal[ActorRefResolveCache] {
    override def initialValue: ActorRefResolveCache = new ActorRefResolveCache(provider)
  }

  def threadLocalCache(@unused provider: RemoteActorRefProvider): ActorRefResolveCache =
    current.get

}

/**
 * INTERNAL API
 */
private[akka] final class ActorRefResolveCache(provider: RemoteActorRefProvider)
  extends LruBoundedCache[String, ActorRef](capacity = 1024, evictAgeThreshold = 600) {

  override protected def compute(k: String): ActorRef =
    provider.internalResolveActorRef(k)

  override protected def hash(k: String): Int = Unsafe.fastHash(k)

  override protected def isCacheable(v: ActorRef): Boolean = !v.isInstanceOf[EmptyLocalActorRef]
}
