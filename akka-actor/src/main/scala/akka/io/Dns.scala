/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.io

import java.net.{ Inet4Address, Inet6Address, InetAddress, UnknownHostException }
import java.util.concurrent.ConcurrentHashMap

import akka.actor._
import akka.annotation.InternalApi
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import com.typesafe.config.Config
import java.util.function.{ Function ⇒ JFunction }

import akka.util.unused

import scala.collection.immutable
import akka.util.ccompat._

abstract class Dns {

  /**
   * Lookup if a DNS resolved is cached. The exact behavior of caching will depend on
   * the akka.actor.io.dns.resolver that is configured.
   */
  def cached(@unused name: String): Option[Dns.Resolved] = None

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
      val ipv4: immutable.Seq[Inet4Address] = addresses.iterator.collect({
        case a: Inet4Address ⇒ a
      }).to(immutable.IndexedSeq)
      val ipv6: immutable.Seq[Inet6Address] = addresses.iterator.collect({
        case a: Inet6Address ⇒ a
      }).to(immutable.IndexedSeq)
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

class DnsExt private[akka] (val system: ExtendedActorSystem, resolverName: String, managerName: String) extends IO.Extension {

  private val asyncDns = new ConcurrentHashMap[String, ActorRef]

  /**
   * INTERNAL API
   *
   * Load an additional async-dns resolver. Can be used to use async-dns even if inet-resolver is the configuerd
   * default.
   * Intentionally chosen not to support loading an arbitrary resolver as it required a specific constructor
   * for the manager actor. The expected constructor for DNS plugins is just to take in a DnsExt which can't
   * be used in this case
   */
  @InternalApi
  private[akka] def loadAsyncDns(managerName: String): ActorRef = {
    // This can't pass in `this` as then AsyncDns would pick up the system settings
    asyncDns.computeIfAbsent(managerName, new JFunction[String, ActorRef] {
      override def apply(r: String): ActorRef = {
        val settings = new Settings(system.settings.config.getConfig("akka.io.dns"), "async-dns")
        val provider = system.dynamicAccess.getClassFor[DnsProvider](settings.ProviderObjectName).get.newInstance()
        system.log.info("Creating async dns resolver {} with manager name {}", settings.Resolver, managerName)
        system.systemActorOf(
          props = Props(provider.managerClass, settings.Resolver, system, settings.ResolverConfig, provider.cache, settings.Dispatcher, provider).withDeploy(Deploy.local).withDispatcher(settings.Dispatcher),
          name = managerName)
      }
    })
  }

  /**
   * INTERNAL API
   *
   * Use IO(DNS) or Dns(system). Do not instantiate directly
   *
   * For binary compat as DnsExt constructor didn't used to have internal API on
   */
  @InternalApi
  def this(system: ExtendedActorSystem) = this(system, system.settings.config.getString("akka.io.dns.resolver"), "IO-DNS")

  class Settings private[DnsExt] (config: Config, resolverName: String) {
    /**
     * Load the default resolver
     */
    def this(config: Config) = this(config, config.getString("resolver"))

    val Dispatcher: String = config.getString("dispatcher")
    val Resolver: String = resolverName
    val ResolverConfig: Config = config.getConfig(Resolver)
    val ProviderObjectName: String = ResolverConfig.getString("provider-object")

    override def toString = s"Settings($Dispatcher, $Resolver, $ResolverConfig, $ProviderObjectName)"
  }

  // Settings for the system resolver
  val Settings: Settings = new Settings(system.settings.config.getConfig("akka.io.dns"), resolverName)

  // System DNS resolver
  val provider: DnsProvider = system.dynamicAccess.getClassFor[DnsProvider](Settings.ProviderObjectName).get.newInstance()

  // System DNS cache
  val cache: Dns = provider.cache

  // System DNS manager
  val manager: ActorRef = {
    system.systemActorOf(
      props = Props(provider.managerClass, this).withDeploy(Deploy.local).withDispatcher(Settings.Dispatcher),
      name = managerName)
  }

  // System DNS manager
  def getResolver: ActorRef = manager

}

object IpVersionSelector {
  def getInetAddress(ipv4: Option[Inet4Address], ipv6: Option[Inet6Address]): Option[InetAddress] =
    System.getProperty("java.net.preferIPv6Addresses") match {
      case "true" ⇒ ipv6 orElse ipv4
      case _      ⇒ ipv4 orElse ipv6
    }
}
