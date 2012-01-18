package akka.ticket

import akka.actor._
import akka.routing._
import akka.testkit.AkkaSpec
import akka.dispatch.Await
import akka.util.duration._
import akka.pattern.ask

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class Ticket703Spec extends AkkaSpec {

  "A ? call to an actor pool" should {
    "reuse the proper timeout" in {
      val actorPool = system.actorOf(
        Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with BasicNoBackoffFilter {
          def lowerBound = 2
          def upperBound = 20
          def rampupRate = 0.1
          def partialFill = true
          def selectionCount = 1
          def receive = _route
          def pressureThreshold = 1
          def instance(p: Props) = system.actorOf(p.withCreator(new Actor {
            def receive = {
              case req: String ⇒
                Thread.sleep(6000L)
                sender.tell("Response")
            }
          }))
        }).withFaultHandler(OneForOneStrategy(List(classOf[Exception]), 5, 1000)))
      Await.result(actorPool.?("Ping", 10000), 10 seconds) must be === "Response"
    }
  }
}
