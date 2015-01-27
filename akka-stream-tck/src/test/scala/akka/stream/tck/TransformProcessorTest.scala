/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.scaladsl.OperationAttributes._
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.impl.ActorFlowMaterializerImpl
import akka.stream.impl.Ast
import akka.stream.ActorFlowMaterializer
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
    val settings = ActorFlowMaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = ActorFlowMaterializer(settings)(system)

    val flowName = getClass.getSimpleName + "-" + processorCounter.incrementAndGet()

    val mkStage = () ⇒
      new PushStage[Any, Any] {
        override def onPush(in: Any, ctx: Context[Any]) = ctx.push(in)
      }

    val (processor, _) = materializer.asInstanceOf[ActorFlowMaterializerImpl].processorForNode(
      Ast.StageFactory(mkStage, name("transform")), flowName, 1)

    processor.asInstanceOf[Processor[Int, Int]]
  }

  override def createHelperPublisher(elements: Long): Publisher[Int] = {
    implicit val mat = ActorFlowMaterializer()(system)

    createSimpleIntPublisher(elements)(mat)
  }

}
