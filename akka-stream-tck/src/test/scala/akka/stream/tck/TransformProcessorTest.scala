/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.MaterializerSettings
import akka.stream.Transformer
import akka.stream.impl2.ActorBasedFlowMaterializer
import akka.stream.impl2.Ast
import akka.stream.scaladsl2.FlowMaterializer
import java.util.concurrent.atomic.AtomicInteger
import org.reactivestreams.Processor
import org.reactivestreams.Publisher

class TransformProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  val processorCounter = new AtomicInteger

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = MaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = FlowMaterializer(settings)(system)

    val flowName = getClass.getSimpleName + "-" + processorCounter.incrementAndGet()

    val mkTransformer = () ⇒
      new Transformer[Any, Any] {
        override def onNext(in: Any) = List(in)
      }

    val processor = materializer.asInstanceOf[ActorBasedFlowMaterializer].processorForNode(
      Ast.Transform("transform", mkTransformer), flowName, 1)

    processor.asInstanceOf[Processor[Int, Int]]
  }

  override def createHelperPublisher(elements: Long): Publisher[Int] = {
    implicit val mat = FlowMaterializer()(system)

    createSimpleIntPublisher(elements)(mat)
  }

}
