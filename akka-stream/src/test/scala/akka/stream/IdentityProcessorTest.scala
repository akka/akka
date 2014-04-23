/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.spi.Publisher
import org.reactivestreams.api.Processor
import org.reactivestreams.tck.{ PublisherVerification, TestEnvironment, IdentityProcessorVerification }
import akka.actor.{ ActorSystem, Props }
import akka.stream.impl.ActorProcessor
import akka.stream.impl.TransformProcessorImpl
import akka.stream.impl.Ast
import akka.testkit.{ TestEvent, EventFilter }
import akka.stream.impl.ActorBasedFlowMaterializer
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import java.util.concurrent.atomic.AtomicInteger

class IdentityProcessorTest(_system: ActorSystem, env: TestEnvironment, publisherShutdownTimeout: Long)
  extends IdentityProcessorVerification[Int](env, publisherShutdownTimeout)
  with WithActorSystem with TestNGSuiteLike {

  implicit val system = _system
  import system.dispatcher

  def this(system: ActorSystem) {
    this(system, new TestEnvironment(Timeouts.defaultTimeoutMillis(system)), Timeouts.publisherShutdownTimeoutMillis)
  }

  def this() {
    this(ActorSystem(classOf[IdentityProcessorTest].getSimpleName, AkkaSpec.testConf))
  }

  system.eventStream.publish(TestEvent.Mute(EventFilter[RuntimeException]("Test exception")))
  val processorCounter = new AtomicInteger

  def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val fanoutSize = maxBufferSize / 2
    val inputSize = maxBufferSize - fanoutSize

    val materializer = new ActorBasedFlowMaterializer(
      MaterializerSettings(
        initialInputBufferSize = inputSize,
        maximumInputBufferSize = inputSize,
        initialFanOutBufferSize = fanoutSize,
        maxFanOutBufferSize = fanoutSize),
      system, system.name)

    val processor = materializer.processorForNode(Ast.Transform(
      new Transformer[Any, Any] {
        override def onNext(in: Any) = List(in)
      }), "IdentityProcessorTest-" + processorCounter.incrementAndGet(), 1)

    processor.asInstanceOf[Processor[Int, Int]]
  }

  def createHelperPublisher(elements: Int): Publisher[Int] = {
    val materializer = FlowMaterializer(MaterializerSettings(
      maximumInputBufferSize = 512))(system)
    val iter = Iterator from 1000
    Flow(if (elements > 0) iter take elements else iter).toProducer(materializer).getPublisher
  }

  override def createErrorStatePublisher(): Publisher[Int] = null // ignore error-state tests

  override def createCompletedStatePublisher(): Publisher[Int] = null // ignore completed-state tests
}
