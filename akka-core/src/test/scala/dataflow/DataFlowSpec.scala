/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.dataflow

import org.scalatest.Spec
import org.scalatest.Assertions
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.BeforeAndAfterAll
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith

import se.scalablesolutions.akka.dispatch.DefaultCompletableFuture
import java.util.concurrent.{TimeUnit, CountDownLatch}
import annotation.tailrec
import java.util.concurrent.atomic.{AtomicLong, AtomicReference, AtomicInteger}
import se.scalablesolutions.akka.actor.ActorRegistry

@RunWith(classOf[JUnitRunner])
class DataFlowTest extends Spec with ShouldMatchers with BeforeAndAfterAll {
    describe("DataflowVariable") {
      it("should be able to set the value of one variable from other variables") {
        import DataFlow._

        val latch = new CountDownLatch(1)
        val result = new AtomicInteger(0)
        val x, y, z = new DataFlowVariable[Int]
        thread {
          z << x() + y()
          latch.countDown
          result.set(z())
        }
        thread { x << 40 }
        thread { y << 2 }

        latch.await(10,TimeUnit.SECONDS) should equal (true)
        result.get should equal (42)
        List(x,y,z).foreach(_.shutdown)
      }

      it("should be able to sum a sequence of ints") {
        import DataFlow._

        def ints(n: Int, max: Int): List[Int] =
          if (n == max) Nil
          else n :: ints(n + 1, max)

        def sum(s: Int, stream: List[Int]): List[Int] = stream match {
          case Nil => s :: Nil
          case h :: t => s :: sum(h + s, t)
        }

        val latch = new CountDownLatch(1)
        val result = new AtomicReference[List[Int]](Nil)
        val x = new DataFlowVariable[List[Int]]
        val y = new DataFlowVariable[List[Int]]
        val z = new DataFlowVariable[List[Int]]

        thread { x << ints(0, 1000) }
        thread { y << sum(0, x())   }

        thread { z << y()
          result.set(z())
          latch.countDown
        }

        latch.await(10,TimeUnit.SECONDS) should equal (true)
        result.get should equal (sum(0,ints(0,1000)))
        List(x,y,z).foreach(_.shutdown)
      }
    }
}
