/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.actor

import scala.concurrent.duration._
import akka.actor.ActorRef
import akka.actor.Props
import akka.stream.FlowMaterializer
import akka.stream.MaterializerSettings
import akka.stream.scaladsl.Flow
import akka.stream.testkit.AkkaSpec
import akka.stream.actor.ActorSubscriber.RequestStrategy
import akka.actor.Actor
import akka.routing.ActorRefRoutee
import akka.routing.Router
import akka.routing.RoundRobinRoutingLogic
import akka.testkit.ImplicitSender
import scala.util.control.NoStackTrace

object ActorSubscriberSpec {

  def manualSubscriberProps(probe: ActorRef): Props =
    Props(new ManualSubscriber(probe)).withDispatcher("akka.test.stream-dispatcher")

  class ManualSubscriber(probe: ActorRef) extends ActorSubscriber {
    import ActorSubscriber._

    override val requestStrategy = ZeroRequestStrategy

    def receive = {
      case next @ OnNext(elem)   ⇒ probe ! next
      case complete @ OnComplete ⇒ probe ! complete
      case err @ OnError(cause)  ⇒ probe ! err
      case "ready"               ⇒ request(elements = 2)
      case "boom"                ⇒ throw new RuntimeException("boom") with NoStackTrace
      case "requestAndCancel"    ⇒ { request(1); cancel() }
    }
  }

  def requestStrategySubscriberProps(probe: ActorRef, strat: RequestStrategy): Props =
    Props(new RequestStrategySubscriber(probe, strat)).withDispatcher("akka.test.stream-dispatcher")

  class RequestStrategySubscriber(probe: ActorRef, strat: RequestStrategy) extends ActorSubscriber {
    import ActorSubscriber._

    override val requestStrategy = strat

    def receive = {
      case next @ OnNext(elem)   ⇒ probe ! next
      case complete @ OnComplete ⇒ probe ! complete
    }
  }

  case class Msg(id: Int, replyTo: ActorRef)
  case class Work(id: Int)
  case class Reply(id: Int)
  case class Done(id: Int)

  def streamerProps: Props =
    Props(new Streamer).withDispatcher("akka.test.stream-dispatcher")

  class Streamer extends ActorSubscriber {
    import ActorSubscriber._
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
        queue += (id -> replyTo)
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

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorSubscriberSpec extends AkkaSpec with ImplicitSender {
  import ActorSubscriberSpec._
  import ActorSubscriber._

  val materializer = FlowMaterializer(MaterializerSettings(dispatcher = "akka.test.stream-dispatcher"))

  "An ActorSubscriber" must {

    "receive requested elements" in {
      val ref = system.actorOf(manualSubscriberProps(testActor))
      Flow(List(1, 2, 3)).produceTo(materializer, ActorSubscriber(ref))
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
      val ref = system.actorOf(manualSubscriberProps(testActor))
      val e = new RuntimeException("simulated") with NoStackTrace
      Flow(() ⇒ throw e).produceTo(materializer, ActorSubscriber(ref))
      ref ! "ready"
      expectMsg(OnError(e))
    }

    "remember requested after restart" in {
      val ref = system.actorOf(manualSubscriberProps(testActor))
      Flow(1 to 7).produceTo(materializer, ActorSubscriber(ref))
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
      val ref = system.actorOf(manualSubscriberProps(testActor))
      Flow(1 to 5).produceTo(materializer, ActorSubscriber(ref))
      ref ! "ready"
      expectMsg(OnNext(1))
      expectMsg(OnNext(2))
      ref ! "requestAndCancel"
      expectNoMsg(200.millis)
    }

    "work with OneByOneRequestStrategy" in {
      val ref = system.actorOf(requestStrategySubscriberProps(testActor, OneByOneRequestStrategy))
      Flow(1 to 17).produceTo(materializer, ActorSubscriber(ref))
      for (n ← 1 to 17) expectMsg(OnNext(n))
      expectMsg(OnComplete)
    }

    "work with WatermarkRequestStrategy" in {
      val ref = system.actorOf(requestStrategySubscriberProps(testActor, WatermarkRequestStrategy(highWatermark = 10)))
      Flow(1 to 17).produceTo(materializer, ActorSubscriber(ref))
      for (n ← 1 to 17) expectMsg(OnNext(n))
      expectMsg(OnComplete)
    }

    "suport custom max in flight request strategy with child workers" in {
      val ref = system.actorOf(streamerProps)
      val N = 117
      Flow(1 to N).map(Msg(_, testActor)).produceTo(materializer, ActorSubscriber(ref))
      receiveN(N).toSet should be((1 to N).map(Done(_)).toSet)
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
