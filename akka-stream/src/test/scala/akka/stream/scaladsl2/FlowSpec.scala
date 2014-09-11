/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.actor.{ Props, ActorRefFactory, ActorRef }
import akka.stream.impl.TransformProcessorImpl
import akka.stream.impl2.Ast.{ Transform, AstNode }
import akka.stream.impl2.{ ActorProcessorFactory, StreamSupervisor, ActorBasedFlowMaterializer }
import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import akka.stream.testkit2.ChainSetup
import akka.stream.{ TransformerLike, MaterializerSettings }
import akka.testkit.TestEvent.{ UnMute, Mute }
import akka.testkit._
import com.typesafe.config.ConfigFactory
import java.util.concurrent.atomic.AtomicLong
import org.reactivestreams.{ Processor, Subscriber, Publisher }
import scala.collection.immutable
import scala.concurrent.duration._

object FlowSpec {
  class Fruit
  class Apple extends Fruit

  val flowNameCounter = new AtomicLong(0)

  case class BrokenMessage(msg: String)

  class BrokenTransformProcessorImpl(
    _settings: MaterializerSettings,
    transformer: TransformerLike[Any, Any],
    brokenMessage: Any) extends TransformProcessorImpl(_settings, transformer) {

    import akka.stream.actor.ActorSubscriberMessage._

    override protected[akka] def aroundReceive(receive: Receive, msg: Any) = {
      msg match {
        case OnNext(m) if m == brokenMessage ⇒
          throw new NullPointerException(s"I'm so broken [$m]")
        case _ ⇒ super.aroundReceive(receive, msg)
      }
    }
  }

  class BrokenFlowMaterializer(
    settings: MaterializerSettings,
    supervisor: ActorRef,
    flowNameCounter: AtomicLong,
    namePrefix: String,
    brokenMessage: Any) extends ActorBasedFlowMaterializer(settings, supervisor, flowNameCounter, namePrefix) {

    override protected def processorForNode(op: AstNode, flowName: String, n: Int): Processor[Any, Any] = {
      val props = op match {
        case t: Transform ⇒ Props(new BrokenTransformProcessorImpl(settings, t.mkTransformer(), brokenMessage))
        case o            ⇒ ActorProcessorFactory.props(this, o)
      }
      val impl = actorOf(props, s"$flowName-$n-${op.name}")
      ActorProcessorFactory(impl)
    }

  }

  def createBrokenFlowMaterializer(settings: MaterializerSettings, brokenMessage: Any)(implicit context: ActorRefFactory): BrokenFlowMaterializer = {
    new BrokenFlowMaterializer(settings,
      context.actorOf(StreamSupervisor.props(settings).withDispatcher(settings.dispatcher)),
      flowNameCounter,
      "brokenflow",
      brokenMessage)
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowSpec extends AkkaSpec(ConfigFactory.parseString("akka.actor.debug.receive=off\nakka.loglevel=INFO")) {
  import FlowSpec._

  val settings = MaterializerSettings(system)
    .withInputBuffer(initialSize = 2, maxSize = 16)
    .withFanOutBuffer(initialSize = 1, maxSize = 16)

  implicit val mat = FlowMaterializer(settings)

  val identity: ProcessorFlow[Any, Any] ⇒ ProcessorFlow[Any, Any] = in ⇒ in.map(e ⇒ e)
  val identity2: ProcessorFlow[Any, Any] ⇒ ProcessorFlow[Any, Any] = in ⇒ identity(in)

  val toPublisher: (FlowWithSource[Any, Any], FlowMaterializer) ⇒ Publisher[Any] =
    (f, m) ⇒ f.toPublisher()(m)
  def toFanoutPublisher[In, Out](initialBufferSize: Int, maximumBufferSize: Int): (FlowWithSource[In, Out], FlowMaterializer) ⇒ Publisher[Out] =
    (f, m) ⇒ f.toFanoutPublisher(initialBufferSize, maximumBufferSize)(m)

  def materializeIntoSubscriberAndPublisher[In, Out](processorFlow: ProcessorFlow[In, Out]): (Subscriber[In], Publisher[Out]) = {
    val source = SubscriberSource[In]
    val sink = PublisherSink[Out]
    val mf = processorFlow.withSource(source).withSink(sink).run()
    (source.subscriber(mf), sink.publisher(mf))
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
        EventFilter[WeirdError.type](occurrences = 1) intercept {
          upstreamSubscription.sendError(WeirdError)
          downstream.expectError(WeirdError)
        }
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
      val processorFlow = FlowFrom[String]
      val (flowIn: Subscriber[String], flowOut: Publisher[String]) = materializeIntoSubscriberAndPublisher(processorFlow)

      val c1 = StreamTestKit.SubscriberProbe[String]()
      flowOut.subscribe(c1)

      val source: Publisher[String] = FlowFrom(List("1", "2", "3")).toPublisher()
      source.subscribe(flowIn)

      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "materialize into Publisher/Subscriber and transformation processor" in {
      val processorFlow = FlowFrom[Int].map((i: Int) ⇒ i.toString)
      val (flowIn: Subscriber[Int], flowOut: Publisher[String]) = materializeIntoSubscriberAndPublisher(processorFlow)

      val c1 = StreamTestKit.SubscriberProbe[String]()
      flowOut.subscribe(c1)
      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNoMsg(200.millis)

      val source: Publisher[Int] = FlowFrom(List(1, 2, 3)).toPublisher()
      source.subscribe(flowIn)

      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "materialize into Publisher/Subscriber and multiple transformation processors" in {
      val processorFlow = FlowFrom[Int].map(_.toString).map("elem-" + _)
      val (flowIn, flowOut) = materializeIntoSubscriberAndPublisher(processorFlow)

      val c1 = StreamTestKit.SubscriberProbe[String]()
      flowOut.subscribe(c1)
      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNoMsg(200.millis)

      val source: Publisher[Int] = FlowFrom(List(1, 2, 3)).toPublisher()
      source.subscribe(flowIn)

      c1.expectNext("elem-1")
      c1.expectNext("elem-2")
      c1.expectNext("elem-3")
      c1.expectComplete
    }

    "subscribe Subscriber" in {
      val processorFlow: ProcessorFlow[String, String] = FlowFrom[String]
      val c1 = StreamTestKit.SubscriberProbe[String]()
      val flow: FlowWithSink[String, String] = processorFlow.withSink(SubscriberSink(c1))
      val source: Publisher[String] = FlowFrom(List("1", "2", "3")).toPublisher()
      flow.withSource(PublisherSource(source)).run()

      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "perform transformation operation" in {
      val processorFlow = FlowFrom[Int].map(i ⇒ { testActor ! i.toString; i.toString })

      val source = FlowFrom(List(1, 2, 3)).toPublisher()
      processorFlow.withSource(PublisherSource(source)).consume()

      expectMsg("1")
      expectMsg("2")
      expectMsg("3")
    }

    "perform transformation operation and subscribe Subscriber" in {
      val processorFlow = FlowFrom[Int].map(_.toString)
      val c1 = StreamTestKit.SubscriberProbe[String]()
      val flow: FlowWithSink[Int, String] = processorFlow.withSink(SubscriberSink(c1))
      val source: Publisher[Int] = FlowFrom(List(1, 2, 3)).toPublisher()
      flow.withSource(PublisherSource(source)).run()

      val sub1 = c1.expectSubscription
      sub1.request(3)
      c1.expectNext("1")
      c1.expectNext("2")
      c1.expectNext("3")
      c1.expectComplete
    }

    "be covariant" in {
      val f1: FlowWithSource[Fruit, Fruit] = FlowFrom[Fruit](() ⇒ Some(new Apple))
      val p1: Publisher[Fruit] = FlowFrom[Fruit](() ⇒ Some(new Apple)).toPublisher()
      val f2: FlowWithSource[Fruit, FlowWithSource[Fruit, Fruit]] = FlowFrom[Fruit](() ⇒ Some(new Apple)).splitWhen(_ ⇒ true)
      val f3: FlowWithSource[Fruit, (Boolean, FlowWithSource[Fruit, Fruit])] = FlowFrom[Fruit](() ⇒ Some(new Apple)).groupBy(_ ⇒ true)
      val f4: FlowWithSource[Fruit, (immutable.Seq[Fruit], FlowWithSource[Fruit, Fruit])] = FlowFrom[Fruit](() ⇒ Some(new Apple)).prefixAndTail(1)
      val d1: ProcessorFlow[String, FlowWithSource[Fruit, Fruit]] = FlowFrom[String].map(_ ⇒ new Apple).splitWhen(_ ⇒ true)
      val d2: ProcessorFlow[String, (Boolean, FlowWithSource[Fruit, Fruit])] = FlowFrom[String].map(_ ⇒ new Apple).groupBy(_ ⇒ true)
      val d3: ProcessorFlow[String, (immutable.Seq[Apple], FlowWithSource[Fruit, Fruit])] = FlowFrom[String].map(_ ⇒ new Apple).prefixAndTail(1)
    }
  }

  "A Flow with multiple subscribers (FanOutBox)" must {
    "adapt speed to the currently slowest subscriber" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1),
        toFanoutPublisher(initialBufferSize = 1, maximumBufferSize = 1)) {
        val downstream2 = StreamTestKit.SubscriberProbe[Any]()
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
        val downstream2 = StreamTestKit.SubscriberProbe[Any]()
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
        val downstream2 = StreamTestKit.SubscriberProbe[Any]()
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
        val downstream2 = StreamTestKit.SubscriberProbe[Any]()
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

    "call future subscribers' onComplete instead of onSubscribed after initial upstream was completed" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1),
        toFanoutPublisher(initialBufferSize = 1, maximumBufferSize = 1)) {
        val downstream2 = StreamTestKit.SubscriberProbe[Any]()
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

        // FIXME when adding a sleep before the following link this will fail with IllegalStateExc shut-down
        // what is the expected shutdown behavior? Is the title of this test wrong?
        //        val downstream3 = StreamTestKit.SubscriberProbe[Any]()
        //        publisher.subscribe(downstream3)
        //        downstream3.expectComplete()
      }
    }

    "call future subscribers' onError should be called instead of onSubscribed after initial upstream reported an error" in {
      new ChainSetup[Int, String](_.map(_ ⇒ throw TestException), settings.copy(initialInputBufferSize = 1),
        toFanoutPublisher(initialBufferSize = 1, maximumBufferSize = 1)) {
        downstreamSubscription.request(1)
        upstreamSubscription.expectRequest(1)

        EventFilter[TestException.type](occurrences = 2) intercept {
          upstreamSubscription.sendNext(5)
          upstreamSubscription.expectRequest(1)
          upstreamSubscription.expectCancellation()
          downstream.expectError(TestException)
        }

        val downstream2 = StreamTestKit.SubscriberProbe[String]()
        publisher.subscribe(downstream2)
        downstream2.expectError() should be(TestException)
      }
    }

    "call future subscribers' onError when all subscriptions were cancelled" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1),
        toFanoutPublisher(initialBufferSize = 1, maximumBufferSize = 16)) {
        upstreamSubscription.expectRequest(1)
        downstreamSubscription.cancel()
        upstreamSubscription.expectCancellation()

        val downstream2 = StreamTestKit.SubscriberProbe[Any]()
        publisher.subscribe(downstream2)
        // IllegalStateException shut down
        downstream2.expectError().isInstanceOf[IllegalStateException] should be(true)
      }
    }
  }

  "A broken Flow" must {
    "cancel upstream and call onError on current and future downstream subscribers if an internal error occurs" in {
      new ChainSetup(identity, settings.copy(initialInputBufferSize = 1), (s, f) ⇒ createBrokenFlowMaterializer(s, "a3")(f),
        toFanoutPublisher(initialBufferSize = 1, maximumBufferSize = 16)) {

        def checkError(sprobe: StreamTestKit.SubscriberProbe[Any]): Unit = {
          val error = sprobe.expectError()
          error.isInstanceOf[IllegalStateException] should be(true)
          error.getMessage should be("Processor actor terminated abruptly")
        }

        val downstream2 = StreamTestKit.SubscriberProbe[Any]()
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

        val filters = immutable.Seq(EventFilter[NullPointerException](), EventFilter[IllegalStateException]())
        try {
          system.eventStream.publish(Mute(filters))

          EventFilter[akka.actor.PreRestartException](occurrences = 1) intercept {
            upstream.expectRequest(upstreamSubscription, 1)
            upstreamSubscription.sendNext("a3")
            upstreamSubscription.expectCancellation()

            // IllegalStateException terminated abruptly
            checkError(downstream)
            checkError(downstream2)

            val downstream3 = StreamTestKit.SubscriberProbe[Any]()
            publisher.subscribe(downstream3)
            // IllegalStateException terminated abruptly
            checkError(downstream3)
          }
        } finally {
          system.eventStream.publish(UnMute(filters))
        }
      }
    }
  }

  object TestException extends RuntimeException

}
