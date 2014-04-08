/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import org.scalatest.testng.TestNGSuiteLike
import org.reactivestreams.spi.Publisher
import org.reactivestreams.api.Processor
import org.reactivestreams.tck.IdentityProcessorVerification
import akka.actor.Props
import akka.stream.impl.ActorProcessor
import akka.stream.impl.TransformProcessorImpl
import akka.stream.impl.Ast
import akka.testkit.TestEvent
import akka.testkit.EventFilter
import akka.stream.impl.ActorBasedFlowMaterializer
import akka.stream.scaladsl.Flow

class IdentityProcessorTest extends IdentityProcessorVerification[Int] with WithActorSystem with TestNGSuiteLike {

  system.eventStream.publish(TestEvent.Mute(EventFilter[RuntimeException]("Test exception")))

  def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val fanoutSize = maxBufferSize / 2
    val inputSize = maxBufferSize - fanoutSize

    val materializer = new ActorBasedFlowMaterializer(
      MaterializerSettings(
        initialInputBufferSize = inputSize,
        maximumInputBufferSize = inputSize,
        initialFanOutBufferSize = fanoutSize,
        maxFanOutBufferSize = fanoutSize),
      system)

    val processor = materializer.processorForNode(Ast.Transform(Unit, (_, in: Any) ⇒ (Unit, List(in)), _ ⇒ Nil, _ ⇒ false, _ ⇒ ()))

    processor.asInstanceOf[Processor[Int, Int]]
  }

  def createHelperPublisher(elements: Int): Publisher[Int] = {
    val gen = FlowMaterializer(MaterializerSettings(
      maximumInputBufferSize = 512))(system)
    val iter = Iterator from 1000
    Flow(if (elements > 0) iter take elements else iter).toProducer(gen).getPublisher
  }

}
