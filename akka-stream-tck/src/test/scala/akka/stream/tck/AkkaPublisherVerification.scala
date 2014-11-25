/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.event.Logging

import scala.concurrent.duration._

import akka.actor.ActorSystem
import akka.stream.MaterializerSettings
import akka.stream.FlowMaterializer
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import org.reactivestreams.Publisher
import org.reactivestreams.tck.{ PublisherVerification, TestEnvironment }
import org.scalatest.testng.TestNGSuiteLike
import org.testng.annotations.AfterClass

abstract class AkkaPublisherVerification[T](val system: ActorSystem, env: TestEnvironment, publisherShutdownTimeout: Long)
  extends PublisherVerification[T](env, publisherShutdownTimeout)
  with TestNGSuiteLike {

  def this(system: ActorSystem, printlnDebug: Boolean) {
    this(system, new TestEnvironment(Timeouts.defaultTimeoutMillis(system), printlnDebug), Timeouts.publisherShutdownTimeoutMillis)
  }

  def this(printlnDebug: Boolean) {
    this(ActorSystem(Logging.simpleName(classOf[IterablePublisherTest]), AkkaSpec.testConf), printlnDebug)
  }

  def this() {
    this(false)
  }

  implicit val materializer = FlowMaterializer(MaterializerSettings(system).copy(maxInputBufferSize = 512))(system)

  override def skipStochasticTests() = true // TODO maybe enable?

  // TODO re-enable this test once 1.0.0.RC1 is released, with https://github.com/reactive-streams/reactive-streams/pull/154
  override def spec317_mustSignalOnErrorWhenPendingAboveLongMaxValue() = notVerified("TODO Enable this test once https://github.com/reactive-streams/reactive-streams/pull/154 is merged (ETA 1.0.0.RC1)")

  @AfterClass
  def shutdownActorSystem(): Unit = {
    system.shutdown()
    system.awaitTermination(10.seconds)
  }

  override def createErrorStatePublisher(): Publisher[T] =
    StreamTestKit.errorPublisher(new Exception("Unable to serve subscribers right now!"))
}
