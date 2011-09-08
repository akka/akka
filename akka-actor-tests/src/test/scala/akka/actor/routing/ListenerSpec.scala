package akka.actor.routing

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.testkit._
import akka.actor._
import akka.actor.Actor._
import akka.routing._
import java.util.concurrent.atomic.AtomicInteger

class ListenerSpec extends WordSpec with MustMatchers {

  "Listener" must {

    "listen" in {
      val fooLatch = TestLatch(2)
      val barLatch = TestLatch(2)
      val barCount = new AtomicInteger(0)

      val broadcast = actorOf(new Actor with Listeners {
        def receive = listenerManagement orElse {
          case "foo" ⇒ gossip("bar")
        }
      })

      def newListener = actorOf(new Actor {
        def receive = {
          case "bar" ⇒
            barCount.incrementAndGet
            barLatch.countDown()
          case "foo" ⇒
            fooLatch.countDown()
        }
      })

      val a1 = newListener
      val a2 = newListener
      val a3 = newListener

      broadcast ! Listen(a1)
      broadcast ! Listen(a2)
      broadcast ! Listen(a3)

      broadcast ! Deafen(a3)

      broadcast ! WithListeners(_ ! "foo")
      broadcast ! "foo"

      barLatch.await
      barCount.get must be(2)

      fooLatch.await

      for (a ← List(broadcast, a1, a2, a3)) a.stop()
    }
  }
}
