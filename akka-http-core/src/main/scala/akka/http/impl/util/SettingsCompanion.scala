/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.util

import java.net.{ InetSocketAddress, InetAddress }
import com.typesafe.config.{ ConfigFactory, Config }
import com.typesafe.config.ConfigFactory._
import scala.util.control.NonFatal
import scala.collection.immutable.ListMap
import scala.collection.JavaConverters._
import akka.actor.{ ActorRefFactory, ActorSystem }

/**
 * INTERNAL API
 */
private[http] abstract class SettingsCompanion[T](protected val prefix: String) {
  private final val MaxCached = 8
  private[this] var cache = ListMap.empty[ActorSystem, T]

  implicit def default(implicit refFactory: ActorRefFactory): T =
    apply(actorSystem)

  def apply(system: ActorSystem): T =
    // we use and update the cache without any synchronization,
    // there are two possible "problems" resulting from this:
    // - cache misses of things another thread has already put into the cache,
    //   in these cases we do double work, but simply accept it
    // - cache hits of things another thread has already dropped from the cache,
    //   in these cases we avoid double work, which is nice
    cache.getOrElse(system, {
      val settings = apply(system.settings.config)
      val c =
        if (cache.size < MaxCached) cache
        else cache.tail // drop the first (and oldest) cache entry
      cache = c.updated(system, settings)
      settings
    })

  def apply(configOverrides: String): T =
    apply(parseString(configOverrides)
      .withFallback(SettingsCompanion.configAdditions)
      .withFallback(defaultReference(getClass.getClassLoader)))

  def apply(config: Config): T =
    fromSubConfig(config, config getConfig prefix)

  def fromSubConfig(root: Config, c: Config): T
}

private[http] object SettingsCompanion {
  lazy val configAdditions: Config = {
    val localHostName =
      try new InetSocketAddress(InetAddress.getLocalHost, 80).getHostString
      catch { case NonFatal(_) ⇒ "" }
    ConfigFactory.parseMap(Map("akka.http.hostname" -> localHostName).asJava)
  }
}