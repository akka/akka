/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.util.concurrent.atomic.AtomicBoolean

import akka.{ Done, NotUsed }
import akka.stream.ActorMaterializer
import akka.stream.impl.LazySource
import akka.stream.testkit.Utils.TE
import akka.stream.testkit.{ StreamSpec, TestPublisher, TestSubscriber }
import akka.testkit.DefaultTimeout
import org.scalatest.concurrent.ScalaFutures

import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.util.Success

class LazySourceSpec extends StreamSpec with DefaultTimeout with ScalaFutures {

  implicit val materializer = ActorMaterializer()

  "A lazy source" should {
    "work like a normal source, happy path" in {
      val result = Source.fromGraph(LazySource(() ⇒ Source(List(1, 2, 3)))).runWith(Sink.seq)

      result.futureValue should ===(Seq(1, 2, 3))
    }

    "never construct the source when there was no demand" in {
      val probe = TestSubscriber.probe[Int]()
      val constructed = new AtomicBoolean(false)
      val result = Source.fromGraph(LazySource { () ⇒ constructed.set(true); Source(List(1, 2, 3)) }).runWith(Sink.fromSubscriber(probe))
      probe.cancel()

      constructed.get() should ===(false)
    }

    "stop consuming when downstream has cancelled" in {
      val outProbe = TestSubscriber.probe[Int]()
      val inProbe = TestPublisher.probe[Int]()

      Source.fromGraph(LazySource(() ⇒ Source.fromPublisher(inProbe))).runWith(Sink.fromSubscriber(outProbe))

      outProbe.request(1)
      inProbe.expectRequest()
      inProbe.sendNext(27)
      outProbe.expectNext(27)
      outProbe.cancel()
      inProbe.expectCancellation()
    }

    "materialize when the source has been created" in {
      val probe = TestSubscriber.probe[Int]()

      val matF: Future[Done] = Source.fromGraph(LazySource { () ⇒
        Source(List(1, 2, 3)).mapMaterializedValue(_ ⇒ Done)
      }).to(Sink.fromSubscriber(probe))
        .run()

      matF.value shouldEqual None
      probe.request(1)
      probe.expectNext(1)
      matF.value shouldEqual Some(Success(Done))

      probe.cancel()
    }

    "fail stage when upstream fails" in {
      val outProbe = TestSubscriber.probe[Int]()
      val inProbe = TestPublisher.probe[Int]()

      Source.fromGraph(LazySource(() ⇒ Source.fromPublisher(inProbe))).runWith(Sink.fromSubscriber(outProbe))

      outProbe.request(1)
      inProbe.expectRequest()
      inProbe.sendNext(27)
      outProbe.expectNext(27)
      inProbe.sendError(TE("OMG Who set that on fire!?!"))
      outProbe.expectError()
    }
  }

}
