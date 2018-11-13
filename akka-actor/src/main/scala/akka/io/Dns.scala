/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.net.{ Inet4Address, Inet6Address, InetAddress, UnknownHostException }

import akka.actor._
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import com.typesafe.config.Config

import scala.collection.{ breakOut, immutable }

abstract class Dns {

  /**
   * Lookup if a DNS resolved is cached. The exact behavior of caching will depend on
   * the akka.actor.io.dns.resolver that is configured.
   */
  def cached(name: String): Option[Dns.Resolved] = None

  /**
   * If an entry is cached return it immediately. If it is not then
   * trigger a resolve and return None.
   */
  def resolve(name: String)(system: ActorSystem, sender: ActorRef): Option[Dns.Resolved] = {
    val ret = cached(name)
    if (ret.isEmpty)
      IO(Dns)(system).tell(Dns.Resolve(name), sender)
    ret
  }
}

object Dns extends ExtensionId[DnsExt] with ExtensionIdProvider {
  sealed trait Command

  case class Resolve(name: String) extends Command with ConsistentHashable {
    override def consistentHashKey = name
  }

  case class Resolved(name: String, ipv4: immutable.Seq[Inet4Address], ipv6: immutable.Seq[Inet6Address]) extends Command {
    val addrOption: Option[InetAddress] = IpVersionSelector.getInetAddress(ipv4.headOption, ipv6.headOption)

    @throws[UnknownHostException]
    def addr: InetAddress = addrOption match {
      case Some(ipAddress) ⇒ ipAddress
      case None            ⇒ throw new UnknownHostException(name)
    }
  }

  object Resolved {
    def apply(name: String, addresses: Iterable[InetAddress]): Resolved = {
      val ipv4: immutable.Seq[Inet4Address] = addresses.collect({
        case a: Inet4Address ⇒ a
      })(breakOut)
      val ipv6: immutable.Seq[Inet6Address] = addresses.collect({
        case a: Inet6Address ⇒ a
      })(breakOut)
      Resolved(name, ipv4, ipv6)
    }
  }

  /**
   * Lookup if a DNS resolved is cached. The exact behavior of caching will depend on
   * the akka.actor.io.dns.resolver that is configured.
   */
  def cached(name: String)(system: ActorSystem): Option[Resolved] = {
    Dns(system).cache.cached(name)
  }

  /**
   * If an entry is cached return it immediately. If it is not then
   * trigger a resolve and return None.
   */
  def resolve(name: String)(system: ActorSystem, sender: ActorRef): Option[Resolved] = {
    Dns(system).cache.resolve(name)(system, sender)
  }

  override def lookup() = Dns

  override def createExtension(system: ExtendedActorSystem): DnsExt = new DnsExt(system)

  /**
   * Java API: retrieve the Udp extension for the given system.
   */
  override def get(system: ActorSystem): DnsExt = super.get(system)
}

/**
 * Load the resolver from akka.io.dns.<resolverName> with the given <managerName>
 */
class DnsExt(val system: ExtendedActorSystem, resolverName: String, managerName: String) extends IO.Extension {

  /**
   * Load DNS resolver configured at akka.io.dns.resolver
   */
  def this(system: ExtendedActorSystem) = this(system, system.settings.config.getConfig("akka.io.dns").getString("resolver"), "IO-DNS")

  class Settings private[DnsExt] (config: Config, resolverName: String) {
    def this(config: Config) = this(config, config.getString("resolver"))
    val Dispatcher: String = config.getString("dispatcher")
    val Resolver: String = resolverName
    val ResolverConfig: Config = config.getConfig(Resolver)
    val ProviderObjectName: String = ResolverConfig.getString("provider-object")

    override def toString = s"Settings($Dispatcher, $Resolver, $ResolverConfig, $ProviderObjectName)"
  }

  val Settings: Settings = new Settings(system.settings.config.getConfig("akka.io.dns"), resolverName)

  system.log.debug("Using settings: {}", Settings)

  val provider: DnsProvider = system.dynamicAccess.getClassFor[DnsProvider](Settings.ProviderObjectName).get.newInstance()
  val cache: Dns = provider.cache

  val manager: ActorRef = {
    system.systemActorOf(
      props = Props(provider.managerClass, this).withDeploy(Deploy.local).withDispatcher(Settings.Dispatcher),
      name = managerName)
  }

  def getResolver: ActorRef = manager
}

object IpVersionSelector {
  def getInetAddress(ipv4: Option[Inet4Address], ipv6: Option[Inet6Address]): Option[InetAddress] =
    System.getProperty("java.net.preferIPv6Addresses") match {
      case "true" ⇒ ipv6 orElse ipv4
      case _      ⇒ ipv4 orElse ipv6
    }
}
