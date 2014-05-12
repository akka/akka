/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.spi.Publisher
import org.reactivestreams.tck.{ TestEnvironment, PublisherVerification }
import org.reactivestreams.api.Producer
import akka.stream.scaladsl.Flow
import akka.actor.ActorSystem
import akka.stream.testkit.AkkaSpec

class ActorProducerTest(_system: ActorSystem, env: TestEnvironment, publisherShutdownTimeout: Long)
  extends PublisherVerification[Int](env, publisherShutdownTimeout)
  with WithActorSystem with TestNGSuiteLike {

  implicit val system = _system
  import system.dispatcher

  def this(system: ActorSystem) {
    this(system, new TestEnvironment(Timeouts.defaultTimeoutMillis(system)), Timeouts.publisherShutdownTimeoutMillis)
  }

  def this() {
    this(ActorSystem(classOf[ActorProducerTest].getSimpleName, AkkaSpec.testConf))
  }

  private val materializer = FlowMaterializer(MaterializerSettings())

  private def createProducer(elements: Int): Producer[Int] = {
    val iter = Iterator from 1000
    val iter2 = if (elements > 0) iter take elements else iter
    Flow(() ⇒ if (iter2.hasNext) iter2.next() else throw Stop).toProducer(materializer)
  }

  def createPublisher(elements: Int): Publisher[Int] = createProducer(elements).getPublisher

  override def createCompletedStatePublisher(): Publisher[Int] = {
    val pub = createProducer(1)
    Flow(pub).consume(materializer)
    Thread.sleep(100)
    pub.getPublisher
  }

  override def createErrorStatePublisher(): Publisher[Int] = null // ignore error-state tests
}