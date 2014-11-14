package akka.stream.impl.fusing

import akka.stream.testkit.AkkaSpec
import akka.util.ByteString

import scala.collection.immutable

class IteratorInterpreterSpec extends AkkaSpec {

  "IteratorInterpreter" must {

    "work in the happy case" in {
      val itr = new IteratorInterpreter[Int, Int]((1 to 10).iterator, Seq(
        Map((x: Int) ⇒ x + 1))).iterator

      itr.toSeq should be(2 to 11)
    }

    "hasNext should not affect elements" in {
      val itr = new IteratorInterpreter[Int, Int]((1 to 10).iterator, Seq(
        Map((x: Int) ⇒ x))).iterator

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
        Map((x: Int) ⇒ x))).iterator

      itr.next() should be(1)
      a[NoSuchElementException] should be thrownBy { itr.next() }
    }

    "throw exceptions when chain fails" in {
      val itr = new IteratorInterpreter[Int, Int](List(1, 2, 3).iterator, Seq(
        new TransitivePullOp[Int, Int] {
          override def onPush(elem: Int, ctxt: Context[Int]): Directive = {
            if (elem == 2) ctxt.fail(new ArithmeticException())
            else ctxt.push(elem)
          }
        })).iterator

      itr.next() should be(1)
      a[ArithmeticException] should be thrownBy { itr.next() }
    }

    "throw exceptions when op in chain throws" in {
      val itr = new IteratorInterpreter[Int, Int](List(1, 2, 3).iterator, Seq(
        new TransitivePullOp[Int, Int] {
          override def onPush(elem: Int, ctxt: Context[Int]): Directive = {
            if (elem == 2) throw new ArithmeticException()
            else ctxt.push(elem)
          }
        })).iterator

      itr.next() should be(1)
      a[ArithmeticException] should be thrownBy { itr.next() }
    }

    "work with an empty iterator" in {
      val itr = new IteratorInterpreter[Int, Int](Iterator.empty, Seq(
        Map((x: Int) ⇒ x + 1))).iterator

      itr.hasNext should be(false)
      a[NoSuchElementException] should be thrownBy { itr.next() }
    }

    "able to implement a ByteStringBatcher" in {
      val testBytes = (1 to 10).map(ByteString(_))

      def newItr(threshold: Int) =
        new IteratorInterpreter[ByteString, ByteString](testBytes.iterator, Seq(ByteStringBatcher(threshold))).iterator

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
        new IteratorInterpreter[ByteString, ByteString](Iterator.empty, Seq(ByteStringBatcher(10))).iterator

      itr4.hasNext should be(false)
    }

  }

  // This op needs an extra pull round to finish
  case class NaiveTake[T](count: Int) extends DeterministicOp[T, T] {
    private var left: Int = count

    override def onPush(elem: T, ctxt: Context[T]): Directive = {
      left -= 1
      ctxt.push(elem)
    }

    override def onPull(ctxt: Context[T]): Directive = {
      if (left == 0) ctxt.finish()
      else ctxt.pull()
    }
  }

  case class ByteStringBatcher(threshold: Int, compact: Boolean = true) extends DeterministicOp[ByteString, ByteString] {
    require(threshold > 0, "Threshold must be positive")

    private var buf = ByteString.empty
    private var passthrough = false

    override def onPush(elem: ByteString, ctxt: Context[ByteString]): Directive = {
      if (passthrough) ctxt.push(elem)
      else {
        buf = buf ++ elem
        if (buf.size >= threshold) {
          val batch = if (compact) buf.compact else buf
          passthrough = true
          buf = ByteString.empty
          ctxt.push(batch)
        } else ctxt.pull()
      }
    }

    override def onPull(ctxt: Context[ByteString]): Directive = {
      if (isFinishing) ctxt.pushAndFinish(buf)
      else ctxt.pull()
    }

    override def onUpstreamFinish(ctxt: Context[ByteString]): TerminationDirective = {
      if (passthrough || buf.isEmpty) ctxt.finish()
      else ctxt.absorbTermination()
    }
  }

}
