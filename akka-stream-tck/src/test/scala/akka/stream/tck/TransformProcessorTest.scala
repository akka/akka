/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.impl.ActorMaterializerImpl
import akka.stream.impl.Stages.Identity
import akka.stream.scaladsl.Flow
import akka.stream.Attributes
import akka.stream.stage.{ Context, PushStage }
import org.reactivestreams.{ Processor, Publisher }

class TransformProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = ActorMaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = ActorMaterializer(settings)(system)

    val mkStage = () ⇒
      new PushStage[Int, Int] {
        override def onPush(in: Int, ctx: Context[Int]) = ctx.push(in)
      }

    processorFromFlow(Flow[Int].transform(mkStage))
  }

  override def createElement(element: Int): Int = element

}
