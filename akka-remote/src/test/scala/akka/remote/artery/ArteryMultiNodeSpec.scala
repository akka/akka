/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.nio.file.{ FileSystems, Files, Path }
import java.util.UUID

import akka.actor.{ ActorSystem, RootActorPath }
import akka.remote.RARP
import akka.testkit.AkkaSpec
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.Outcome

/**
 * Base class for remoting tests what needs to test interaction between a "local" actor system
 * which is always created (the usual AkkaSpec system), and multiple additional actor systems over artery
 */
abstract class ArteryMultiNodeSpec(config: Config) extends AkkaSpec(config.withFallback(ArterySpecSupport.defaultConfig))
  with FlightRecorderSpecIntegration {

  def this() = this(ConfigFactory.empty())
  def this(extraConfig: String) = this(ConfigFactory.parseString(extraConfig))

  /** just an alias to make tests more readable */
  def localSystem = system
  def localPort = port(localSystem)
  def port(system: ActorSystem): Int = RARP(system).provider.getDefaultAddress.port.get
  def address(sys: ActorSystem) = RARP(sys).provider.getDefaultAddress
  def rootActorPath(sys: ActorSystem) = RootActorPath(address(sys))
  def nextGeneratedSystemName = s"${localSystem.name}-remote-${remoteSystems.size}"

  private var remoteSystems: Vector[ActorSystem] = Vector.empty

  /**
   * @return A new actor system configured with artery enabled. The system will
   *         automatically be terminated after test is completed to avoid leaks.
   */
  def newRemoteSystem(extraConfig: Option[String] = None, name: Option[String] = None): ActorSystem = {
    val config =
      ArterySpecSupport.newFlightRecorderConfig.withFallback(extraConfig.fold(
        localSystem.settings.config
      )(
        str ⇒ ConfigFactory.parseString(str).withFallback(localSystem.settings.config)
      ))

    val remoteSystem = ActorSystem(name.getOrElse(nextGeneratedSystemName), config)
    remoteSystems = remoteSystems :+ remoteSystem

    remoteSystem
  }

  override def afterTermination(): Unit = {
    remoteSystems.foreach(sys ⇒ shutdown(sys))
    (system +: remoteSystems).foreach(handleFlightRecorderFile)
    remoteSystems = Vector.empty
    super.afterTermination()
  }

}
