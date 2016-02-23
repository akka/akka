/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.tck

import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.stream.scaladsl.Flow
import akka.stream.stage.{ Context, PushStage }
import org.reactivestreams.{ Processor }

class TransformProcessorTest extends AkkaIdentityProcessorVerification[Int] {

  override def createIdentityProcessor(maxBufferSize: Int): Processor[Int, Int] = {
    val settings = ActorMaterializerSettings(system)
      .withInputBuffer(initialSize = maxBufferSize / 2, maxSize = maxBufferSize)

    implicit val materializer = ActorMaterializer(settings)(system)

    val mkStage = () ⇒
      new PushStage[Int, Int] {
        override def onPush(in: Int, ctx: Context[Int]) = ctx.push(in)
      }

    Flow[Int].transform(mkStage).toProcessor.run()
  }

  override def createElement(element: Int): Int = element

}
