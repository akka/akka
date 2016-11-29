/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
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

  /** artery enabled, flight recorder enabled, dynamic selection of port on localhost */
  def defaultConfig =
    ConfigFactory.parseString(s"""
      akka {
        remote.artery {
          advanced.flight-recorder {
            enabled=on
            destination=target/flight-recorder-${UUID.randomUUID().toString}.afr
          }
        }
      }
    """).withFallback(staticArteryRemotingConfig)

}

/**
 * Dumps flight recorder data on test failure if artery flight recorder is enabled
 */
trait FlightRecorderSpecIntegration { self: AkkaSpec ⇒

  def system: ActorSystem

  private val flightRecorderFile: Path =
    FileSystems.getDefault.getPath(RARP(system).provider.remoteSettings.Artery.Advanced.FlightRecorderDestination)

  // keep track of failure so that we can print flight recorder output on failures
  private var failed = false
  override protected def withFixture(test: NoArgTest): Outcome = {
    val out = test()
    if (!out.isSucceeded) failed = true
    out
  }

  override def afterTermination(): Unit = {
    self.afterTermination()
    handleFlightRecorderFile()
  }

  protected def handleFlightRecorderFile(): Unit = {
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