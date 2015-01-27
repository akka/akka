/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.stream

import akka.stream.ActorFlowMaterializer
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec

import scala.collection.immutable.IndexedSeq
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NoStackTrace

class FlexiDocSpec extends AkkaSpec {

  implicit val ec = system.dispatcher
  implicit val mat = ActorFlowMaterializer()

  "implement zip using readall" in {
    //#fleximerge-zip-readall
    class Zip[A, B] extends FlexiMerge[(A, B)] {
      import FlexiMerge._
      val left = createInputPort[A]()
      val right = createInputPort[B]()

      def createMergeLogic = new MergeLogic[(A, B)] {
        override def inputHandles(inputCount: Int) = {
          require(inputCount == 2, s"Zip must have two connected inputs, was $inputCount")
          Vector(left, right)
        }

        override def initialState: State[_] =
          State[ReadAllInputs](ReadAll(left, right)) { (ctx, _, inputs) =>
            val a: A = inputs(left)
            val b: B = inputs(right)
            ctx.emit((a, b))
            SameState
          }
      }
    }
    //#fleximerge-zip-readall

    //format: OFF
    //#fleximerge-zip-connecting
    val head = Sink.head[(Int, String)]
    //#fleximerge-zip-connecting

    val map =
    //#fleximerge-zip-connecting
    FlowGraph { implicit b =>
      import FlowGraphImplicits._

      val zip = Zip[Int, String]

      Source.single(1)   ~> zip.left
      Source.single("1") ~> zip.right
                            zip.out ~> head
    }
    //#fleximerge-zip-connecting
      .run()
    //format: ON

    Await.result(map.get(head), 300.millis) should equal((1, "1"))
  }

  "implement zip using two states" in {
    //#fleximerge-zip-states
    class Zip[A, B] extends FlexiMerge[(A, B)] {
      import FlexiMerge._
      val left = createInputPort[A]()
      val right = createInputPort[B]()

      def createMergeLogic = new MergeLogic[(A, B)] {
        var lastInA: A = _

        override def inputHandles(inputCount: Int) = {
          require(inputCount == 2, s"Zip must have two connected inputs, was $inputCount")
          Vector(left, right)
        }

        val readA: State[A] = State[A](Read(left)) { (ctx, input, element) =>
          lastInA = element
          readB
        }

        val readB: State[B] = State[B](Read(right)) { (ctx, input, element) =>
          ctx.emit((lastInA, element))
          readA
        }

        override def initialState: State[_] = readA
      }
    }
    //#fleximerge-zip-states

    val head = Sink.head[(Int, String)]
    val map = FlowGraph { implicit b =>
      import akka.stream.scaladsl.FlowGraphImplicits._

      val zip = new Zip[Int, String]

      Source(1 to 2) ~> zip.left
      Source((1 to 2).map(_.toString)) ~> zip.right
      zip.out ~> head
    }.run()

    Await.result(map.get(head), 300.millis) should equal((1, "1"))
  }

  "fleximerge completion handling" in {
    //#fleximerge-completion
    class ImportantWithBackups[A] extends FlexiMerge[A] {
      import FlexiMerge._

      val important = createInputPort[A]()
      val replica1 = createInputPort[A]()
      val replica2 = createInputPort[A]()

      def createMergeLogic = new MergeLogic[A] {
        val inputs = Vector(important, replica1, replica2)

        override def inputHandles(inputCount: Int) = {
          require(inputCount == 3, s"Must connect 3 inputs, connected only $inputCount")
          inputs
        }

        override def initialCompletionHandling =
          CompletionHandling(
            onComplete = (ctx, input) => input match {
              case `important` =>
                log.info("Important input completed, shutting down.")
                ctx.complete()
                SameState

              case replica =>
                log.info("Replica {} completed, " +
                  "no more replicas available, " +
                  "applying eagerClose completion handling.", replica)

                ctx.changeCompletionHandling(eagerClose)
                SameState
            },
            onError = (ctx, input, cause) => input match {
              case `important` =>
                ctx.error(cause)
                SameState

              case replica =>
                log.error(cause, "Replica {} failed, " +
                  "no more replicas available, " +
                  "applying eagerClose completion handling.", replica)

                ctx.changeCompletionHandling(eagerClose)
                SameState
            })

        override def initialState = State[A](ReadAny(inputs)) {
          (ctx, input, element) =>
            ctx.emit(element)
            SameState
        }
      }
    }
    //#fleximerge-completion

    FlowGraph { implicit b =>
      import FlowGraphImplicits._
      val importantWithBackups = new ImportantWithBackups[Int]
      Source.single(1) ~> importantWithBackups.important
      Source.single(2) ~> importantWithBackups.replica1
      Source.failed[Int](new Exception("Boom!") with NoStackTrace) ~> importantWithBackups.replica2
      importantWithBackups.out ~> Sink.ignore
    }.run()
  }

  "flexi preferring merge" in {
    //#flexi-preferring-merge
    class PreferringMerge extends FlexiMerge[Int] {
      import akka.stream.scaladsl.FlexiMerge._

      val preferred = createInputPort[Int]()
      val secondary1 = createInputPort[Int]()
      val secondary2 = createInputPort[Int]()

      def createMergeLogic = new MergeLogic[Int] {
        override def inputHandles(inputCount: Int) = {
          require(inputCount == 2, s"Zip must have two connected inputs, was $inputCount")
          Vector(preferred, secondary1, secondary2)
        }

        override def initialState =
          State[Int](ReadPreferred(preferred)(secondary1, secondary2)) {
            (ctx, input, element) =>
              ctx.emit(element)
              SameState
          }
      }
    }
    //#flexi-preferring-merge
  }

  "flexi read conditions" in {
    class X extends FlexiMerge[Int] {
      import FlexiMerge._

      override def createMergeLogic(): MergeLogic[Int] = new MergeLogic[Int] {
        //#read-conditions
        val first = createInputPort[Int]()
        val second = createInputPort[Int]()
        val third = createInputPort[Int]()
        //#read-conditions

        //#read-conditions
        val onlyFirst = Read(first)

        val firstOrThird = ReadAny(first, third)

        val firstAndSecond = ReadAll(first, second)
        val firstAndThird = ReadAll(first, third)

        val mostlyFirst = ReadPreferred(first)(second, third)

        //#read-conditions

        override def inputHandles(inputCount: Int): IndexedSeq[InputHandle] = Vector()

        override def initialState: State[_] = State[ReadAllInputs](firstAndSecond) {
          (ctx, input, inputs) =>
            val in1: Int = inputs(first)
            SameState
        }
      }
    }
  }

  "flexi route" in {
    //#flexiroute-unzip
    class Unzip[A, B] extends FlexiRoute[(A, B)] {
      import FlexiRoute._
      val outA = createOutputPort[A]()
      val outB = createOutputPort[B]()

      override def createRouteLogic() = new RouteLogic[(A, B)] {

        override def outputHandles(outputCount: Int) = {
          require(outputCount == 2, s"Unzip must have two connected outputs, was $outputCount")
          Vector(outA, outB)
        }

        override def initialState = State[Any](DemandFromAll(outA, outB)) {
          (ctx, _, element) =>
            val (a, b) = element
            ctx.emit(outA, a)
            ctx.emit(outB, b)
            SameState
        }

        override def initialCompletionHandling = eagerClose
      }
    }
    //#flexiroute-unzip
  }

  "flexi route completion handling" in {
    //#flexiroute-completion
    class ImportantRoute[A] extends FlexiRoute[A] {
      import FlexiRoute._
      val important = createOutputPort[A]()
      val additional1 = createOutputPort[A]()
      val additional2 = createOutputPort[A]()

      override def createRouteLogic() = new RouteLogic[A] {
        val outputs = Vector(important, additional1, additional2)

        override def outputHandles(outputCount: Int) = {
          require(outputCount == 3, s"Must have three connected outputs, was $outputCount")
          outputs
        }

        override def initialCompletionHandling =
          CompletionHandling(
            // upstream:
            onComplete = (ctx) => (),
            onError = (ctx, thr) => (),
            // downstream:
            onCancel = (ctx, output) => output match {
              case `important` =>
                // complete all downstreams, and cancel the upstream
                ctx.complete()
                SameState
              case _ =>
                SameState
            })

        override def initialState = State[A](DemandFromAny(outputs)) {
          (ctx, output, element) =>
            ctx.emit(output, element)
            SameState
        }
      }
    }
    //#flexiroute-completion

    FlowGraph { implicit b =>
      import FlowGraphImplicits._
      val route = new ImportantRoute[Int]
      Source.single(1) ~> route.in
      route.important ~> Sink.ignore
      route.additional1 ~> Sink.ignore
      route.additional2 ~> Sink.ignore
    }.run()
  }

  "flexi route completion handling emitting element upstream completion" in {
    class ElementsAndStatus[A] extends FlexiRoute[A] {
      import FlexiRoute._
      val out = createOutputPort[A]()

      override def createRouteLogic() = new RouteLogic[A] {
        override def outputHandles(outputCount: Int) = Vector(out)

        // format: OFF
        //#flexiroute-completion-upstream-completed-signalling
        var buffer: List[A]
        //#flexiroute-completion-upstream-completed-signalling
          = List[A]()
        // format: ON

        //#flexiroute-completion-upstream-completed-signalling

        def drainBuffer(ctx: RouteLogicContext[Any]): Unit =
          while (ctx.isDemandAvailable(out) && buffer.nonEmpty) {
            ctx.emit(out, buffer.head)
            buffer = buffer.tail
          }

        val signalStatusOnTermination = CompletionHandling(
          onComplete = ctx => drainBuffer(ctx),
          onError = (ctx, cause) => drainBuffer(ctx),
          onCancel = (_, _) => SameState)
        //#flexiroute-completion-upstream-completed-signalling

        override def initialCompletionHandling = signalStatusOnTermination

        override def initialState = State[A](DemandFromAny(out)) {
          (ctx, output, element) =>
            ctx.emit(output, element)
            SameState
        }
      }
    }
  }

}
