package akka.stream.dsl

import akka.stream.testkit.AkkaSpec
import akka.stream.{ FlowMaterializer, MaterializerSettings }

import scala.concurrent.{ Future, Promise }

class VertexBuilderSpec extends AkkaSpec {

  val settings = MaterializerSettings(dispatcher = "akka.test.stream-dispatcher")
  val mat = FlowMaterializer(settings)

  "VertexBuilder" should {
    "set inputs" in {
      val in = PromiseInput[Int]()
      val f = From[Int].map(_ * 2)

      val g = VertexBuilder()
      g.from(in, f)

      val in2 = PromiseInput[String]()
      "g.from(in2, f)" shouldNot compile
    }
    "set inputs to any flow" in {
      val in = PromiseInput[Int]()
      val f1: OpenFlow[Int, Int] = From[Int]
      val f2: OpenOutputFlow[Int, Int] = f1.withInput(SubscriberIn())
      val f3: OpenInputFlow[Int, Int] = f1.withOutput(PublisherOut())
      val f4: ClosedFlow[Int, Int] = f2.append(f3)

      VertexBuilder().from(in, f1)
      VertexBuilder().from(in, f2)
      VertexBuilder().from(in, f3)
      VertexBuilder().from(in, f4)
    }
    "set outputs" in {
      val out = FutureOutput[Int]()
      val f = From[Int].map(_ * 2)

      val g = VertexBuilder()
      g.to(out, f)

      val out2 = FutureOutput[String]()
      "g.to(out2, f)" shouldNot compile
    }
    "set outputs to any flow" in {
      val out = FutureOutput[Int]()
      val f1: OpenFlow[Int, Int] = From[Int]
      val f2: OpenOutputFlow[Int, Int] = f1.withInput(SubscriberIn())
      val f3: OpenInputFlow[Int, Int] = f1.withOutput(PublisherOut())
      val f4: ClosedFlow[Int, Int] = f2.append(f3)

      VertexBuilder().to(out, f1)
      VertexBuilder().to(out, f2)
      VertexBuilder().to(out, f3)
      VertexBuilder().to(out, f4)
    }
    "get input and output" in {
      val in = PromiseInput[Int]
      val f = From[Int].map(_.toString)
      val out = FutureOutput[String]

      val g = VertexBuilder()
      g.from(in, f).to(out, f)

      val materialized = g.run(mat)
      in.getInputFrom(materialized): Promise[Int]
      out.getOutputFrom(materialized): Future[String]
    }
    "merge" in {
      val f1, f2, f3 = From[Int]

      val g = VertexBuilder()
      g.merge(f1, f2, f3)

      val f4 = From[String]
      "g.merge(f1 :: f2 :: Nil, f4)" shouldNot compile
    }
    "broadcast" in {
      val f1, f2, f3 = From[Int]

      val g = VertexBuilder()
      g.broadcast(f1, f2 :: f3 :: Nil)

      val f4 = From[String]
      "g.broadcast(f4, f2 :: f3 :: Nil)" shouldNot compile
    }
    "zip" in {
      val (f1, f2, f3) = (From[Int], From[String], From[(Int, String)])

      val g = VertexBuilder()
      g.zip(f1, f2, f3)

      val f4 = From[(String, Int)]
      "g.zip(f1, f2, f4)" shouldNot compile
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

      val g = VertexBuilder()
        .merge(f1, f5, f2)
        .broadcast(f2, f3 :: f4 :: Nil)
        .broadcast(f4, f5 :: f6 :: Nil)
        .from(in, f1)
        .to(out1, f3)
        .to(out2, f6)
    }
  }
}
