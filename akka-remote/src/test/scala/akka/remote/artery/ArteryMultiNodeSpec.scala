/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.nio.file.{ FileSystems, Files, Path }
import java.util.UUID

import akka.actor.{ ActorSystem, RootActorPath }
import akka.remote.RARP
import akka.testkit.AkkaSpec
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.Outcome

object ArteryMultiNodeSpec {

  def defaultConfig =
    ConfigFactory.parseString(s"""
      akka {
        actor.provider = remote
        actor.warn-about-java-serializer-usage = off
        remote.artery {
          enabled = on
          canonical {
            hostname = localhost
            port = 0
          }
          advanced.flight-recorder {
            enabled=on
            destination=target/flight-recorder-${UUID.randomUUID().toString}.afr
          }
        }
      }
    """)
}

/**
 * Base class for remoting tests what needs to test interaction between a "local" actor system
 * which is always created (the usual AkkaSpec system), and multiple additional actor systems over artery
 */
abstract class ArteryMultiNodeSpec(config: Config) extends AkkaSpec(config.withFallback(ArteryMultiNodeSpec.defaultConfig)) {

  def this() = this(ConfigFactory.empty())
  def this(extraConfig: String) = this(ConfigFactory.parseString(extraConfig))

  /** just an alias to make tests more readable */
  def localSystem = system
  def localPort = port(localSystem)
  def port(system: ActorSystem): Int = RARP(system).provider.getDefaultAddress.port.get
  def address(sys: ActorSystem) = RARP(sys).provider.getDefaultAddress
  def rootActorPath(sys: ActorSystem) = RootActorPath(address(sys))
  def nextGeneratedSystemName = s"${localSystem.name}-remote-${remoteSystems.size}"
  private val flightRecorderFile: Path =
    FileSystems.getDefault.getPath(RARP(system).provider.remoteSettings.Artery.Advanced.FlightRecorderDestination)

  private var remoteSystems: Vector[ActorSystem] = Vector.empty

  /**
   * @return A new actor system configured with artery enabled. The system will
   *         automatically be terminated after test is completed to avoid leaks.
   */
  def newRemoteSystem(extraConfig: Option[String] = None, name: Option[String] = None): ActorSystem = {
    val config =
      extraConfig.fold(
        localSystem.settings.config
      )(
        str ⇒ ConfigFactory.parseString(str).withFallback(localSystem.settings.config)
      )

    val remoteSystem = ActorSystem(name.getOrElse(nextGeneratedSystemName), config)
    remoteSystems = remoteSystems :+ remoteSystem

    remoteSystem
  }

  // keep track of failure so that we can print flight recorder output on failures
  private var failed = false
  override protected def withFixture(test: NoArgTest): Outcome = {
    val out = super.withFixture(test)
    if (!out.isSucceeded) failed = true
    out
  }

  override def afterTermination(): Unit = {
    remoteSystems.foreach(sys ⇒ shutdown(sys))
    remoteSystems = Vector.empty
    handleFlightRecorderFile()
  }

  private def handleFlightRecorderFile(): Unit = {
    if (Files.exists(flightRecorderFile)) {
      if (failed) {
        // logger may not be alive anymore so we have to use stdout here
        println("Flight recorder dump:")
        FlightRecorderReader.dumpToStdout(flightRecorderFile)
      }
      Files.delete(flightRecorderFile)
    }
  }

}
