/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 * Adopted from Apache v2 licensed: https://github.com/ilya-epifanov/akka-dns
 */

package akka.io.dns

import java.io.File
import java.net.{ InetSocketAddress, URI }
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

import com.typesafe.config.{ Config, ConfigValueType }

import scala.collection.JavaConverters._
import scala.collection.{ breakOut, immutable }
import scala.concurrent.duration.FiniteDuration
import scala.io.Source
import scala.util.{ Failure, Success, Try }
import java.lang.reflect.Method
import java.security.{ AccessController, PrivilegedExceptionAction }
import java.util

import scala.util.control.NonFatal

/** INTERNAL API */
private[dns] final class DnsSettings(c: Config) {
  import DnsSettings._

  val systemNameServers =
    if (c.getBoolean("resolv-conf"))
      parseSystemNameServers(Paths.get("/etc/resolv.conf").toFile)
    else
      Option.empty[immutable.Seq[InetSocketAddress]]

  val NameServers: immutable.Seq[InetSocketAddress] = {
    val addrs = Try(c.getString("nameservers")).toOption.toList
      .flatMap {
        case "default" ⇒ getDefaultSearchDomains().getOrElse(failUnableToDetermineDefaultNameservers)
        case address   ⇒ parseNameserverAddress(address) :: Nil
      }
    if (addrs.nonEmpty) addrs
    else c.getStringList("nameservers").asScala.map(parseNameserverAddress)(breakOut)
  }

  val NegativeTtl: Long = c.getDuration("negative-ttl", TimeUnit.MILLISECONDS)
  val MinPositiveTtl: Long = c.getDuration("min-positive-ttl", TimeUnit.MILLISECONDS)
  val MaxPositiveTtl: Long = c.getDuration("max-positive-ttl", TimeUnit.MILLISECONDS)

  val ResolveTimeout: FiniteDuration = FiniteDuration(c.getDuration("request-ttl", TimeUnit.MILLISECONDS), TimeUnit.MILLISECONDS)

  // -------------------------

  private val inetSocketAddress = """(.*?)(?::(\d+))?""".r

  def parseNameserverAddress(str: String): InetSocketAddress = {
    val inetSocketAddress(host, port) = str
    new InetSocketAddress(host, Option(port).fold(53)(_.toInt))
  }

  // TODO replace with actual parser, regex is very likely not efficient...
  private val ipv4Address = """^[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}\.[0-9]{1,3}$""".r
  private val ipv6Address = """^\s*((([0-9A-Fa-f]{1,4}:){7}([0-9A-Fa-f]{1,4}|:))|(([0-9A-Fa-f]{1,4}:){6}(:[0-9A-Fa-f]{1,4}|((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){5}(((:[0-9A-Fa-f]{1,4}){1,2})|:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3})|:))|(([0-9A-Fa-f]{1,4}:){4}(((:[0-9A-Fa-f]{1,4}){1,3})|((:[0-9A-Fa-f]{1,4})?:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){3}(((:[0-9A-Fa-f]{1,4}){1,4})|((:[0-9A-Fa-f]{1,4}){0,2}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){2}(((:[0-9A-Fa-f]{1,4}){1,5})|((:[0-9A-Fa-f]{1,4}){0,3}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(([0-9A-Fa-f]{1,4}:){1}(((:[0-9A-Fa-f]{1,4}){1,6})|((:[0-9A-Fa-f]{1,4}){0,4}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:))|(:(((:[0-9A-Fa-f]{1,4}){1,7})|((:[0-9A-Fa-f]{1,4}){0,5}:((25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}))|:)))(%.+)?\s*$""".r

  private def isInetAddress(name: String): Boolean =
    ipv4Address.findAllMatchIn(name).nonEmpty || ipv6Address.findAllMatchIn(name).nonEmpty

  // Note that the corresponding man page doesn't actually dictate the format of this field,
  // just the keywords and their meanings. See http://man7.org/linux/man-pages/man5/resolv.conf.5.html
  private[io] val NameserverLine = """^\s*nameserver\s+(.*)$""".r

  // OS specific. No encoding or charset is specified by the man page as I recall.
  // See http://man7.org/linux/man-pages/man5/resolv.conf.5.html.
  def parseSystemNameServers(resolvConf: File): Option[immutable.Seq[InetSocketAddress]] =
    try {
      val addresses =
        for {
          line ← Source.fromFile(resolvConf).getLines()
          addr ← NameserverLine.findFirstMatchIn(line).map(_.group(1))
        } yield parseNameserverAddress(addr)
      Some(addresses.toList)
    } catch {
      case NonFatal(_) ⇒ Option.empty
    }

  def failUnableToDetermineDefaultNameservers =
    throw new IllegalStateException("Unable to obtain default nameservers from JNDI or via reflection. " +
      "Please set `akka.io.dns.async.nameservers` explicitly in order to be able to resolve domain names.")

}

object DnsSettings {

  private final val DnsFallbackPort = 53

  /**
   * INTERNAL API
   * Find out the default search domains that Java would use normally, e.g. when using InetAddress to resolve domains.
   *
   * The default nameservers are attempted to be obtained from: jndi-dns and from `sun.net.dnsResolverConfiguration`
   * as a fallback (which is expected to fail though when running on JDK9+ due to the module encapsulation of sun packages).
   *
   * Based on: https://github.com/netty/netty/blob/4.1/resolver-dns/src/main/java/io/netty/resolver/dns/DefaultDnsServerAddressStreamProvider.java#L58-L146
   */
  private[akka] def getDefaultSearchDomains(): Try[List[InetSocketAddress]] = {
    def asInetSocketAddress(server: String): Try[InetSocketAddress] = {
      Try {
        val uri = new URI(server)
        val host = uri.getHost
        val port = uri.getPort match {
          case -1       ⇒ DnsFallbackPort
          case selected ⇒ selected
        }
        new InetSocketAddress(host, port)
      }
    }

    def getNameserversUsingJNDI: Try[List[InetSocketAddress]] = {
      import javax.naming.Context
      import javax.naming.NamingException
      import javax.naming.directory.InitialDirContext
      import java.net.URI
      import java.util
      // Using jndi-dns to obtain the default name servers.
      //
      // See:
      // - http://docs.oracle.com/javase/8/docs/technotes/guides/jndi/jndi-dns.html
      // - http://mail.openjdk.java.net/pipermail/net-dev/2017-March/010695.html
      val env = new util.Hashtable[String, String]
      env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.dns.DnsContextFactory")
      env.put("java.naming.provider.url", "dns://")

      Try {
        val ctx = new InitialDirContext(env)
        val dnsUrls = ctx.getEnvironment.get("java.naming.provider.url").asInstanceOf[String]
        // Only try if not empty as otherwise we will produce an exception
        if (dnsUrls != null && !dnsUrls.isEmpty) {
          val servers = dnsUrls.split(" ")
          servers.flatMap { server ⇒ asInetSocketAddress(server).toOption }.toList
        } else Nil
      }
    }

    // this method is used as a fallback in case JNDI results in an empty list
    // this method will not work when running modularised of course since it needs access to internal sun classes
    def getNameserversUsingReflection: Try[List[InetSocketAddress]] = {
      Try {
        val configClass = Class.forName("sun.net.dns.ResolverConfiguration")
        val open = configClass.getMethod("open")
        val nameservers = configClass.getMethod("nameservers")
        val instance = open.invoke(null)

        val ns = nameservers.invoke(instance).asInstanceOf[util.List[String]]
        val res = if (ns.isEmpty) throw new IllegalStateException("Empty nameservers list discovered using reflection. Consider configuring default nameservers manually!")
        else ns.asScala.toList
        res.flatMap(s ⇒ asInetSocketAddress(s).toOption)
      }
    }

    getNameserversUsingJNDI orElse getNameserversUsingReflection
  }
}
