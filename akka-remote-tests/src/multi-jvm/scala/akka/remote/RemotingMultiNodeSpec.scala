/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote

import java.util.UUID

import akka.actor.ActorSystem
import akka.remote.testkit.{ FlightRecordingSupport, MultiNodeConfig, MultiNodeSpec, STMultiNodeSpec }
import akka.testkit.{ DefaultTimeout, ImplicitSender }
import com.typesafe.config.ConfigFactory
import org.scalatest.{ Outcome, Suite }

import scala.util.Try

object RemotingMultiNodeSpec {

  def commonConfig =
    ConfigFactory.parseString(
      s"""
        akka.actor.warn-about-java-serializer-usage = off
        akka.remote.artery.advanced.flight-recorder {
          enabled=on
          destination=target/flight-recorder-${UUID.randomUUID().toString}.afr
        }
      """)

}

abstract class RemotingMultiNodeSpec(config: MultiNodeConfig) extends MultiNodeSpec(config, config ⇒ {
  val name = MultiNodeSpec.getCallerName(classOf[RemotingMultiNodeSpec])
  //  When running multi-jvm tests with port = 0 two ActorSystems
  //  can select the same port so retry once
  Try(ActorSystem(name, config)).getOrElse(ActorSystem(name, config))
})
  with Suite
  with STMultiNodeSpec
  with FlightRecordingSupport
  with ImplicitSender
  with DefaultTimeout { self: MultiNodeSpec ⇒

  // Keep track of failure so we can print artery flight recording on failure
  private var failed = false
  final override protected def withFixture(test: NoArgTest): Outcome = {
    val out = super.withFixture(test)
    if (!out.isSucceeded)
      failed = true
    out
  }

  override def afterTermination(): Unit = {
    if (failed || sys.props.get("akka.remote.artery.always-dump-flight-recorder").isDefined) {
      printFlightRecording()
    }
    deleteFlightRecorderFile()
  }
}
