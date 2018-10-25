/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 * Adopted from Apache v2 licensed: https://github.com/ilya-epifanov/akka-dns
 */

package akka.io.dns

import java.net.{ InetSocketAddress, URI }
import java.util

import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.util.JavaDurationConverters._
import com.typesafe.config.{ Config, ConfigValueType }

import scala.collection.JavaConverters._
import scala.collection.{ breakOut, immutable }
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/** INTERNAL API */
@InternalApi
private[dns] final class DnsSettings(system: ExtendedActorSystem, c: Config) {

  import DnsSettings._

  val NameServers: List[InetSocketAddress] = {
    c.getValue("nameservers").valueType() match {
      case ConfigValueType.STRING ⇒
        c.getString("nameservers") match {
          case "default" ⇒
            val osAddresses = getDefaultNameServers(system).getOrElse(failUnableToDetermineDefaultNameservers)
            if (osAddresses.isEmpty) failUnableToDetermineDefaultNameservers
            osAddresses
          case other ⇒
            parseNameserverAddress(other) :: Nil
        }
      case ConfigValueType.LIST ⇒
        val userAddresses = c.getStringList("nameservers").asScala.map(parseNameserverAddress)(breakOut)
        require(userAddresses.nonEmpty, "nameservers can not be empty")
        userAddresses.toList
      case _ ⇒ throw new IllegalArgumentException("Invalid type for nameservers. Must be a string or string list")
    }
  }

  val ResolveTimeout: FiniteDuration = c.getDuration("resolve-timeout").asScala

  // -------------------------

  def failUnableToDetermineDefaultNameservers =
    throw new IllegalStateException("Unable to obtain default nameservers from JNDI or via reflection. " +
      "Please set `akka.io.dns.async-dns.nameservers` explicitly in order to be able to resolve domain names. "
    )

}

object DnsSettings {

  private final val DnsFallbackPort = 53
  private val inetSocketAddress = """(.*?)(?::(\d+))?""".r

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def parseNameserverAddress(str: String): InetSocketAddress = {
    val inetSocketAddress(host, port) = str
    new InetSocketAddress(host, Option(port).fold(DnsFallbackPort)(_.toInt))
  }

  /**
   * INTERNAL API
   * Find out the default search domains that Java would use normally, e.g. when using InetAddress to resolve domains.
   *
   * The default nameservers are attempted to be obtained from: jndi-dns and from `sun.net.dnsResolverConfiguration`
   * as a fallback (which is expected to fail though when running on JDK9+ due to the module encapsulation of sun packages).
   *
   * Based on: https://github.com/netty/netty/blob/4.1/resolver-dns/src/main/java/io/netty/resolver/dns/DefaultDnsServerAddressStreamProvider.java#L58-L146
   */
  private[akka] def getDefaultNameServers(system: ExtendedActorSystem): Try[List[InetSocketAddress]] = {
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
      import java.util

      import javax.naming.Context
      import javax.naming.directory.InitialDirContext
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
      system.dynamicAccess.getClassFor("sun.net.dns.ResolverConfiguration")
        .flatMap { c ⇒
          Try {
            val open = c.getMethod("open")
            val nameservers = c.getMethod("nameservers")
            val instance = open.invoke(null)
            val ns = nameservers.invoke(instance).asInstanceOf[util.List[String]]
            val res = if (ns.isEmpty) throw new IllegalStateException("Empty nameservers list discovered using reflection. Consider configuring default nameservers manually!")
            else ns.asScala.toList
            res.flatMap(s ⇒ asInetSocketAddress(s).toOption)
          }
        }
    }

    getNameserversUsingJNDI orElse getNameserversUsingReflection
  }
}
