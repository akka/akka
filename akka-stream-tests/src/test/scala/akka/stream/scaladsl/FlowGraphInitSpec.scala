/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._

import akka.stream.FlowMaterializer
import akka.stream.testkit.AkkaSpec

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
  }
}
