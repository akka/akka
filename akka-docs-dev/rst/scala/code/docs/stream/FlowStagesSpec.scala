package docs.stream

import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl.{ RunnableFlow, Sink, Source, Flow }
import akka.stream.stage.PushPullStage
import akka.stream.testkit.AkkaSpec
import org.scalatest.concurrent.{ ScalaFutures, Futures }

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

class FlowStagesSpec extends AkkaSpec with ScalaFutures {
  //#import-stage
  import akka.stream.stage._
  //#import-stage

  implicit val mat = ActorFlowMaterializer()

  "stages demo" must {

    "demonstrate various PushPullStages" in {

      //#one-to-one
      class Map[A, B](f: A => B) extends PushPullStage[A, B] {
        override def onPush(elem: A, ctx: Context[B]): Directive =
          ctx.push(f(elem))

        override def onPull(ctx: Context[B]): Directive =
          ctx.pull()
      }
      //#one-to-one

      //#many-to-one
      class Filter[A](p: A => Boolean) extends PushPullStage[A, A] {
        override def onPush(elem: A, ctx: Context[A]): Directive =
          if (p(elem)) ctx.push(elem)
          else ctx.pull()

        override def onPull(ctx: Context[A]): Directive =
          ctx.pull()
      }
      //#many-to-one

      //#one-to-many
      class Duplicator[A]() extends PushPullStage[A, A] {
        private var lastElem: A = _
        private var oneLeft = false

        override def onPush(elem: A, ctx: Context[A]): Directive = {
          lastElem = elem
          oneLeft = true
          ctx.push(elem)
        }

        override def onPull(ctx: Context[A]): Directive =
          if (!ctx.isFinishing) {
            // the main pulling logic is below as it is demonstrated on the illustration
            if (oneLeft) {
              oneLeft = false
              ctx.push(lastElem)
            } else
              ctx.pull()
          } else {
            // If we need to emit a final element after the upstream
            // finished
            if (oneLeft) ctx.pushAndFinish(lastElem)
            else ctx.finish()
          }

        override def onUpstreamFinish(ctx: Context[A]): TerminationDirective =
          ctx.absorbTermination()

      }
      //#one-to-many

      val keyedSink = Sink.head[immutable.Seq[Int]]
      val sink = Flow[Int].grouped(10).to(keyedSink)

      //#stage-chain
      val runnable: RunnableFlow = Source(1 to 10)
        .transform(() => new Filter(_ % 2 == 0))
        .transform(() => new Duplicator())
        .transform(() => new Map(_ / 2))
        .to(sink)
      //#stage-chain

      Await.result(runnable.run().get(keyedSink), 3.seconds) should be(Seq(1, 1, 2, 2, 3, 3, 4, 4, 5, 5))

    }

    "demonstrate various PushStages" in {

      import akka.stream.stage._

      //#pushstage
      class Map[A, B](f: A => B) extends PushStage[A, B] {
        override def onPush(elem: A, ctx: Context[B]): Directive =
          ctx.push(f(elem))
      }

      class Filter[A](p: A => Boolean) extends PushStage[A, A] {
        override def onPush(elem: A, ctx: Context[A]): Directive =
          if (p(elem)) ctx.push(elem)
          else ctx.pull()
      }
      //#pushstage
    }

    "demonstrate StatefulStage" in {

      //#doubler-stateful
      class Duplicator[A]() extends StatefulStage[A, A] {
        override def initial: StageState[A, A] = new StageState[A, A] {
          override def onPush(elem: A, ctx: Context[A]): Directive =
            emit(List(elem, elem).iterator, ctx)
        }
      }
      //#doubler-stateful

      val fold = Source(1 to 2).transform(() ⇒ new Duplicator[Int]).runFold("")(_ + _)
      whenReady(fold) { s ⇒
        s should be("1122")
      }

    }
  }

}
