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

  /** Readable way to ignore TCK specs; Return this for `createErrorStatePublisher` to skip tests including it */
  final def ignored: Publisher[T] = null

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

  @AfterClass
  def shutdownActorSystem(): Unit = {
    system.shutdown()
    system.awaitTermination(10.seconds)
  }

  override def createErrorStatePublisher(): Publisher[T] =
    StreamTestKit.errorPublisher(new Exception("Unable to serve subscribers right now!"))
}
