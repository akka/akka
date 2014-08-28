package akka.stream.dsl

import akka.stream.testkit.AkkaSpec
import akka.stream.{ FlowMaterializer, MaterializerSettings }

import scala.concurrent.{ Future, Promise }

class EdgeBuilderSpec extends AkkaSpec {

  val settings = MaterializerSettings(dispatcher = "akka.test.stream-dispatcher")
  val mat = FlowMaterializer(settings)

  "EdgeBuilder" should {
    "set inputs" in {
      val in = PromiseInput[Int]()
      val f = From[Int].map(_ * 2)

      val g = EdgeBuilder()
      g(in) ~> f

      val in2 = PromiseInput[String]()
      "g(in2) ~> f" shouldNot compile
    }
    "set inputs to any flow" in {
      val in = PromiseInput[Int]()
      val f1: OpenFlow[Int, Int] = From[Int]
      val f2: OpenOutputFlow[Int, Int] = f1.withInput(SubscriberIn())
      val f3: OpenInputFlow[Int, Int] = f1.withOutput(PublisherOut())
      val f4: ClosedFlow[Int, Int] = f2.append(f3)

      EdgeBuilder()(in) ~> f1
      EdgeBuilder()(in) ~> f2
      EdgeBuilder()(in) ~> f3
      EdgeBuilder()(in) ~> f4
    }
    "set outputs" in {
      val out = FutureOutput[Int]()
      val f = From[Int].map(_ * 2)

      val g = EdgeBuilder()
      g(f) ~> out

      val out2 = FutureOutput[String]()
      "g(f) ~> out2" shouldNot compile
    }
    "set outputs to any flow" in {
      val out = FutureOutput[Int]()
      val f1: OpenFlow[Int, Int] = From[Int]
      val f2: OpenOutputFlow[Int, Int] = f1.withInput(SubscriberIn())
      val f3: OpenInputFlow[Int, Int] = f1.withOutput(PublisherOut())
      val f4: ClosedFlow[Int, Int] = f2.append(f3)

      EdgeBuilder()(f1) ~> out
      EdgeBuilder()(f2) ~> out
      EdgeBuilder()(f3) ~> out
      EdgeBuilder()(f4) ~> out
    }
    "get input and output" in {
      val in = PromiseInput[Int]
      val f = From[Int].map(_.toString)
      val out = FutureOutput[String]

      val g = EdgeBuilder()
      g(in) ~> f ~> out

      val materialized = g.run(mat)
      in.getInputFrom(materialized): Promise[Int]
      out.getOutputFrom(materialized): Future[String]
    }
    "merge" in {
      val (f1, f2, f3) = (From[Int], From[Double], From[AnyVal])

      val g = EdgeBuilder()
      val merge = g.merge[Int, Double, AnyVal]

      g(f1) ~~> merge ~> f3
      g(f2) ~> merge

      val f4 = From[String]
      "g(f4) ~> merge" shouldNot compile
    }
    "broadcast" in {
      val f1, f2, f3 = From[Int]

      val g = EdgeBuilder()
      val broadcast = g.broadcast[Int]

      g(f1) ~> broadcast ~> f3
      g(broadcast) ~> f2

      val f4 = From[String]
      "g(broadcast) ~> f4" shouldNot compile
    }
    "zip" in {
      val (f1, f2, f3) = (From[Int], From[String], From[(Int, String)])

      val g = EdgeBuilder()
      val zip = g.zip[Int, String]

      g(f1) ~> zip ~> f3
      g(f2) ~~> zip

      val f4 = From[(String, Int)]
      "g(f1) ~> zip ~> f4" shouldNot compile
    }
    "be able to express complex topologies" in {
      /**
       * in ---> f1 -+-> f2 -+-> f3 ---> out1
       *             ^       |
       *             |       V
       *             f5 <-+- f4
       *                  |
       *                  V
       *                  f6 ---> out2
       */
      val in = PromiseInput[Int]()
      val out1 = FutureOutput[Int]()
      val out2 = FutureOutput[Int]()

      val f1, f2, f3, f4, f5, f6 = From[Int]

      val g = EdgeBuilder()
      val merge = g.merge[Int, Int, Int]
      val bcast1 = g.broadcast[Int]
      val bcast2 = g.broadcast[Int]

      g(in) ~> f1 ~> merge ~> f2 ~> bcast1 ~> f3 ~> out1
      g(bcast1) ~> f4 ~> bcast2 ~> f5 ~> merge
      g(bcast2) ~> f6 ~> out2
    }
  }
}
