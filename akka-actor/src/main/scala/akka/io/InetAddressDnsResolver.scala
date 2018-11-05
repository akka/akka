/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.net.{ InetAddress, UnknownHostException }
import java.security.Security
import java.util.concurrent.TimeUnit

import akka.actor.Actor
import akka.util.Helpers.Requiring
import com.typesafe.config.Config

import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.Try

/** Respects the settings that can be set on the Java runtime via parameters. */
class InetAddressDnsResolver(cache: SimpleDnsCache, config: Config) extends Actor {

  // Controls the cache policy for successful lookups only
  private final val CachePolicyProp = "networkaddress.cache.ttl"
  // Deprecated JVM property key, keeping for legacy compatibility; replaced by CachePolicyProp
  private final val CachePolicyPropFallback = "sun.net.inetaddr.ttl"

  // Controls the cache policy for negative lookups only
  private final val NegativeCachePolicyProp = "networkaddress.cache.negative.ttl"
  // Deprecated JVM property key, keeping for legacy compatibility; replaced by NegativeCachePolicyProp
  private final val NegativeCachePolicyPropFallback = "sun.net.inetaddr.negative.ttl"

  private final val DefaultPositive = FiniteCache(30.seconds)

  private lazy val defaultCachePolicy: CachePolicy =
    Try(Security.getProperty(CachePolicyProp).toInt)
      .orElse(Try(System.getProperty(CachePolicyPropFallback).toInt))
      .map(parsePolicy)
      .getOrElse(DefaultPositive) // default

  private lazy val defaultNegativeCachePolicy: CachePolicy =
    Try(Security.getProperty(NegativeCachePolicyProp).toInt)
      .orElse(Try(System.getProperty(NegativeCachePolicyPropFallback).toInt))
      .map(parsePolicy)
      .getOrElse(NeverCache) // default

  private def parsePolicy(n: Int): CachePolicy = {
    n match {
      case 0          ⇒ NeverCache
      case x if x < 0 ⇒ CacheForever
      case x          ⇒ FiniteCache(x.seconds)
    }
  }

  private def getTtl(path: String, positive: Boolean): CachePolicy =
    config.getString(path) match {
      case "default" ⇒ if (positive) defaultCachePolicy else defaultNegativeCachePolicy
      case "forever" ⇒ CacheForever
      case "never"   ⇒ NeverCache
      case _ ⇒ {
        val finiteTtl = config
          .getDuration(path, TimeUnit.MILLISECONDS)
          .requiring(_ > 0, s"akka.io.dns.$path must be 'default', 'forever', 'never' or positive duration")
        FiniteCache(finiteTtl.seconds)
      }
    }

  val positiveCachePolicy: CachePolicy = getTtl("positive-ttl", positive = true)
  val negativeCachePolicy: CachePolicy = getTtl("negative-ttl", positive = false)
  @deprecated("Use positiveCacheDuration instead", "2.5.17")
  val positiveTtl: Long = toLongTtl(positiveCachePolicy)
  @deprecated("Use negativeCacheDuration instead", "2.5.17")
  val negativeTtl: Long = toLongTtl(negativeCachePolicy)

  private def toLongTtl(cp: CachePolicy): Long = {
    cp match {
      case CacheForever     ⇒ Long.MaxValue
      case NeverCache       ⇒ 0
      case FiniteCache(ttl) ⇒ ttl.toMillis
    }
  }

  override def receive = {
    case Dns.Resolve(name) ⇒
      val answer = cache.cached(name) match {
        case Some(a) ⇒ a
        case None ⇒
          try {
            val answer = Dns.Resolved(name, InetAddress.getAllByName(name))
            if (positiveCachePolicy != NeverCache) cache.put(answer, positiveCachePolicy)
            answer
          } catch {
            case e: UnknownHostException ⇒
              val answer = Dns.Resolved(name, immutable.Seq.empty, immutable.Seq.empty)
              if (negativeCachePolicy != NeverCache) cache.put(answer, negativeCachePolicy)
              answer
          }
      }
      sender() ! answer
  }
}
