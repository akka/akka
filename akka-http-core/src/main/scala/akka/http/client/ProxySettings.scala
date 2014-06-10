/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.client

import com.typesafe.config.{ ConfigValueType, Config }
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import akka.http.util._

final case class ProxySettings(host: String, port: Int, nonProxyHosts: List[String]) {
  require(host.nonEmpty, "proxy host must be non-empty")
  require(0 < port && port < 65536, "illegal proxy port")
  require(nonProxyHosts forall validIgnore, "illegal nonProxyHosts")

  // see http://docs.oracle.com/javase/6/docs/technotes/guides/net/proxies.html
  private def validIgnore(pattern: String) = pattern.exists(_ != '*') && !pattern.drop(1).dropRight(1).contains('*')

  val matchesHost: String ⇒ Boolean = {
    @tailrec def rec(remainingNonProxyHosts: List[String], result: String ⇒ Boolean): String ⇒ Boolean =
      remainingNonProxyHosts match {
        case Nil ⇒ result
        case pattern :: remaining ⇒
          val check: String ⇒ Boolean =
            (pattern endsWith '*', pattern startsWith '*') match {
              case (true, true) ⇒
                val p = pattern.drop(1).dropRight(1); _.contains(p)
              case (true, false) ⇒
                val p = pattern.dropRight(1); _.startsWith(p)
              case (false, true) ⇒
                val p = pattern.drop(1); _.endsWith(p)
              case _ ⇒ _ == pattern
            }
          rec(remaining, host ⇒ !check(host) && result(host))
      }
    rec(nonProxyHosts, result = _ ⇒ true)
  }
}

object ProxySettings extends SettingsCompanion[Map[String, ProxySettings]]("akka.http.client.proxy") {
  // see http://docs.oracle.com/javase/6/docs/technotes/guides/net/proxies.html
  def fromProperties(properties: Map[String, String], scheme: String): Option[ProxySettings] = {
    val proxyHost = properties.get(s"$scheme.proxyHost")
    val proxyPort = properties.get(s"$scheme.proxyPort")
    val nonProxyHosts = properties.get(s"$scheme.nonProxyHosts")
    proxyHost map (apply(
      _,
      proxyPort.getOrElse("80").toInt,
      nonProxyHosts.map(_.fastSplit('|')).getOrElse(Nil).toList))
  }

  def fromSubConfig(c: Config) = apply(c, sys.props.toMap): Map[String, ProxySettings]

  def apply(c: Config, properties: Map[String, String]): Map[String, ProxySettings] = {
    def proxySettings(scheme: String) = c.getValue(scheme).valueType() match {
      case ConfigValueType.STRING ⇒
        c.getString(scheme) match {
          case "default" ⇒ fromProperties(properties, scheme).map((scheme, _))
          case "none"    ⇒ None
          case unknown   ⇒ throw new IllegalArgumentException(s"illegal value for proxy.$scheme: '$unknown'")
        }
      case _ ⇒
        val cfg = c getConfig scheme
        Some(scheme -> apply(
          cfg getString "host",
          cfg getInt "port",
          (cfg getStringList "non-proxy-hosts").asScala.toList))
    }

    val schemes = c.entrySet.asScala.groupBy(_.getKey.split("\\.")(0)).keySet
    schemes.flatMap(proxySettings).toMap
  }
}
