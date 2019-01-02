/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.actor

import akka.actor.{ Actor, ActorRef, Props }
import akka.routing.{ ActorRefRoutee, RoundRobinRoutingLogic, Router }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.testkit.StreamSpec
import akka.testkit.ImplicitSender
import org.reactivestreams.Subscription

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

object ActorSubscriberSpec {

  def manualSubscriberProps(probe: ActorRef): Props =
    Props(new ManualSubscriber(probe)).withDispatcher("akka.test.stream-dispatcher")

  class ManualSubscriber(probe: ActorRef) extends ActorSubscriber {
    import ActorSubscriberMessage._

    override val requestStrategy = ZeroRequestStrategy

    def receive = {
      case next @ OnNext(elem)  ⇒ probe ! next
      case OnComplete           ⇒ probe ! OnComplete
      case err @ OnError(cause) ⇒ probe ! err
      case "ready"              ⇒ request(elements = 2)
      case "boom"               ⇒ throw new RuntimeException("boom") with NoStackTrace
      case "requestAndCancel"   ⇒ { request(1); cancel() }
      case "cancel"             ⇒ cancel()
    }
  }

  def immediatelyCancelledSubscriberProps(probe: ActorRef): Props =
    Props(new ImmediatelyCancelledSubscriber(probe)).withDispatcher("akka.test.stream-dispatcher")

  class ImmediatelyCancelledSubscriber(probe: ActorRef) extends ManualSubscriber(probe) {
    override val requestStrategy = ZeroRequestStrategy
    override def preStart() = {
      cancel()
      super.preStart()
    }
  }

  def requestStrategySubscriberProps(probe: ActorRef, strat: RequestStrategy): Props =
    Props(new RequestStrategySubscriber(probe, strat)).withDispatcher("akka.test.stream-dispatcher")

  class RequestStrategySubscriber(probe: ActorRef, strat: RequestStrategy) extends ActorSubscriber {
    import ActorSubscriberMessage._

    override val requestStrategy = strat

    def receive = {
      case next @ OnNext(elem) ⇒ probe ! next
      case OnComplete          ⇒ probe ! OnComplete
    }
  }

  case class Msg(id: Int, replyTo: ActorRef)
  case class Work(id: Int)
  case class Reply(id: Int)
  case class Done(id: Int)

  def streamerProps: Props =
    Props(new Streamer).withDispatcher("akka.test.stream-dispatcher")

  class Streamer extends ActorSubscriber {
    import ActorSubscriberMessage._
    var queue = Map.empty[Int, ActorRef]

    val router = {
      val routees = Vector.fill(3) {
        ActorRefRoutee(context.actorOf(Props[Worker].withDispatcher(context.props.dispatcher)))
      }
      Router(RoundRobinRoutingLogic(), routees)
    }

    override val requestStrategy = new MaxInFlightRequestStrategy(max = 10) {
      override def inFlightInternally: Int = queue.size
    }

    def receive = {
      case OnNext(Msg(id, replyTo)) ⇒
        queue += (id → replyTo)
        assert(queue.size <= 10, s"queued too many: ${queue.size}")
        router.route(Work(id), self)
      case Reply(id) ⇒
        queue(id) ! Done(id)
        queue -= id
    }
  }

  class Worker extends Actor {
    def receive = {
      case Work(id) ⇒
        // ...
        sender() ! Reply(id)
    }
  }
}

class ActorSubscriberSpec extends StreamSpec with ImplicitSender {
  import ActorSubscriberMessage._
  import ActorSubscriberSpec._

  implicit val materializer = ActorMaterializer()

  "An ActorSubscriber" must {

    "receive requested elements" in {
      val ref = Source(List(1, 2, 3)).runWith(Sink.actorSubscriber(manualSubscriberProps(testActor)))
      expectNoMsg(200.millis)
      ref ! "ready" // requesting 2
      expectMsg(OnNext(1))
      expectMsg(OnNext(2))
      expectNoMsg(200.millis)
      ref ! "ready"
      expectMsg(OnNext(3))
      expectMsg(OnComplete)
    }

    "signal error" in {
      val e = new RuntimeException("simulated") with NoStackTrace
      val ref = Source.fromIterator(() ⇒ throw e).runWith(Sink.actorSubscriber(manualSubscriberProps(testActor)))
      ref ! "ready"
      expectMsg(OnError(e))
    }

    "remember requested after restart" in {
      // creating actor with default supervision, because stream supervisor default strategy is to stop
      val ref = system.actorOf(manualSubscriberProps(testActor))
      Source(1 to 7).runWith(Sink.fromSubscriber(ActorSubscriber[Int](ref)))
      ref ! "ready"
      expectMsg(OnNext(1))
      expectMsg(OnNext(2))
      expectNoMsg(200.millis) // nothing requested
      ref ! "boom"
      ref ! "ready"
      ref ! "ready"
      ref ! "boom"
      (3 to 6) foreach { n ⇒ expectMsg(OnNext(n)) }
      expectNoMsg(200.millis)
      ref ! "ready"
      expectMsg(OnNext(7))
      expectMsg(OnComplete)
    }

    "not deliver more after cancel" in {
      val ref = Source(1 to 5).runWith(Sink.actorSubscriber(manualSubscriberProps(testActor)))
      ref ! "ready"
      expectMsg(OnNext(1))
      expectMsg(OnNext(2))
      ref ! "requestAndCancel"
      expectNoMsg(200.millis)
    }

    "terminate after cancel" in {
      val ref = Source(1 to 5).runWith(Sink.actorSubscriber(manualSubscriberProps(testActor)))
      watch(ref)
      ref ! "requestAndCancel"
      expectTerminated(ref, 200.millis)
    }

    "cancel incoming subscription when cancel() was called before it arrived" in {
      val ref = system.actorOf(immediatelyCancelledSubscriberProps(testActor))
      val sub = ActorSubscriber(ref)
      watch(ref)
      expectNoMsg(200.millis)

      sub.onSubscribe(new Subscription {
        override def cancel(): Unit = testActor ! "cancel"
        override def request(n: Long): Unit = ()
      })
      expectMsg("cancel")
      expectTerminated(ref, 200.millis)
    }

    "work with OneByOneRequestStrategy" in {
      Source(1 to 17).runWith(Sink.actorSubscriber(requestStrategySubscriberProps(testActor, OneByOneRequestStrategy)))
      for (n ← 1 to 17) expectMsg(OnNext(n))
      expectMsg(OnComplete)
    }

    "work with WatermarkRequestStrategy" in {
      Source(1 to 17).runWith(Sink.actorSubscriber(requestStrategySubscriberProps(testActor, WatermarkRequestStrategy(highWatermark = 10))))
      for (n ← 1 to 17) expectMsg(OnNext(n))
      expectMsg(OnComplete)
    }

    "suport custom max in flight request strategy with child workers" in {
      val N = 117
      Source(1 to N).map(Msg(_, testActor)).runWith(Sink.actorSubscriber(streamerProps))
      receiveN(N).toSet should be((1 to N).map(Done).toSet)
    }

  }

  "Provided RequestStragies" must {
    "implement OneByOne correctly" in {
      val strat = OneByOneRequestStrategy
      strat.requestDemand(0) should be(1)
      strat.requestDemand(1) should be(0)
      strat.requestDemand(2) should be(0)
    }

    "implement Zero correctly" in {
      val strat = ZeroRequestStrategy
      strat.requestDemand(0) should be(0)
      strat.requestDemand(1) should be(0)
      strat.requestDemand(2) should be(0)
    }

    "implement Watermark correctly" in {
      val strat = WatermarkRequestStrategy(highWatermark = 10)
      strat.requestDemand(0) should be(10)
      strat.requestDemand(9) should be(0)
      strat.requestDemand(6) should be(0)
      strat.requestDemand(5) should be(0)
      strat.requestDemand(4) should be(6)
    }

    "implement MaxInFlight with batchSize=1 correctly" in {
      var queue = Set.empty[String]
      val strat = new MaxInFlightRequestStrategy(max = 10) {
        override def batchSize: Int = 1
        def inFlightInternally: Int = queue.size
      }
      strat.requestDemand(0) should be(10)
      strat.requestDemand(9) should be(1)
      queue += "a"
      strat.requestDemand(0) should be(9)
      strat.requestDemand(8) should be(1)
      strat.requestDemand(9) should be(0)
      queue += "b"
      queue += "c"
      strat.requestDemand(5) should be(2)
      ('d' to 'j') foreach { queue += _.toString }
      queue.size should be(10)
      strat.requestDemand(0) should be(0)
      strat.requestDemand(1) should be(0)
      queue += "g"
      strat.requestDemand(0) should be(0)
      strat.requestDemand(1) should be(0)
    }

    "implement MaxInFlight with batchSize=3 correctly" in {
      var queue = Set.empty[String]
      val strat = new MaxInFlightRequestStrategy(max = 10) {
        override def batchSize: Int = 3
        override def inFlightInternally: Int = queue.size
      }
      strat.requestDemand(0) should be(10)
      queue += "a"
      strat.requestDemand(9) should be(0)
      queue += "b"
      strat.requestDemand(8) should be(0)
      queue += "c"
      strat.requestDemand(7) should be(0)
      queue += "d"
      strat.requestDemand(6) should be(0)
      queue -= "a" // 3 remaining in queue
      strat.requestDemand(6) should be(0)
      queue -= "b" // 2 remaining in queue
      strat.requestDemand(6) should be(0)
      queue -= "c" // 1 remaining in queue
      strat.requestDemand(6) should be(3)
    }

    "implement MaxInFlight with batchSize=max correctly" in {
      var queue = Set.empty[String]
      val strat = new MaxInFlightRequestStrategy(max = 3) {
        override def batchSize: Int = 5 // will be bounded to max
        override def inFlightInternally: Int = queue.size
      }
      strat.requestDemand(0) should be(3)
      queue += "a"
      strat.requestDemand(2) should be(0)
      queue += "b"
      strat.requestDemand(1) should be(0)
      queue += "c"
      strat.requestDemand(0) should be(0)
      queue -= "a"
      strat.requestDemand(0) should be(0)
      queue -= "b"
      strat.requestDemand(0) should be(0)
      queue -= "c"
      strat.requestDemand(0) should be(3)
    }

  }

}
