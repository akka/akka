package akka.stream.scaladsl2

import akka.stream.testkit.AkkaSpec
import FlowGraphImplicits._

class GraphFlexiMergeSpec extends AkkaSpec {

  val in1 = IterableTap(List("a", "b", "c"))
  val in2 = IterableTap(List("d", "e", "f"))

  val out1 = PublisherDrain[String]

  "FlexiMerge" must {

    "build simple merge" in {
      FlowGraph { implicit b ⇒
        val merge = new Fair[String]
        in1 ~> merge.input1
        in2 -> merge.input2
        merge.out ~> out1
      }

      FlowGraph { implicit b ⇒
        val merge = new Fair[String]
        in1 ~> merge.input1
        in2 -> merge.input2
        merge.input1.next ~> out1
      }
    }

  }

  class Fair[T] extends FlexiMerge[T]("fairMerge") {
    import FlexiMerge._
    val input1 = input[T]()
    val input2 = input[T]()

    def createMergeLogic = new MergeLogic[T] {
      become {
        State(ReadAny(input1, input2)) {
          in ⇒ emit(in)
        }
      }
    }
  }

  class RoundRobin[T] extends FlexiMerge[T]("roundRobinMerge") {
    import FlexiMerge._
    val input1 = input[T]()
    val input2 = input[T]()

    def createMergeLogic = new MergeLogic[T] {

      def read1: State[T] = State(Read(input1)) { in ⇒
        emit(in)
        become(read2)
      }

      def read2 = State(Read(input2)) { in ⇒
        emit(in)
        become(read1)
      }

      become(read1)
    }
  }

  class Zip[A, B] extends FlexiMerge[(A, B)]("zip") {
    import FlexiMerge._
    val input1 = input[A]()
    val input2 = input[B]()

    def createMergeLogic = new MergeLogic[(A, B)] {
      var lastInA: A = _

      def readA: State[A] = State(Read(input1)) { in ⇒
        lastInA
        become(readB)
      }

      def readB = State(Read(input2)) { in ⇒
        emit((lastInA, in))
        become(readA)
      }

      become(readA, eagerClose)
    }
  }

  class OrderedMerge extends FlexiMerge[Int] {
    import FlexiMerge._
    val input1 = input[Int]()
    val input2 = input[Int]()

    def createMergeLogic = new MergeLogic[Int] {
      private var reference = 0
      
      val emitOtherOnClose = CompletionHandling(
        onComplete = {
          case input: Input[Int] ⇒
            emit(reference)
            become(readRemaining(other(input)), defaultCompletionHandling)
        },
        onError = (_, cause) ⇒ error(cause)
      )

      def other(input: Input[Int]): Input[Int] = if (input eq input1) input2 else input1

      def getFirstElement = State(ReadAnyWithInput(input1, input2)) {
        case (in, input) ⇒
          reference = in
          become(readUntilLarger(other(input)), emitOtherOnClose)
      }

      def readUntilLarger(input: Input[Int]): State[Int] = State(Read(input)) { in ⇒
        if (in <= reference) emit(in)
        else {
          emit(reference)
          reference = in
          become(readUntilLarger(other(input)))
        }
      }

      def readRemaining(input: Input[Int]) = State(Read(input)) {
        in ⇒ emit(in)
      }

      become(getFirstElement)
    }
  }

}
