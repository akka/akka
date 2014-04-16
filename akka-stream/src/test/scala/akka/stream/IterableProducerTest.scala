/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.spi.Publisher
import org.reactivestreams.tck.{ TestEnvironment, PublisherVerification }
import scala.collection.immutable
import akka.stream.scaladsl.Flow
import akka.actor.ActorSystem
import akka.stream.testkit.AkkaSpec

class IterableProducerTest(_system: ActorSystem, env: TestEnvironment, publisherShutdownTimeout: Long)
  extends PublisherVerification[Int](env, publisherShutdownTimeout)
  with WithActorSystem with TestNGSuiteLike {

  implicit val system = _system

  def this(system: ActorSystem) {
    this(system, new TestEnvironment(Timeouts.defaultTimeoutMillis(system)), Timeouts.publisherShutdownTimeoutMillis)
  }

  def this() {
    this(ActorSystem(classOf[IterableProducerTest].getSimpleName, AkkaSpec.testConf))
  }

  val materializer = FlowMaterializer(MaterializerSettings(
    maximumInputBufferSize = 512))(system)

  def createPublisher(elements: Int): Publisher[Int] = {
    val iterable: immutable.Iterable[Int] =
      if (elements == 0)
        new immutable.Iterable[Int] { override def iterator = Iterator from 0 }
      else
        0 until elements
    Flow(iterable).toProducer(materializer).getPublisher
  }

  override def createCompletedStatePublisher(): Publisher[Int] =
    Flow[Int](Nil).toProducer(materializer).getPublisher

  override def createErrorStatePublisher(): Publisher[Int] = null // ignore error-state tests
}