/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.FlowMaterializer
import akka.stream.testkit.AkkaSpec

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

class FlowGraphInitSpec extends AkkaSpec {

  import system.dispatcher
  implicit val mat = FlowMaterializer()

  "Initialization of FlowGraph" should {
    "be thread safe" in {
      def create(): Option[FlowGraph] = {
        try {
          Some(FlowGraph { implicit b ⇒
            val zip = Zip[Int, String]
            val unzip = Unzip[Int, String]
            val out = Sink.publisher[(Int, String)]
            import FlowGraphImplicits._
            Source(List(1 -> "a", 2 -> "b", 3 -> "c")) ~> unzip.in
            unzip.left ~> Flow[Int].map(_ * 2) ~> zip.left
            unzip.right ~> zip.right
            zip.out ~> out
          })

        } catch {
          case e: Throwable ⇒ // yes I want to catch everything
            log.error(e, "FlowGraph init failure")
            None
        }
      }

      val graphs = Vector.fill(5)(Future(create()))
      val result = Await.result(Future.sequence(graphs), 5.seconds).flatten.size should be(5)
    }

    "fail when the same `KeyedSink` is used in it multiple times" in {
      val s = Source(1 to 5)
      val b = Broadcast[Int]

      val sink: KeyedSink[Int] = Sink.foreach[Int](_ ⇒ ())
      val otherSink: KeyedSink[Int] = Sink.foreach[Int](i ⇒ 2 * i)

      FlowGraph { implicit builder ⇒
        import FlowGraphImplicits._
        // format: OFF
        s ~> b ~> sink
             b ~> otherSink // this is fine
        // format: ON
      }

      val ex1 = intercept[IllegalArgumentException] {
        FlowGraph { implicit builder ⇒
          import FlowGraphImplicits._
          // format: OFF
          s ~> b ~> sink
               b ~> sink // this is not fine
          // format: ON
        }
      }
      ex1.getMessage should include(sink.getClass.getSimpleName)

      val ex2 = intercept[IllegalArgumentException] {
        FlowGraph { implicit builder ⇒
          import FlowGraphImplicits._
          // format: OFF
          s ~> b ~> sink
               b ~> otherSink // this is fine
               b ~> sink // this is not fine
          // format: ON
        }
      }
      ex2.getMessage should include(sink.getClass.getSimpleName)
    }

    "fail when the same `KeyedSource` is used in it multiple times" in {
      val s = Sink.ignore
      val m = Merge[Int]

      val source1: KeyedSource[Int] = Source.subscriber
      val source2: KeyedSource[Int] = Source.subscriber

      FlowGraph { implicit builder ⇒
        import FlowGraphImplicits._
        // KeyedSources of same type should be fine to be mixed
        // format: OFF
        source1 ~> m
                   m ~> s
        source2 ~> m
        // format: ON
      }

      val ex1 = intercept[IllegalArgumentException] {
        FlowGraph { implicit builder ⇒
          import FlowGraphImplicits._
          // format: OFF
          source1 ~> m
                     m ~> s
          source1 ~> m // whoops
          // format: ON
        }
      }
      ex1.getMessage should include(source1.getClass.getSimpleName)

      val ex2 = intercept[IllegalArgumentException] {
        FlowGraph { implicit builder ⇒
          import FlowGraphImplicits._
          // format: OFF
          source1 ~> m
          source2 ~> m ~> s
          source1 ~> m // this is not fine
          // format: ON
        }
      }
      ex2.getMessage should include(source1.getClass.getSimpleName)
    }
  }
}
