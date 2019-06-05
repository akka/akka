/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import java.util.UUID

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

import akka.actor.ActorIdentity
import akka.actor.ActorRef
import akka.actor.Identify
import akka.remote.artery.ArterySpecSupport
import akka.remote.testconductor.RoleName
import akka.remote.testkit.FlightRecordingSupport
import akka.remote.testkit.MultiNodeConfig
import akka.remote.testkit.MultiNodeSpec
import akka.remote.testkit.STMultiNodeSpec
import akka.testkit.DefaultTimeout
import akka.testkit.ImplicitSender
import akka.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.Outcome
import org.scalatest.Suite

object RemotingMultiNodeSpec {

  def commonConfig =
    ConfigFactory.parseString(s"""
        akka.actor.warn-about-java-serializer-usage = off
        akka.remote.artery.advanced.flight-recorder {
          enabled=on
          destination=target/flight-recorder-${UUID.randomUUID().toString}.afr
        }
      """).withFallback(ArterySpecSupport.tlsConfig) // TLS only used if transport=tls-tcp

}

abstract class RemotingMultiNodeSpec(config: MultiNodeConfig)
    extends MultiNodeSpec(config)
    with Suite
    with STMultiNodeSpec
    with FlightRecordingSupport
    with ImplicitSender
    with DefaultTimeout { self: MultiNodeSpec =>

  private val probe = TestProbe()

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

  /** Identify a user actor by actor name for the given `role`.
   * {{{
   *   runOn(first) {
   *     val a = identify(second, "your-actor")
   *   }
   * }}}
   */
  def identify(role: RoleName, actorName: String, within: FiniteDuration = 10.seconds): ActorRef =
    identifyWithPath(role, "user", actorName, within)

  /** Identify an actor by `role / path / name`. Use for system actors as well. */
  private[akka] def identifyWithPath(
      role: RoleName,
      path: String,
      actorName: String,
      within: FiniteDuration = 10.seconds): ActorRef = {
    import system.dispatcher
    //(system.actorSelection(node(role) / "user" / actorName)).tell(Identify(actorName), p.ref)
    system.actorSelection(node(role) / path / actorName).resolveOne(within).map(probe.send(_, Identify(actorName)))
    val actorIdentity = probe.expectMsgType[ActorIdentity](remainingOrDefault)
    assert(actorIdentity.ref.isDefined, s"Unable to Identify actor: $actorName on node: $role")
    actorIdentity.ref.get
  }
}
