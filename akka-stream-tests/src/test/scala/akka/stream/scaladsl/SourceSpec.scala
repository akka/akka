/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{ Success, Failure }
import scala.util.control.NoStackTrace

import akka.stream.ActorFlowMaterializer
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.StreamTestKit

class SourceSpec extends AkkaSpec {

  implicit val materializer = ActorFlowMaterializer()

  "Singleton Source" must {
    "produce element" in {
      val p = Source.single(1).runWith(Sink.publisher)
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      val sub = c.expectSubscription()
      sub.request(1)
      c.expectNext(1)
      c.expectComplete()
    }

    "produce elements to later subscriber" in {
      val p = Source.single(1).runWith(Sink.publisher)
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c1)

      val sub1 = c1.expectSubscription()
      sub1.request(1)
      c1.expectNext(1)
      c1.expectComplete()
      p.subscribe(c2)
      val sub2 = c2.expectSubscription()
      sub2.request(3)
      c2.expectNext(1)
      c2.expectComplete()
    }

  }

  "Empty Source" must {
    "complete immediately" in {
      val p = Source.empty.runWith(Sink.publisher)
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      c.expectComplete()

      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c2)
      c2.expectComplete()
    }
  }

  "Failed Source" must {
    "emit error immediately" in {
      val ex = new RuntimeException with NoStackTrace
      val p = Source.failed(ex).runWith(Sink.publisher)
      val c = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c)
      c.expectError(ex)

      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p.subscribe(c2)
      c2.expectError(ex)
    }
  }

  "Lazy Empty Source" must {
    "complete materialized future when stream cancels" in {
      val neverSource = Source.lazyEmpty()
      val pubSink = Sink.publisher

      val mat = neverSource.to(pubSink).run()

      val f = mat.get(neverSource)
      val neverPub = mat.get(pubSink)

      val c = StreamTestKit.SubscriberProbe()
      neverPub.subscribe(c)
      val subs = c.expectSubscription()

      subs.request(1000)
      c.expectNoMsg(300.millis)

      subs.cancel()
      Await.result(f.future, 300.millis)
    }

    "allow external triggering of completion" in {
      val neverSource = Source.lazyEmpty[Int]()
      val counterSink = Sink.fold[Int, Int](0) { (acc, _) ⇒ acc + 1 }

      val mat = neverSource.to(counterSink).run()

      val neverPromise = mat.get(neverSource)
      val counterFuture = mat.get(counterSink)

      // external cancellation
      neverPromise.success(())

      val ready = Await.ready(counterFuture, 200.millis)
      val Success(0) = ready.value.get
    }

    "allow external triggering of onError" in {
      val neverSource = Source.lazyEmpty()
      val counterSink = Sink.fold[Int, Int](0) { (acc, _) ⇒ acc + 1 }

      val mat = neverSource.to(counterSink).run()

      val neverPromise = mat.get(neverSource)
      val counterFuture = mat.get(counterSink)

      // external cancellation
      neverPromise.failure(new Exception("Boom") with NoStackTrace)

      val ready = Await.ready(counterFuture, 200.millis)
      val Failure(ex) = ready.value.get
      ex.getMessage should include("Boom")
    }

  }

  "Source with additional keys" must {
    "materialize keys properly" in {
      val ks = Source.subscriber[Int]
      val mk1 = new Key[String] {
        override def materialize(map: MaterializedMap) = map.get(ks).toString
      }
      val mk2 = new Key[String] {
        override def materialize(map: MaterializedMap) = map.get(mk1).toUpperCase
      }
      val sp = StreamTestKit.SubscriberProbe[Int]()
      val mm = ks.withKey(mk1).withKey(mk2).to(Sink(sp)).run()
      val s = mm.get(ks)
      mm.get(mk1) should be(s.toString)
      mm.get(mk2) should be(s.toString.toUpperCase)
      val p = Source.single(1).runWith(Sink.publisher)
      p.subscribe(s)
      val sub = sp.expectSubscription()
      sub.request(1)
      sp.expectNext(1)
      sp.expectComplete()
    }

    "materialize keys properly when used in a graph" in {
      val ks = Source.subscriber[Int]
      val mk1 = new Key[String] {
        override def materialize(map: MaterializedMap) = map.get(ks).toString
      }
      val mk2 = new Key[String] {
        override def materialize(map: MaterializedMap) = map.get(mk1).toUpperCase
      }
      val sp = StreamTestKit.SubscriberProbe[Int]()
      val mks = ks.withKey(mk1).withKey(mk2)
      val mm = FlowGraph { implicit b ⇒
        import FlowGraphImplicits._
        val bcast = Broadcast[Int]
        mks ~> bcast ~> Sink(sp)
        bcast ~> Sink.ignore
      }.run()
      val s = mm.get(ks)
      mm.get(mk1) should be(s.toString)
      mm.get(mk2) should be(s.toString.toUpperCase)
      val p = Source.single(1).runWith(Sink.publisher)
      p.subscribe(s)
      val sub = sp.expectSubscription()
      sub.request(1)
      sp.expectNext(1)
      sp.expectComplete()
    }
  }
}