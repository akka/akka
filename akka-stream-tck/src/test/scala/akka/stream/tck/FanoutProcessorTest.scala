/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import java.util.concurrent.atomic.AtomicInteger

import akka.stream._
import akka.stream.impl.ActorBasedFlowMaterializer
import akka.stream.impl.Ast
import org.reactivestreams.Processor
import org.reactivestreams.Publisher

class FanoutProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  val processorCounter = new AtomicInteger

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = MaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = FlowMaterializer(settings)(system)

    val flowName = getClass.getSimpleName + "-" + processorCounter.incrementAndGet()

    val processor = materializer.asInstanceOf[ActorBasedFlowMaterializer].processorForNode(
      Ast.FanoutBox(initialBufferSize = maxBufferSize / 2, maxBufferSize), flowName, 1)

    processor.asInstanceOf[Processor[Int, Int]]
  }

  override def createHelperPublisher(elements: Long): Publisher[Int] = {
    implicit val mat = FlowMaterializer()(system)

    createSimpleIntPublisher(elements)(mat)
  }

  /** The Fanout Processor actually supports fanout */
  override def maxElementsFromPublisher = Long.MaxValue

}
