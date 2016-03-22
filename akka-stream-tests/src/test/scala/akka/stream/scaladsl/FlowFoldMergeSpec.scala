/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.stream.testkit.TestPublisher
import akka.stream.ActorMaterializer
import akka.testkit.AkkaSpec
import org.scalatest.exceptions.TestFailedException

import scala.concurrent._

class FlowFoldMergeSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  def src10(i: Int) = Source(i until (i + 10))
  def blocked = Source.fromFuture(Promise[Int].future)

  val toSeq = Sink.seq[Int]
  val toSet: Sink[Int, Future[Set[Int]]] = toSeq.mapMaterializedValue(_.map(_.toSet))

  "A FoldMerge" must {

    "work in the nominal case" in {
      Source(List(0, 10, 20, 30))
        .foldMerge[Int, Int](Hub.empty)((id, hub) ⇒ hub + (id -> src10(id)))
        .runWith(toSet)
        .futureValue should ===((0 until 40).toSet)
    }

    "not be held back by one slow stream" in {
      Source(List(0, 10, -1, 20, 30))
        .foldMerge[Int, Int](Hub.empty) { (id, hub) ⇒
          if (id < 0) hub + (id -> blocked) else hub + (id -> src10(id))
        }.take(40)
        .runWith(toSet)
        .futureValue should ===((0 until 40).toSet)
    }

    "propagate early failure from main stream" in {
      val ex = new Exception("buh")
      intercept[TestFailedException] {
        Source.failed[Int](ex)
          .foldMerge[Int, Int](Hub.empty)((id, hub) ⇒ hub + (id -> src10(id)))
          .runWith(Sink.head)
          .futureValue
      }.cause.get should ===(ex)
    }

    "propagate late failure from main stream" in {
      val ex = new Exception("buh")
      intercept[TestFailedException] {
        (Source(List(1, 2)) ++ Source.failed[Int](ex))
          .foldMerge[Int, Int](Hub.empty)((id, hub) ⇒ hub + (id -> blocked))
          .runWith(Sink.head)
          .futureValue
      }.cause.get should ===(ex)
    }

    "allow removing sub streams" in {
      val sourceProbe, p1, p2, p3 = TestPublisher.probe[Int]()
      val sources = Map(
        1 -> Source.fromPublisher(p1),
        2 -> Source.fromPublisher(p2),
        3 -> Source.fromPublisher(p3))

      val result = Source.fromPublisher(sourceProbe).foldMerge[Int, Int](Hub.empty) { (id, hub) ⇒
        if (id < 0) hub - (-id)
        else hub + (id -> sources(id))
      }.runWith(toSet)

      sourceProbe.sendNext(1)
      p1.sendNext(10)
      sourceProbe.sendNext(2)
      p2.sendNext(20)
      p1.sendNext(11)
      p2.sendNext(21)
      sourceProbe.sendNext(-1)
      p1.expectCancellation()
      sourceProbe.sendNext(3)
      p3.sendNext(30)
      p2.sendNext(22)
      sourceProbe.sendNext(-3)
      p3.expectCancellation()
      sourceProbe.sendComplete()
      p2.sendNext(23)
      p2.sendComplete()

      result
        .futureValue should ===(Set(10, 11, 20, 21, 22, 23, 30))
    }

    // Other tests needed: Initial sources in hub, exception in fold method, failures in substreams

  }
}
