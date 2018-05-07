/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.net.{ InetAddress, UnknownHostException }
import java.security.Security
import java.util.concurrent.TimeUnit

import akka.actor.Actor
import com.typesafe.config.Config

import scala.collection.immutable
import akka.util.Helpers.Requiring

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

  // default values (-1 and 0 are magic numbers, trust them)
  private final val Forever = -1
  private final val Never = 0
  private final val DefaultPositive = 30

  private lazy val cachePolicy: Int = {
    val n = Try(Security.getProperty(CachePolicyProp).toInt)
      .orElse(Try(System.getProperty(CachePolicyPropFallback).toInt))
      .getOrElse(DefaultPositive) // default
    if (n < 0) Forever else n
  }

  private lazy val negativeCachePolicy = {
    val n = Try(Security.getProperty(NegativeCachePolicyProp).toInt)
      .orElse(Try(System.getProperty(NegativeCachePolicyPropFallback).toInt))
      .getOrElse(0) // default
    if (n < 0) Forever else n
  }

  private def getTtl(path: String, positive: Boolean): Long =
    config.getString(path) match {
      case "default" ⇒
        (if (positive) cachePolicy else negativeCachePolicy) match {
          case Never      ⇒ Never
          case n if n > 0 ⇒ TimeUnit.SECONDS.toMillis(n)
          case _          ⇒ Long.MaxValue // forever if negative
        }
      case "forever" ⇒ Long.MaxValue
      case "never"   ⇒ Never
      case _ ⇒ config.getDuration(path, TimeUnit.MILLISECONDS)
        .requiring(_ > 0, s"akka.io.dns.$path must be 'default', 'forever', 'never' or positive duration")
    }

  val positiveTtl: Long = getTtl("positive-ttl", positive = true)
  val negativeTtl: Long = getTtl("negative-ttl", positive = false)

  override def receive = {
    case Dns.Resolve(name) ⇒
      val answer = cache.cached(name) match {
        case Some(a) ⇒ a
        case None ⇒
          try {
            val answer = Dns.Resolved(name, InetAddress.getAllByName(name))
            if (positiveTtl != Never) cache.put(answer, positiveTtl)
            answer
          } catch {
            case e: UnknownHostException ⇒
              val answer = Dns.Resolved(name, immutable.Seq.empty, immutable.Seq.empty)
              if (negativeTtl != Never) cache.put(answer, negativeTtl)
              answer
          }
      }
      sender() ! answer
  }
}
