/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.actor.ActorSystem
import akka.stream._
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit
import akka.testkit.EventFilter
import akka.testkit.TestEvent
import org.reactivestreams.Publisher
import org.reactivestreams.tck.IdentityProcessorVerification
import org.reactivestreams.tck.TestEnvironment
import org.scalatest.testng.TestNGSuiteLike

import scala.collection.immutable

abstract class AkkaIdentityProcessorVerification[T](val system: ActorSystem, env: TestEnvironment, publisherShutdownTimeout: Long)
  extends IdentityProcessorVerification[T](env, publisherShutdownTimeout)
  with TestNGSuiteLike {

  system.eventStream.publish(TestEvent.Mute(EventFilter[RuntimeException]("Test exception")))

  /** Readable way to ignore TCK specs; Return this for `createErrorStatePublisher` to skip tests including it */
  final def ignored: Publisher[T] = null

  def this(system: ActorSystem, printlnDebug: Boolean) {
    this(system, new TestEnvironment(Timeouts.defaultTimeoutMillis(system), printlnDebug), Timeouts.publisherShutdownTimeoutMillis)
  }

  def this(printlnDebug: Boolean) {
    this(ActorSystem(classOf[IterablePublisherTest].getSimpleName, AkkaSpec.testConf), printlnDebug)
  }

  def this() {
    this(false)
  }

  override def skipStochasticTests() = true // TODO maybe enable?

  override def createErrorStatePublisher(): Publisher[T] =
    StreamTestKit.errorPublisher(new Exception("Unable to serve subscribers right now!"))

  def createSimpleIntPublisher(elements: Long)(implicit mat: FlowMaterializer): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] =
      if (elements == Long.MaxValue) 1 to Int.MaxValue
      else 0 until elements.toInt

    Flow(iterable).toPublisher()
  }

  /** By default Akka Publishers do not support Fanout! */
  override def maxSupportedSubscribers: Long = 1L
}
