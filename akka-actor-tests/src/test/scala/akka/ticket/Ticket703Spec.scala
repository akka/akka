package akka.ticket

import akka.actor.Actor._
import akka.actor._
import akka.routing._
import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.config.Supervision.OneForOnePermanentStrategy

class Ticket703Spec extends WordSpec with MustMatchers {

  "A ? call to an actor pool" should {
    "reuse the proper timeout" in {
      val actorPool = actorOf(
        Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with BasicNoBackoffFilter {
          def lowerBound = 2
          def upperBound = 20
          def rampupRate = 0.1
          def partialFill = true
          def selectionCount = 1
          def instance = factory
          def receive = _route
          def pressureThreshold = 1
          def factory = actorOf(new Actor {
            def receive = {
              case req: String ⇒
                Thread.sleep(6000L)
                self.tryReply("Response")
            }
          })
        }).withFaultHandler(OneForOnePermanentStrategy(List(classOf[Exception]), 5, 1000)))
      (actorPool.?("Ping", 7000)).await.result must be === Some("Response")
    }
  }
}
