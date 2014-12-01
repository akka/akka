/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.scaladsl.OperationAttributes._
import akka.stream.MaterializerSettings
import akka.stream.impl.ActorBasedFlowMaterializer
import akka.stream.impl.Ast
import akka.stream.FlowMaterializer
import java.util.concurrent.atomic.AtomicInteger
import akka.stream.scaladsl.MaterializedMap
import org.reactivestreams.Processor
import org.reactivestreams.Publisher
import akka.stream.stage.PushStage
import akka.stream.stage.Context

import scala.concurrent.Promise

class TransformProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  val processorCounter = new AtomicInteger

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = MaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = FlowMaterializer(settings)(system)

    val flowName = getClass.getSimpleName + "-" + processorCounter.incrementAndGet()

    val mkStage = () ⇒
      new PushStage[Any, Any] {
        override def onPush(in: Any, ctx: Context[Any]) = ctx.push(in)
      }

    val (processor, _) = materializer.asInstanceOf[ActorBasedFlowMaterializer].processorForNode(
      Ast.StageFactory(mkStage, name("transform")), flowName, 1)

    processor.asInstanceOf[Processor[Int, Int]]
  }

  override def createHelperPublisher(elements: Long): Publisher[Int] = {
    implicit val mat = FlowMaterializer()(system)

    createSimpleIntPublisher(elements)(mat)
  }

}
