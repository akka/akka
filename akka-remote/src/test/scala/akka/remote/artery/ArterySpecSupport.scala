/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.nio.file.{ FileSystems, Files, Path }
import java.util.UUID

import akka.actor.ActorSystem
import akka.remote.RARP
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory
import org.scalatest.Outcome

object ArterySpecSupport {
  // same for all artery enabled remoting tests
  private val staticArteryRemotingConfig = ConfigFactory.parseString(s"""
    akka {
      actor {
        provider = remote
        warn-about-java-serializer-usage = off
        serialize-creators = off
      }
      remote.artery {
        enabled = on
        canonical {
          hostname = localhost
          port = 0
        }
      }
    }""")

  def newFlightRecorderConfig =
    ConfigFactory.parseString(s"""
      akka {
        remote.artery {
          advanced.flight-recorder {
            enabled=on
            destination=target/flight-recorder-${UUID.randomUUID().toString}.afr
          }
        }
      }
    """)

  /**
   * Artery enabled, flight recorder enabled, dynamic selection of port on localhost.
   * Combine with [[FlightRecorderSpecIntegration]] or remember to delete flight recorder file if using manually
   */
  def defaultConfig = newFlightRecorderConfig.withFallback(staticArteryRemotingConfig)

}

/**
 * Dumps flight recorder data on test failure if artery flight recorder is enabled
 *
 * Important note: if you more than one (the default AkkaSpec.system) systems you need to override
 * afterTermination and call handleFlightRecorderFile manually in the spec or else it will not be dumped
 * on failure but also leak the afr file
 */
trait FlightRecorderSpecIntegration { self: AkkaSpec ⇒

  def system: ActorSystem

  protected final def flightRecorderFileFor(system: ActorSystem): Path =
    FileSystems.getDefault.getPath(RARP(system).provider.remoteSettings.Artery.Advanced.FlightRecorderDestination)

  // keep track of failure so that we can print flight recorder output on failures
  protected final def failed = _failed
  private var _failed = false
  override protected def withFixture(test: NoArgTest): Outcome = {
    val out = test()
    if (!out.isSucceeded) _failed = true
    out
  }

  override def afterTermination(): Unit = {
    self.afterTermination()
    handleFlightRecorderFile(system)
  }

  protected def handleFlightRecorderFile(system: ActorSystem): Unit = {
    val flightRecorderFile = flightRecorderFileFor(system)
    if (Files.exists(flightRecorderFile)) {
      if (failed) {
        // logger may not be alive anymore so we have to use stdout here
        println(s"Flight recorder dump for system [${system.name}]:")
        FlightRecorderReader.dumpToStdout(flightRecorderFile)
      }
      Files.delete(flightRecorderFile)
    }
  }
}
