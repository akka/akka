package docs.stream

import akka.stream._
import akka.stream.scaladsl.{ Sink, Source, Flow, Keep }
import akka.stream.testkit.AkkaSpec
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable
import scala.concurrent.Await
import scala.concurrent.duration._

class FlowStagesSpec extends AkkaSpec with ScalaFutures {
  //#import-stage
  import akka.stream.stage._
  //#import-stage

  implicit val materializer = ActorMaterializer()

  "stages demo" must {

    "demonstrate various PushPullStages" in {

      //#one-to-one
      class Map[A, B](f: A => B) extends PushPullStage[A, B] {
        override def onPush(elem: A, ctx: Context[B]): SyncDirective =
          ctx.push(f(elem))

        override def onPull(ctx: Context[B]): SyncDirective =
          ctx.pull()
      }
      //#one-to-one

      //#many-to-one
      class Filter[A](p: A => Boolean) extends PushPullStage[A, A] {
        override def onPush(elem: A, ctx: Context[A]): SyncDirective =
          if (p(elem)) ctx.push(elem)
          else ctx.pull()

        override def onPull(ctx: Context[A]): SyncDirective =
          ctx.pull()
      }
      //#many-to-one

      //#one-to-many
      class Duplicator[A]() extends PushPullStage[A, A] {
        private var lastElem: A = _
        private var oneLeft = false

        override def onPush(elem: A, ctx: Context[A]): SyncDirective = {
          lastElem = elem
          oneLeft = true
          ctx.push(elem)
        }

        override def onPull(ctx: Context[A]): SyncDirective =
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
      val sink = Flow[Int].grouped(10).toMat(keyedSink)(Keep.right)

      //#stage-chain
      val resultFuture = Source(1 to 10)
        .transform(() => new Filter(_ % 2 == 0))
        .transform(() => new Duplicator())
        .transform(() => new Map(_ / 2))
        .runWith(sink)
      //#stage-chain

      Await.result(resultFuture, 3.seconds) should be(Seq(1, 1, 2, 2, 3, 3, 4, 4, 5, 5))

    }

    "demonstrate various PushStages" in {

      import akka.stream.stage._

      //#pushstage
      class Map[A, B](f: A => B) extends PushStage[A, B] {
        override def onPush(elem: A, ctx: Context[B]): SyncDirective =
          ctx.push(f(elem))
      }

      class Filter[A](p: A => Boolean) extends PushStage[A, A] {
        override def onPush(elem: A, ctx: Context[A]): SyncDirective =
          if (p(elem)) ctx.push(elem)
          else ctx.pull()
      }
      //#pushstage
    }

    "demonstrate GraphStage" in {

      //#doubler-stateful
      class Duplicator[A] extends GraphStage[FlowShape[A, A]] {
        val in = Inlet[A]("Duplicator.in")
        val out = Outlet[A]("Duplicator.out")

        val shape: FlowShape[A, A] = FlowShape(in, out)

        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
          new GraphStageLogic(shape) {
            setHandler(in, new InHandler {
              override def onPush(): Unit = {
                val elem = grab(in)
                emitMultiple(out, List(elem, elem))
              }
            })

            setHandler(out, new OutHandler {
              override def onPull(): Unit = pull(in)
            })
          }
      }
      //#doubler-stateful

      val duplicator = Flow.fromGraph(new Duplicator[Int])
      val fold = Source(1 to 2).via(duplicator).runFold("")(_ + _)
      whenReady(fold) { s ⇒
        s should be("1122")
      }

    }

    "demonstrate DetachedStage" in {
      //#detached
      class Buffer2[T]() extends DetachedStage[T, T] {
        private var buf = Vector.empty[T]
        private var capacity = 2

        private def isFull = capacity == 0
        private def isEmpty = capacity == 2

        private def dequeue(): T = {
          capacity += 1
          val next = buf.head
          buf = buf.tail
          next
        }

        private def enqueue(elem: T) = {
          capacity -= 1
          buf = buf :+ elem
        }

        override def onPull(ctx: DetachedContext[T]): DownstreamDirective = {
          if (isEmpty) {
            if (ctx.isFinishing) ctx.finish() // No more elements will arrive
            else ctx.holdDownstream() // waiting until new elements
          } else {
            val next = dequeue()
            if (ctx.isHoldingUpstream) ctx.pushAndPull(next) // release upstream
            else ctx.push(next)
          }
        }

        override def onPush(elem: T, ctx: DetachedContext[T]): UpstreamDirective = {
          enqueue(elem)
          if (isFull) ctx.holdUpstream() // Queue is now full, wait until new empty slot
          else {
            if (ctx.isHoldingDownstream) ctx.pushAndPull(dequeue()) // Release downstream
            else ctx.pull()
          }
        }

        override def onUpstreamFinish(ctx: DetachedContext[T]): TerminationDirective = {
          if (!isEmpty) ctx.absorbTermination() // still need to flush from buffer
          else ctx.finish() // already empty, finishing
        }
      }
      //#detached
    }
  }

}
