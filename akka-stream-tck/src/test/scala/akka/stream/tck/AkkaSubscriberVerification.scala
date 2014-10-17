/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor.ActorSystem
import akka.stream.MaterializerSettings
import akka.stream.scaladsl2.FlowMaterializer
import akka.stream.scaladsl2.Sink
import akka.stream.scaladsl2.Source
import akka.stream.testkit.AkkaSpec
import org.reactivestreams.Publisher
import org.reactivestreams.tck.SubscriberBlackboxVerification
import org.reactivestreams.tck.SubscriberWhiteboxVerification
import org.reactivestreams.tck.TestEnvironment
import org.scalatest.testng.TestNGSuiteLike
import org.testng.annotations.AfterClass

abstract class AkkaSubscriberBlackboxVerification[T](val system: ActorSystem, env: TestEnvironment)
  extends SubscriberBlackboxVerification[T](env) with TestNGSuiteLike
  with AkkaSubscriberVerificationLike {

  def this(system: ActorSystem, printlnDebug: Boolean) {
    this(system, new TestEnvironment(Timeouts.defaultTimeoutMillis(system), printlnDebug))
  }

  def this(printlnDebug: Boolean) {
    this(ActorSystem(classOf[IterablePublisherTest].getSimpleName, AkkaSpec.testConf), printlnDebug)
  }

  def this() {
    this(false)
  }
}

abstract class AkkaSubscriberWhiteboxVerification[T](val system: ActorSystem, env: TestEnvironment)
  extends SubscriberWhiteboxVerification[T](env) with TestNGSuiteLike
  with AkkaSubscriberVerificationLike {

  def this(system: ActorSystem, printlnDebug: Boolean) {
    this(system, new TestEnvironment(Timeouts.defaultTimeoutMillis(system), printlnDebug))
  }

  def this(printlnDebug: Boolean) {
    this(ActorSystem(classOf[IterablePublisherTest].getSimpleName, AkkaSpec.testConf), printlnDebug)
  }

  def this() {
    this(false)
  }
}

trait AkkaSubscriberVerificationLike {
  implicit def system: ActorSystem

  implicit val materializer = FlowMaterializer(MaterializerSettings(system))

  def createSimpleIntPublisher(elements: Long): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] =
      if (elements == Long.MaxValue) 1 to Int.MaxValue
      else 0 until elements.toInt

    Source(iterable).runWith(Sink.publisher)
  }

  @AfterClass
  def shutdownActorSystem(): Unit = {
    system.shutdown()
    system.awaitTermination(10.seconds)
  }

}
