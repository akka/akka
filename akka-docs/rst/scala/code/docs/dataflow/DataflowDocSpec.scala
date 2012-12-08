/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.dataflow

import language.postfixOps

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, Promise }
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import scala.util.{ Try, Failure, Success }

class DataflowDocSpec extends WordSpec with MustMatchers {

  //#import-akka-dataflow
  import akka.dataflow._ //to get the flow method and implicit conversions
  //#import-akka-dataflow

  //#import-global-implicit
  import scala.concurrent.ExecutionContext.Implicits.global
  //#import-global-implicit

  "demonstrate flow using hello world" in {
    def println[T](any: Try[T]): Unit = any.get must be === "Hello world!"
    //#simplest-hello-world
    flow { "Hello world!" } onComplete println
    //#simplest-hello-world

    //#nested-hello-world-a
    flow {
      val f1 = flow { "Hello" }
      f1() + " world!"
    } onComplete println
    //#nested-hello-world-a

    //#nested-hello-world-b
    flow {
      val f1 = flow { "Hello" }
      val f2 = flow { "world!" }
      f1() + " " + f2()
    } onComplete println
    //#nested-hello-world-b
  }

  "demonstrate the use of dataflow variables" in {
    val result = Promise[Int]()
    def println(any: Try[Int]): Unit = result.complete(any)
    //#dataflow-variable-a
    val v1, v2 = Promise[Int]()
    flow {
      // v1 will become the value of v2 + 10 when v2 gets a value
      v1 << v2() + 10
      v1() + v2()
    } onComplete println
    flow { v2 << 5 } // As you can see, no blocking above!
    //#dataflow-variable-a
    Await.result(result.future, 10.seconds) must be === 20
  }

  "demonstrate the difference between for and flow" in {
    val result = Promise[Int]()
    def println(any: Try[Int]): Unit = result.tryComplete(any)
    //#for-vs-flow
    val f1, f2 = Future { 1 }

    val usingFor = for { v1 ← f1; v2 ← f2 } yield v1 + v2
    val usingFlow = flow { f1() + f2() }

    usingFor onComplete println
    usingFlow onComplete println
    //#for-vs-flow
    Await.result(result.future, 10.seconds) must be === 2
  }

}
