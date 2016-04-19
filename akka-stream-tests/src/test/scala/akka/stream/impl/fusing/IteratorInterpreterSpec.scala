/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.fusing

import akka.testkit.AkkaSpec
import akka.util.ByteString
import akka.stream.stage._
import akka.stream.{ Attributes, Supervision }
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage

class IteratorInterpreterSpec extends AkkaSpec with GraphInterpreterSpecKit {
  import Supervision.stoppingDecider

  "IteratorInterpreter" must {

    "work in the happy case" in {
      val itr = new IteratorInterpreter[Int, Int]((1 to 10).iterator, Seq(
        Map((x: Int) ⇒ x + 1, stoppingDecider).toGS)).iterator

      itr.toSeq should be(2 to 11)
    }

    "hasNext should not affect elements" in {
      val itr = new IteratorInterpreter[Int, Int]((1 to 10).iterator, Seq(
        Map((x: Int) ⇒ x, stoppingDecider).toGS)).iterator

      itr.hasNext should be(true)
      itr.hasNext should be(true)
      itr.hasNext should be(true)
      itr.hasNext should be(true)
      itr.hasNext should be(true)

      itr.toSeq should be(1 to 10)
    }

    "work with ops that need extra pull for complete" in {
      val itr = new IteratorInterpreter[Int, Int]((1 to 10).iterator, Seq(NaiveTake(1))).iterator

      itr.toSeq should be(Seq(1))
    }

    "throw exceptions on empty iterator" in {
      val itr = new IteratorInterpreter[Int, Int](List(1).iterator, Seq(
        Map((x: Int) ⇒ x, stoppingDecider).toGS)).iterator

      itr.next() should be(1)
      a[NoSuchElementException] should be thrownBy { itr.next() }
    }

    "throw exceptions when op in chain throws" in {
      val itr = new IteratorInterpreter[Int, Int](List(1, 2, 3).iterator, Seq(
        Map((n: Int) ⇒ if (n == 2) throw new ArithmeticException() else n, stoppingDecider).toGS)).iterator

      itr.next() should be(1)
      itr.hasNext should be(true)
      a[ArithmeticException] should be thrownBy { itr.next() }
      itr.hasNext should be(false)
    }

    "work with an empty iterator" in {
      val itr = new IteratorInterpreter[Int, Int](Iterator.empty, Seq(
        Map((x: Int) ⇒ x + 1, stoppingDecider).toGS)).iterator

      itr.hasNext should be(false)
      a[NoSuchElementException] should be thrownBy { itr.next() }
    }

    "able to implement a ByteStringBatcher" in {
      val testBytes = (1 to 10).map(ByteString(_))

      def newItr(threshold: Int) =
        new IteratorInterpreter[ByteString, ByteString](testBytes.iterator, Seq(
          ByteStringBatcher(threshold))).iterator

      val itr1 = newItr(20)
      itr1.next() should be(ByteString(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
      itr1.hasNext should be(false)

      val itr2 = newItr(10)
      itr2.next() should be(ByteString(1, 2, 3, 4, 5, 6, 7, 8, 9, 10))
      itr2.hasNext should be(false)

      val itr3 = newItr(5)
      itr3.next() should be(ByteString(1, 2, 3, 4, 5))
      (6 to 10) foreach { i ⇒
        itr3.hasNext should be(true)
        itr3.next() should be(ByteString(i))
      }
      itr3.hasNext should be(false)

      val itr4 =
        new IteratorInterpreter[ByteString, ByteString](Iterator.empty, Seq(
          ByteStringBatcher(10))).iterator

      itr4.hasNext should be(false)
    }

  }

  // This op needs an extra pull round to finish
  case class NaiveTake[T](count: Int) extends SimpleLinearGraphStage[T] {

    override def createLogic(attributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {
        private var left: Int = count

        override def onPush(): Unit = {
          left -= 1
          push(out, grab(in))
        }

        override def onPull(): Unit = {
          if (left == 0) completeStage()
          else pull(in)
        }

        setHandlers(in, out, this)
      }

    override def toString = "NaiveTake"
  }

  case class ByteStringBatcher(threshold: Int, compact: Boolean = true) extends SimpleLinearGraphStage[ByteString] {
    require(threshold > 0, "Threshold must be positive")

    override def createLogic(attributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with InHandler with OutHandler {
        private var buf: ByteString = ByteString.empty
        private var passthrough: Boolean = false

        override def onPush(): Unit = {
          val elem = grab(in)
          if (passthrough) push(out, elem)
          else {
            buf = buf ++ elem
            if (buf.size >= threshold) {
              val batch = if (compact) buf.compact else buf
              passthrough = true
              buf = ByteString.empty
              push(out, batch)
            } else pull(in)
          }
        }

        override def onPull(): Unit = {
          if (isClosed(in)) {
            push(out, buf)
            completeStage()
          } else pull(in)
        }

        override def onUpstreamFinish(): Unit = {
          if (passthrough || buf.isEmpty) completeStage()
          else if (isAvailable(out)) onPull()
        }

        setHandlers(in, out, this)
      }

    override def toString = "ByteStringBatcher"
  }
}
