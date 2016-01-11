/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import java.util.concurrent.atomic.AtomicLong
import akka.dispatch.Dispatchers
import akka.stream.Supervision._
import akka.stream.impl.Stages.StageModule
import akka.stream.stage.Stage
import scala.collection.immutable
import scala.concurrent.duration._
import akka.actor._
import akka.stream.ActorFlowMaterializerSettings
import akka.stream.ActorFlowMaterializer
import akka.stream.impl._
import akka.stream.testkit._
import akka.stream.testkit.Utils._
import akka.testkit._
import akka.testkit.TestEvent.{ UnMute, Mute }
import com.typesafe.config.ConfigFactory
import org.reactivestreams.{ Subscription, Processor, Subscriber, Publisher }
import akka.stream.impl.fusing.ActorInterpreter
import scala.util.control.NoStackTrace

object FlowSpec {
  class Fruit
  class Apple extends Fruit
  val apples = () ⇒ Iterator.continually(new Apple)

}

class FlowSpec extends AkkaSpec(ConfigFactory.parseString("akka.actor.debug.receive=off\nakka.loglevel=INFO")) {
  import FlowSpec._

  val settings = ActorFlowMaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)

  implicit val mat = ActorFlowMaterializer(settings)

  val identity: Flow[Any, Any, _] ⇒ Flow[Any, Any, _] = in ⇒ in.map(e ⇒ e)
  val identity2: Flow[Any, Any, _] ⇒ Flow[Any, Any, _] = in ⇒ identity(in)

  class BrokenActorInterpreter(
    _settings: ActorFlowMaterializerSettings,
    _ops: Seq[Stage[_, _]],
    brokenMessage: Any)
    extends ActorInterpreter(_settings, _ops, mat) {

    import akka.stream.actor.ActorSubscriberMessage._

    override protected[akka] def aroundReceive(receive: Receive, msg: Any) = {
      msg match {
        case OnNext(m) if m == brokenMessage ⇒
          throw new NullPointerException(s"I'm so broken [$m]")
        case _ ⇒ super.aroundReceive(receive, msg)
      }
    }
  }

  val faultyFlow: Flow[Any, Any, _] ⇒ Flow[Any, Any, _] = in ⇒ in.andThenMat { () ⇒
    val props = Props(new BrokenActorInterpreter(settings, List(fusing.Map({ x: Any ⇒ x }, stoppingDecider)), "a3"))
      .withDispatcher("akka.test.stream-dispatcher")
    val processor = ActorProcessorFactory[Any, Any](system.actorOf(
      props,
      "borken-stage-actor"))
    (processor, ())
  }

  val toPublisher: (Source[Any, _], ActorFlowMaterializer) ⇒ Publisher[Any] =
    (f, m) ⇒ f.runWith(Sink.publisher)(m)

  def toFanoutPublisher[In, Out](initialBufferSize: Int, maximumBufferSize: Int): (Source[Out, _], ActorFlowMaterializer) ⇒ Publisher[Out] =
    (f, m) ⇒ f.runWith(Sink.fanoutPublisher(initialBufferSize, maximumBufferSize))(m)

  def materializeIntoSubscriberAndPublisher[In, Out](flow: Flow[In, Out, _]): (Subscriber[In], Publisher[Out]) = {
    val source = Source.subscriber[In]
    val sink = Sink.publisher[Out]
    flow.runWith(source, sink)
  }

  "A Flow" must {

    for ((name, op) ← List("identity" -> identity, "identity2" -> identity2); n ← List(1, 2, 4)) {
      s"request initial elements from upstream ($name, $n)" in {
        new ChainSetup(op, settings.withInputBuffer(initialSize = n, maxSize = settings.maxInputBufferSize), toPublisher) {
          upstream.expectRequest(upstreamSubscription, settings.initialInputBufferSize)
        }
      }
    }

    "request more elements from upstream when downstream requests more elements" in {
      new ChainSetup(identity, settings, toPublisher) {
        upstream.expectRequest(upstreamSubscription, settings.initialInputBufferSize)
        downstreamSubscription.request(1)
        upstream.expectNoMsg(100.millis)
        downstreamSubscription.request(2)
        upstream.expectNoMsg(100.millis)
        upstreamSubscription.sendNext("a")
        downstream.expectNext("a")
        upstream.expectRequest(upstreamSubscription, 1)
        upstream.expectNoMsg(100.millis)
        upstreamSubscription.sendNext("b")
        upstreamSubscription.sendNext("c")
        upstreamSubscription.sendNext("d")
        downstream.expectNext("b")
        downstream.expectNext("c")
      }
    }

    "deliver events when publisher sends elements and then completes" in {
      new ChainSetup(identity, settings, toPublisher) {
        downstreamSubscription.request(1)
        upstreamSubscription.sendNext("test")
        upstreamSubscription.sendComplete()
        downstream.expectNext("test")
        downstream.expectComplete()
      }
    }

    "deliver complete signal when publisher immediately completes" in {
      new ChainSetup(identity, settings, toPublisher) {
        upstreamSubscription.sendComplete()
        downstream.expectComplete()
      }
    }

    "deliver error signal when publisher immediately fails" in {
      new ChainSetup(identity, settings, toPublisher) {
        object WeirdError extends RuntimeException("weird test exception")
        upstreamSubscription.sendError(WeirdError)
        downstream.expectError(WeirdError)
      }
    }

    "cancel upstream when single subscriber cancels subscription while receiving data" in {
      new ChainSetup(identity, settings.withInputBuffer(initialSize = 1, maxSize = settings.maxInputBufferSize), toPublisher) {
        downstreamSubscription.request(5)
        upstreamSubscription.expectRequest(1)
        upstreamSubscription.sendNext("test")
        upstreamSubscription.expectRequest(1)
        upstreamSubscription.sendNext("test2")
        upstreamSubscription.expectRequest(1)
        downstream.expectNext("test")
        downstream.expectNext("test2")
        downstreamSubscription.cancel()

        // because of the "must cancel its upstream Subscription if its last downstream Subscription has been cancelled" rule
        upstreamSubscription.expectCancellation()
      }
    }

    "materialize into Publisher/Subscriber" in {
      val flow = Flow[String]
      val (flowIn: Subscriber[String], flowOut: Publisher[String]) = materializeIntoSubscriberAndPublisher(flow)

      val c1 = TestSubscriber.manualProbe[String]()
      flowOut.subscribe(c1)

      val source: Publisher[String] = Source(List("1", "2", "3")).runWith(Sink.publisher)
      source.subscribe(flowIn)

      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "materialize into Publisher/Subscriber and transformation processor" in {
      val flow = Flow[Int].map((i: Int) ⇒ i.toString)
      val (flowIn: Subscriber[Int], flowOut: Publisher[String]) = materializeIntoSubscriberAndPublisher(flow)

      val c1 = TestSubscriber.manualProbe[String]()
      flowOut.subscribe(c1)
      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNoMsg(200.millis)

      val source: Publisher[Int] = Source(List(1, 2, 3)).runWith(Sink.publisher)
      source.subscribe(flowIn)

      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "materialize into Publisher/Subscriber and multiple transformation processors" in {
      val flow = Flow[Int].map(_.toString).map("elem-" + _)
      val (flowIn, flowOut) = materializeIntoSubscriberAndPublisher(flow)

      val c1 = TestSubscriber.manualProbe[String]()
      flowOut.subscribe(c1)
      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNoMsg(200.millis)

      val source: Publisher[Int] = Source(List(1, 2, 3)).runWith(Sink.publisher)
      source.subscribe(flowIn)

      c1.expectNext("elem-1")
      c1.expectNext("elem-2")
      c1.expectNext("elem-3")
      c1.expectComplete
    }

    "subscribe Subscriber" in {
      val flow: Flow[String, String, _] = Flow[String]
      val c1 = TestSubscriber.manualProbe[String]()
      val sink: Sink[String, _] = flow.to(Sink(c1))
      val publisher: Publisher[String] = Source(List("1", "2", "3")).runWith(Sink.publisher)
      Source(publisher).to(sink).run()

      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "perform transformation operation" in {
      val flow = Flow[Int].map(i ⇒ { testActor ! i.toString; i.toString })

      val publisher = Source(List(1, 2, 3)).runWith(Sink.publisher)
      Source(publisher).via(flow).to(Sink.ignore).run()

      expectMsg("1")
      expectMsg("2")
      expectMsg("3")
    }

    "perform transformation operation and subscribe Subscriber" in {
      val flow = Flow[Int].map(_.toString)
      val c1 = TestSubscriber.manualProbe[String]()
      val sink: Sink[Int, _] = flow.to(Sink(c1))
      val publisher: Publisher[Int] = Source(List(1, 2, 3)).runWith(Sink.publisher)
      Source(publisher).to(sink).run()

      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "be materializable several times with fanout publisher" in assertAllStagesStopped {
      val flow = Source(List(1, 2, 3)).map(_.toString)
      val p1 = flow.runWith(Sink.fanoutPublisher(2, 2))
      val p2 = flow.runWith(Sink.fanoutPublisher(2, 2))
      val s1 = TestSubscriber.manualProbe[String]()
      val s2 = TestSubscriber.manualProbe[String]()
      val s3 = TestSubscriber.manualProbe[String]()
      p1.subscribe(s1)
      p2.subscribe(s2)
      p2.subscribe(s3)

      val sub1 = s1.expectSubscription
      val sub2 = s2.expectSubscription
      val sub3 = s3.expectSubscription

      sub1.request(3)
      s1.expectNext("1")
      s1.expectNext("2")
      s1.expectNext("3")
      s1.expectComplete

      sub2.request(3)
      sub3.request(3)
      s2.expectNext("1")
      s2.expectNext("2")
      s2.expectNext("3")
      s2.expectComplete
      s3.expectNext("1")
      s3.expectNext("2")
      s3.expectNext("3")
      s3.expectComplete
    }

    "be covariant" in {
      val f1: Source[Fruit, _] = Source[Fruit](apples)
      val p1: Publisher[Fruit] = Source[Fruit](apples).runWith(Sink.publisher)
      val f2: Source[Source[Fruit, _], _] = Source[Fruit](apples).splitWhen(_ ⇒ true)
      val f3: Source[(Boolean, Source[Fruit, _]), _] = Source[Fruit](apples).groupBy(_ ⇒ true)
      val f4: Source[(immutable.Seq[Fruit], Source[Fruit, _]), _] = Source[Fruit](apples).prefixAndTail(1)
      val d1: Flow[String, Source[Fruit, _], _] = Flow[String].map(_ ⇒ new Apple).splitWhen(_ ⇒ true)
      val d2: Flow[String, (Boolean, Source[Fruit, _]), _] = Flow[String].map(_ ⇒ new Apple).groupBy(_ ⇒ true)
      val d3: Flow[String, (immutable.Seq[Apple], Source[Fruit, _]), _] = Flow[String].map(_ ⇒ new Apple).prefixAndTail(1)
    }

    "be able to concat with a Source" in {
      val f1: Flow[Int, String, _] = Flow[Int].map(_.toString + "-s")
      val s1: Source[Int, _] = Source(List(1, 2, 3))
      val s2: Source[String, _] = Source(List(4, 5, 6)).map(_.toString + "-s")

      val subs = TestSubscriber.manualProbe[Any]()
      val subSink = Sink.publisher[Any]

      val (_, res) = f1.concat(s2).runWith(s1, subSink)

      res.subscribe(subs)
      val sub = subs.expectSubscription()
      sub.request(9)
      subs.expectNext("1-s")
      subs.expectNext("2-s")
      subs.expectNext("3-s")
      subs.expectNext("4-s")
      subs.expectNext("5-s")
      subs.expectNext("6-s")
      subs.expectComplete()
    }
  }

  "A Flow with multiple subscribers (FanOutBox)" must {
    "adapt speed to the currently slowest subscriber" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1),
        toFanoutPublisher(initialBufferSize = 1, maximumBufferSize = 1)) {
        val downstream2 = TestSubscriber.manualProbe[Any]()
        publisher.subscribe(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        downstreamSubscription.request(5)
        upstream.expectRequest(upstreamSubscription, 1) // because initialInputBufferSize=1

        upstreamSubscription.sendNext("firstElement")
        downstream.expectNext("firstElement")

        upstream.expectRequest(upstreamSubscription, 1)
        upstreamSubscription.sendNext("element2")

        downstream.expectNoMsg(1.second)
        downstream2Subscription.request(1)
        downstream2.expectNext("firstElement")

        downstream.expectNext("element2")

        downstream2Subscription.request(1)
        downstream2.expectNext("element2")
      }
    }

    "support slow subscriber with fan-out 2" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1),
        toFanoutPublisher(initialBufferSize = 2, maximumBufferSize = 2)) {
        val downstream2 = TestSubscriber.manualProbe[Any]()
        publisher.subscribe(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        downstreamSubscription.request(5)

        upstream.expectRequest(upstreamSubscription, 1) // because initialInputBufferSize=1
        upstreamSubscription.sendNext("element1")
        downstream.expectNext("element1")
        upstreamSubscription.expectRequest(1)
        upstreamSubscription.sendNext("element2")
        downstream.expectNext("element2")
        upstreamSubscription.expectRequest(1)
        upstreamSubscription.sendNext("element3")
        // downstream2 has not requested anything, fan-out buffer 2
        downstream.expectNoMsg(100.millis.dilated)

        downstream2Subscription.request(2)
        downstream.expectNext("element3")
        downstream2.expectNext("element1")
        downstream2.expectNext("element2")
        downstream2.expectNoMsg(100.millis.dilated)

        upstreamSubscription.expectRequest(1)
        upstreamSubscription.sendNext("element4")
        downstream.expectNext("element4")

        downstream2Subscription.request(2)
        downstream2.expectNext("element3")
        downstream2.expectNext("element4")

        upstreamSubscription.sendComplete()
        downstream.expectComplete()
        downstream2.expectComplete()
      }
    }

    "support incoming subscriber while elements were requested before" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1),
        toFanoutPublisher(initialBufferSize = 1, maximumBufferSize = 1)) {
        downstreamSubscription.request(5)
        upstream.expectRequest(upstreamSubscription, 1)
        upstreamSubscription.sendNext("a1")
        downstream.expectNext("a1")

        upstream.expectRequest(upstreamSubscription, 1)
        upstreamSubscription.sendNext("a2")
        downstream.expectNext("a2")

        upstream.expectRequest(upstreamSubscription, 1)

        // link now while an upstream element is already requested
        val downstream2 = TestSubscriber.manualProbe[Any]()
        publisher.subscribe(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        // situation here:
        // downstream 1 now has 3 outstanding
        // downstream 2 has 0 outstanding

        upstreamSubscription.sendNext("a3")
        downstream.expectNext("a3")
        downstream2.expectNoMsg(100.millis.dilated) // as nothing was requested yet, fanOutBox needs to cache element in this case

        downstream2Subscription.request(1)
        downstream2.expectNext("a3")

        // d1 now has 2 outstanding
        // d2 now has 0 outstanding
        // buffer should be empty so we should be requesting one new element

        upstream.expectRequest(upstreamSubscription, 1) // because of buffer size 1
      }
    }

    "be unblocked when blocking subscriber cancels subscription" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1),
        toFanoutPublisher(initialBufferSize = 1, maximumBufferSize = 1)) {
        val downstream2 = TestSubscriber.manualProbe[Any]()
        publisher.subscribe(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        downstreamSubscription.request(5)
        upstreamSubscription.expectRequest(1)
        upstreamSubscription.sendNext("firstElement")
        downstream.expectNext("firstElement")

        downstream2Subscription.request(1)
        downstream2.expectNext("firstElement")
        upstreamSubscription.expectRequest(1)
        upstreamSubscription.sendNext("element2")

        downstream.expectNext("element2")
        upstreamSubscription.expectRequest(1)
        upstreamSubscription.sendNext("element3")
        upstreamSubscription.expectRequest(1)

        downstream.expectNoMsg(200.millis.dilated)
        downstream2.expectNoMsg(200.millis.dilated)
        upstream.expectNoMsg(200.millis.dilated)

        // should unblock fanoutbox
        downstream2Subscription.cancel()
        downstream.expectNext("element3")
        upstreamSubscription.sendNext("element4")
        downstream.expectNext("element4")

        upstreamSubscription.sendComplete()
        downstream.expectComplete()
      }
    }

    "call future subscribers' onError after onSubscribe if initial upstream was completed" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1),
        toFanoutPublisher(initialBufferSize = 1, maximumBufferSize = 1)) {
        val downstream2 = TestSubscriber.manualProbe[Any]()
        // don't link it just yet

        downstreamSubscription.request(5)
        upstream.expectRequest(upstreamSubscription, 1)
        upstreamSubscription.sendNext("a1")
        downstream.expectNext("a1")

        upstream.expectRequest(upstreamSubscription, 1)
        upstreamSubscription.sendNext("a2")
        downstream.expectNext("a2")

        upstream.expectRequest(upstreamSubscription, 1)

        // link now while an upstream element is already requested
        publisher.subscribe(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        upstreamSubscription.sendNext("a3")
        upstreamSubscription.sendComplete()
        downstream.expectNext("a3")
        downstream.expectComplete()

        downstream2.expectNoMsg(100.millis.dilated) // as nothing was requested yet, fanOutBox needs to cache element in this case

        downstream2Subscription.request(1)
        downstream2.expectNext("a3")
        downstream2.expectComplete()

        val downstream3 = TestSubscriber.manualProbe[Any]()
        publisher.subscribe(downstream3)
        downstream3.expectSubscription()
        downstream3.expectError() should ===(ActorPublisher.NormalShutdownReason)
      }
    }

    "call future subscribers' onError should be called instead of onSubscribed after initial upstream reported an error" in {
      new ChainSetup[Int, String](_.map(_ ⇒ throw TestException), settings.copy(initialInputBufferSize = 1),
        toFanoutPublisher(initialBufferSize = 1, maximumBufferSize = 1)) {
        downstreamSubscription.request(1)
        upstreamSubscription.expectRequest(1)

        upstreamSubscription.sendNext(5)
        upstreamSubscription.expectRequest(1)
        upstreamSubscription.expectCancellation()
        downstream.expectError(TestException)

        val downstream2 = TestSubscriber.manualProbe[String]()
        publisher.subscribe(downstream2)
        downstream2.expectSubscriptionAndError() should be(TestException)
      }
    }

    "call future subscribers' onError when all subscriptions were cancelled" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1),
        toFanoutPublisher(initialBufferSize = 1, maximumBufferSize = 16)) {
        upstreamSubscription.expectRequest(1)
        downstreamSubscription.cancel()
        upstreamSubscription.expectCancellation()

        val downstream2 = TestSubscriber.manualProbe[Any]()
        publisher.subscribe(downstream2)
        // IllegalStateException shut down
        downstream2.expectSubscriptionAndError().isInstanceOf[IllegalStateException] should be(true)
      }
    }
  }

  "A broken Flow" must {
    "cancel upstream and call onError on current and future downstream subscribers if an internal error occurs" in {
      new ChainSetup(faultyFlow, settings.copy(initialInputBufferSize = 1), toFanoutPublisher(initialBufferSize = 1, maximumBufferSize = 16)) {

        def checkError(sprobe: TestSubscriber.ManualProbe[Any]): Unit = {
          val error = sprobe.expectError()
          error.isInstanceOf[IllegalStateException] should be(true)
          error.getMessage should be("Processor actor terminated abruptly")
        }

        val downstream2 = TestSubscriber.manualProbe[Any]()
        publisher.subscribe(downstream2)
        val downstream2Subscription = downstream2.expectSubscription()

        downstreamSubscription.request(5)
        downstream2Subscription.request(5)
        upstream.expectRequest(upstreamSubscription, 1)
        upstreamSubscription.sendNext("a1")
        downstream.expectNext("a1")
        downstream2.expectNext("a1")

        upstream.expectRequest(upstreamSubscription, 1)
        upstreamSubscription.sendNext("a2")
        downstream.expectNext("a2")
        downstream2.expectNext("a2")

        val filters = immutable.Seq(
          EventFilter[NullPointerException](),
          EventFilter[IllegalStateException](),
          EventFilter[PostRestartException]()) // This is thrown because we attach the dummy failing actor to toplevel
        try {
          system.eventStream.publish(Mute(filters))

          upstream.expectRequest(upstreamSubscription, 1)
          upstreamSubscription.sendNext("a3")
          upstreamSubscription.expectCancellation()

          // IllegalStateException terminated abruptly
          checkError(downstream)
          checkError(downstream2)

          val downstream3 = TestSubscriber.manualProbe[Any]()
          publisher.subscribe(downstream3)
          downstream3.expectSubscription()
          // IllegalStateException terminated abruptly
          checkError(downstream3)
        } finally {
          system.eventStream.publish(UnMute(filters))
        }
      }
    }
  }

  object TestException extends RuntimeException with NoStackTrace

}
