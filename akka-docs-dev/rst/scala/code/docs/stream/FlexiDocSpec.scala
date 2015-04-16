/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream.OperationAttributes

object FlexiDocSpec {
  //#fleximerge-zip-states
  //#fleximerge-zip-readall
  import akka.stream.FanInShape._
  class ZipPorts[A, B](_init: Init[(A, B)] = Name("Zip"))
    extends FanInShape[(A, B)](_init) {
    val left = newInlet[A]("left")
    val right = newInlet[B]("right")
    protected override def construct(i: Init[(A, B)]) = new ZipPorts(i)
  }
  //#fleximerge-zip-readall
  //#fleximerge-zip-states
}

class FlexiDocSpec extends AkkaSpec {
  import FlexiDocSpec._

  implicit val ec = system.dispatcher
  implicit val mat = ActorFlowMaterializer()

  "implement zip using readall" in {
    //#fleximerge-zip-readall
    class Zip[A, B] extends FlexiMerge[(A, B), ZipPorts[A, B]](
      new ZipPorts, OperationAttributes.name("Zip1State")) {
      import FlexiMerge._
      override def createMergeLogic(p: PortT) = new MergeLogic[(A, B)] {
        override def initialState =
          State(ReadAll(p.left, p.right)) { (ctx, _, inputs) =>
            val a = inputs(p.left)
            val b = inputs(p.right)
            ctx.emit((a, b))
            SameState
          }

        override def initialCompletionHandling = eagerClose
      }
    }
    //#fleximerge-zip-readall

    //format: OFF
    val res =
    //#fleximerge-zip-connecting
    FlowGraph.closed(Sink.head[(Int, String)]) { implicit b =>
      o =>
      import FlowGraph.Implicits._

      val zip = b.add(new Zip[Int, String])

      Source.single(1)   ~> zip.left
      Source.single("1") ~> zip.right
                            zip.out ~> o.inlet
    }
    //#fleximerge-zip-connecting
    .run()
    //format: ON

    Await.result(res, 300.millis) should equal((1, "1"))
  }

  "implement zip using two states" in {
    //#fleximerge-zip-states
    class Zip[A, B] extends FlexiMerge[(A, B), ZipPorts[A, B]](
      new ZipPorts, OperationAttributes.name("Zip2State")) {
      import FlexiMerge._

      override def createMergeLogic(p: PortT) = new MergeLogic[(A, B)] {
        var lastInA: A = _

        val readA: State[A] = State[A](Read(p.left)) { (ctx, input, element) =>
          lastInA = element
          readB
        }

        val readB: State[B] = State[B](Read(p.right)) { (ctx, input, element) =>
          ctx.emit((lastInA, element))
          readA
        }

        override def initialState: State[_] = readA

        override def initialCompletionHandling = eagerClose
      }
    }
    //#fleximerge-zip-states

    val res = FlowGraph.closed(Sink.head[(Int, String)]) { implicit b =>
      o =>
        import FlowGraph.Implicits._

        val zip = b.add(new Zip[Int, String])

        Source(1 to 2) ~> zip.left
        Source((1 to 2).map(_.toString)) ~> zip.right
        zip.out ~> o.inlet
    }.run()

    Await.result(res, 300.millis) should equal((1, "1"))
  }

  "fleximerge completion handling" in {
    import FanInShape._
    //#fleximerge-completion
    class ImportantWithBackupShape[A](_init: Init[A] = Name("Zip"))
      extends FanInShape[A](_init) {
      val important = newInlet[A]("important")
      val replica1 = newInlet[A]("replica1")
      val replica2 = newInlet[A]("replica2")
      protected override def construct(i: Init[A]) =
        new ImportantWithBackupShape(i)
    }
    class ImportantWithBackups[A] extends FlexiMerge[A, ImportantWithBackupShape[A]](
      new ImportantWithBackupShape, OperationAttributes.name("ImportantWithBackups")) {
      import FlexiMerge._

      override def createMergeLogic(p: PortT) = new MergeLogic[A] {
        import p.important
        override def initialCompletionHandling =
          CompletionHandling(
            onUpstreamFinish = (ctx, input) => input match {
              case `important` =>
                log.info("Important input completed, shutting down.")
                ctx.finish()
                SameState

              case replica =>
                log.info("Replica {} completed, " +
                  "no more replicas available, " +
                  "applying eagerClose completion handling.", replica)

                ctx.changeCompletionHandling(eagerClose)
                SameState
            },
            onUpstreamFailure = (ctx, input, cause) => input match {
              case `important` =>
                ctx.fail(cause)
                SameState

              case replica =>
                log.error(cause, "Replica {} failed, " +
                  "no more replicas available, " +
                  "applying eagerClose completion handling.", replica)

                ctx.changeCompletionHandling(eagerClose)
                SameState
            })

        override def initialState =
          State[A](ReadAny(p.important, p.replica1, p.replica2)) {
            (ctx, input, element) =>
              ctx.emit(element)
              SameState
          }
      }
    }
    //#fleximerge-completion

    FlowGraph.closed() { implicit b =>
      import FlowGraph.Implicits._
      val importantWithBackups = b.add(new ImportantWithBackups[Int])
      Source.single(1) ~> importantWithBackups.important
      Source.single(2) ~> importantWithBackups.replica1
      Source.failed[Int](new Exception("Boom!") with NoStackTrace) ~> importantWithBackups.replica2
      importantWithBackups.out ~> Sink.ignore
    }.run()
  }

  "flexi preferring merge" in {
    import FanInShape._
    //#flexi-preferring-merge-ports
    class PreferringMergeShape[A](_init: Init[A] = Name("PreferringMerge"))
      extends FanInShape[A](_init) {
      val preferred = newInlet[A]("preferred")
      val secondary1 = newInlet[A]("secondary1")
      val secondary2 = newInlet[A]("secondary2")
      protected override def construct(i: Init[A]) = new PreferringMergeShape(i)
    }
    //#flexi-preferring-merge-ports

    //#flexi-preferring-merge

    class PreferringMerge extends FlexiMerge[Int, PreferringMergeShape[Int]](
      new PreferringMergeShape, OperationAttributes.name("ImportantWithBackups")) {
      import akka.stream.scaladsl.FlexiMerge._

      override def createMergeLogic(p: PortT) = new MergeLogic[Int] {
        override def initialState =
          State[Int](ReadPreferred(p.preferred, p.secondary1, p.secondary2)) {
            (ctx, input, element) =>
              ctx.emit(element)
              SameState
          }
      }
    }
    //#flexi-preferring-merge
  }

  "flexi route" in {
    //#flexiroute-unzip
    import FanOutShape._
    class UnzipShape[A, B](_init: Init[(A, B)] = Name[(A, B)]("Unzip"))
      extends FanOutShape[(A, B)](_init) {
      val outA = newOutlet[A]("outA")
      val outB = newOutlet[B]("outB")
      protected override def construct(i: Init[(A, B)]) = new UnzipShape(i)
    }
    class Unzip[A, B] extends FlexiRoute[(A, B), UnzipShape[A, B]](
      new UnzipShape, OperationAttributes.name("Unzip")) {
      import FlexiRoute._

      override def createRouteLogic(p: PortT) = new RouteLogic[(A, B)] {
        override def initialState =
          State[Any](DemandFromAll(p.outA, p.outB)) {
            (ctx, _, element) =>
              val (a, b) = element
              ctx.emit(p.outA)(a)
              ctx.emit(p.outB)(b)
              SameState
          }

        override def initialCompletionHandling = eagerClose
      }
    }
    //#flexiroute-unzip
  }

  "flexi route completion handling" in {
    import FanOutShape._
    //#flexiroute-completion
    class ImportantRouteShape[A](_init: Init[A] = Name[A]("ImportantRoute")) extends FanOutShape[A](_init) {
      val important = newOutlet[A]("important")
      val additional1 = newOutlet[A]("additional1")
      val additional2 = newOutlet[A]("additional2")
      protected override def construct(i: Init[A]) = new ImportantRouteShape(i)
    }
    class ImportantRoute[A] extends FlexiRoute[A, ImportantRouteShape[A]](
      new ImportantRouteShape, OperationAttributes.name("ImportantRoute")) {
      import FlexiRoute._
      override def createRouteLogic(p: PortT) = new RouteLogic[A] {
        import p.important
        private val select = (p.important | p.additional1 | p.additional2)

        override def initialCompletionHandling =
          CompletionHandling(
            // upstream:
            onUpstreamFinish = (ctx) => (),
            onUpstreamFailure = (ctx, thr) => (),
            // downstream:
            onDownstreamFinish = (ctx, output) => output match {
              case `important` =>
                // finish all downstreams, and cancel the upstream
                ctx.finish()
                SameState
              case _ =>
                SameState
            })

        override def initialState =
          State(DemandFromAny(p.important, p.additional1, p.additional2)) {
            (ctx, output, element) =>
              ctx.emit(select(output))(element)
              SameState
          }
      }
    }
    //#flexiroute-completion

    FlowGraph.closed() { implicit b =>
      import FlowGraph.Implicits._
      val route = b.add(new ImportantRoute[Int])
      Source.single(1) ~> route.in
      route.important ~> Sink.ignore
      route.additional1 ~> Sink.ignore
      route.additional2 ~> Sink.ignore
    }.run()
  }

}
