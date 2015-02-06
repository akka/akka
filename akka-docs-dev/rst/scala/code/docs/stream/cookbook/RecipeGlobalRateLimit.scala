package docs.stream.cookbook

import akka.actor.{ Props, ActorRef, Actor }
import akka.actor.Actor.Receive
import akka.stream.scaladsl._
import akka.stream.testkit.StreamTestKit.SubscriberProbe

import scala.collection.immutable
import scala.concurrent.duration._

class RecipeGlobalRateLimit extends RecipeSpec {

  "Global rate limiting recipe" must {

    //#global-limiter-actor
    object Limiter {
      case object WantToPass
      case object MayPass

      case object ReplenishTokens

      def props(maxAvailableTokens: Int, tokenRefreshPeriod: FiniteDuration, tokenRefreshAmount: Int): Props =
        Props(new Limiter(maxAvailableTokens, tokenRefreshPeriod, tokenRefreshAmount))
    }

    class Limiter(
      val maxAvailableTokens: Int,
      val tokenRefreshPeriod: FiniteDuration,
      val tokenRefreshAmount: Int) extends Actor {
      import Limiter._
      import context.dispatcher
      import akka.actor.Status

      private var waitQueue = immutable.Queue.empty[ActorRef]
      private var permitTokens = maxAvailableTokens
      private val replenishTimer = system.scheduler.schedule(
        initialDelay = tokenRefreshPeriod,
        interval = tokenRefreshPeriod,
        receiver = self,
        ReplenishTokens)

      override def receive: Receive = open

      val open: Receive = {
        case ReplenishTokens =>
          permitTokens = math.min(permitTokens + tokenRefreshAmount, maxAvailableTokens)
        case WantToPass =>
          permitTokens -= 1
          sender() ! MayPass
          if (permitTokens == 0) context.become(closed)
      }

      val closed: Receive = {
        case ReplenishTokens =>
          permitTokens = math.min(permitTokens + tokenRefreshAmount, maxAvailableTokens)
          releaseWaiting()
        case WantToPass =>
          waitQueue = waitQueue.enqueue(sender())
      }

      private def releaseWaiting(): Unit = {
        val (toBeReleased, remainingQueue) = waitQueue.splitAt(permitTokens)
        waitQueue = remainingQueue
        permitTokens -= toBeReleased.size
        toBeReleased foreach (_ ! MayPass)
        if (permitTokens > 0) context.become(open)
      }

      override def postStop(): Unit = {
        replenishTimer.cancel()
        waitQueue foreach (_ ! Status.Failure(new IllegalStateException("limiter stopped")))
      }
    }
    //#global-limiter-actor

    "work" in {

      //#global-limiter-flow
      def limitGlobal[T](limiter: ActorRef, maxAllowedWait: FiniteDuration): Flow[T, T] = {
        import akka.pattern.ask
        import akka.util.Timeout
        Flow[T].mapAsync { (element: T) =>
          import system.dispatcher
          implicit val triggerTimeout = Timeout(maxAllowedWait)
          val limiterTriggerFuture = limiter ? Limiter.WantToPass
          limiterTriggerFuture.map((_) => element)
        }
      }
      //#global-limiter-flow

      // Use a large period and emulate the timer by hand instead
      val limiter = system.actorOf(Limiter.props(2, 100.days, 1), "limiter")

      val source1 = Source(() => Iterator.continually("E1")).via(limitGlobal(limiter, 2.seconds))
      val source2 = Source(() => Iterator.continually("E2")).via(limitGlobal(limiter, 2.seconds))

      val probe = SubscriberProbe[String]()

      FlowGraph { implicit b =>
        import FlowGraphImplicits._
        val merge = Merge[String]
        source1 ~> merge ~> Sink(probe)
        source2 ~> merge
      }.run()

      probe.expectSubscription().request(1000)

      probe.expectNext() should startWith("E")
      probe.expectNext() should startWith("E")
      probe.expectNoMsg(500.millis)

      limiter ! Limiter.ReplenishTokens
      probe.expectNext() should startWith("E")
      probe.expectNoMsg(500.millis)

      var resultSet = Set.empty[String]
      for (_ <- 1 to 100) {
        limiter ! Limiter.ReplenishTokens
        resultSet += probe.expectNext()
      }

      resultSet.contains("E1") should be(true)
      resultSet.contains("E2") should be(true)

      probe.expectError()

    }

  }

}
