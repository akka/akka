/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import java.util.concurrent.atomic.AtomicInteger
import akka.stream.impl.{ Ast, ActorBasedFlowMaterializer }
import akka.stream.scaladsl.MaterializedMap
import akka.stream.scaladsl.OperationAttributes._
import akka.stream.{ FlowMaterializer, MaterializerSettings }
import org.reactivestreams.{ Publisher, Processor }
import akka.stream.impl.fusing.Map

import scala.concurrent.Promise

class FusableProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  val processorCounter = new AtomicInteger

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = MaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = FlowMaterializer(settings)(system)

    val flowName = getClass.getSimpleName + "-" + processorCounter.incrementAndGet()

    val (processor, _ns) = materializer.asInstanceOf[ActorBasedFlowMaterializer].processorForNode(
      Ast.Fused(List(Map[Int, Int](identity)), name("identity")), flowName, 1)

    processor.asInstanceOf[Processor[Int, Int]]
  }

  override def createHelperPublisher(elements: Long): Publisher[Int] = {
    implicit val mat = FlowMaterializer()(system)

    createSimpleIntPublisher(elements)(mat)
  }

}
