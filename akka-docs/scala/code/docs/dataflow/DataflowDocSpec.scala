/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.dataflow

import language.postfixOps

import scala.concurrent.util.duration._
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

  import DataflowDocSpec._
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
    def println[T](any: Try[T]): Unit = any.get must be === 20
    //#dataflow-variable-a
    flow {
      val v1, v2 = Promise[Int]()

      // v1 will become the value of v2 + 10 when v2 gets a value
      v1 << v2() + 10
      v2 << flow { 5 } // As you can see, no blocking!
      v1() + v2()
    } onComplete println
    //#dataflow-variable-a
  }

  "demonstrate the difference between for and flow" in {
    def println[T](any: Try[T]): Unit = any.get must be === 2
    //#for-vs-flow
    val f1, f2 = Future { 1 }

    val usingFor = for { v1 ← f1; v2 ← f2 } yield v1 + v2
    val usingFlow = flow { f1() + f2() }

    usingFor onComplete println
    usingFlow onComplete println
    //#for-vs-flow
  }

}
