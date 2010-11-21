package akka.actor.routing

import akka.actor.Actor
import akka.actor.Actor._
import akka.util.Logging

import org.scalatest.Suite
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.MustMatchers
import org.junit.Test

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}
import akka.routing._

@RunWith(classOf[JUnitRunner])
class RoutingSpec extends junit.framework.TestCase with Suite with MustMatchers with Logging {
  import Routing._

  @Test def testDispatcher = {
    val (testMsg1,testMsg2,testMsg3,testMsg4) = ("test1","test2","test3","test4")
    val targetOk = new AtomicInteger(0)
    val t1 = actorOf( new Actor() {
          def receive = {
        case `testMsg1` => self.reply(3)
        case `testMsg2` => self.reply(7)
      }
    } ).start

    val t2 = actorOf( new Actor() {
          def receive = {
        case `testMsg3` => self.reply(11)
      }
    }).start

    val d = dispatcherActor {
      case `testMsg1`|`testMsg2` => t1
      case `testMsg3` => t2
    }.start

    val result = for {
      a <- (d !! (testMsg1, 5000)).as[Int]
      b <- (d !! (testMsg2, 5000)).as[Int]
      c <- (d !! (testMsg3, 5000)).as[Int]
    } yield a + b + c

    result.isDefined must be (true)
    result.get must be(21)

    for(a <- List(t1,t2,d)) a.stop
  }

  @Test def testLogger = {
    val msgs = new java.util.concurrent.ConcurrentSkipListSet[Any]
    val latch = new CountDownLatch(2)
    val t1 = actorOf(new Actor { def receive = { case _ => } }).start
    val l = loggerActor(t1,(x) => { msgs.add(x); latch.countDown }).start
    val foo : Any = "foo"
    val bar : Any = "bar"
    l ! foo
    l ! bar
    val done = latch.await(5,TimeUnit.SECONDS)
    done must be (true)
    msgs must ( have size (2) and contain (foo) and contain (bar) )
    t1.stop
    l.stop
  }

  @Test def testSmallestMailboxFirstDispatcher = {
    val t1ProcessedCount = new AtomicInteger(0)
    val latch = new CountDownLatch(500)
    val t1 = actorOf(new Actor {
      def receive = {
        case x =>
          Thread.sleep(50) // slow actor
          t1ProcessedCount.incrementAndGet
          latch.countDown
      }
    }).start

    val t2ProcessedCount = new AtomicInteger(0)
    val t2 = actorOf(new Actor {
      def receive = {
        case x => t2ProcessedCount.incrementAndGet
                  latch.countDown
      }
    }).start
    val d = loadBalancerActor(new SmallestMailboxFirstIterator(t1 :: t2 :: Nil))
    for (i <- 1 to 500) d ! i
    val done = latch.await(10,TimeUnit.SECONDS)
    done must be (true)
    t1ProcessedCount.get must be < (t2ProcessedCount.get) // because t1 is much slower and thus has a bigger mailbox all the time
    for(a <- List(t1,t2,d)) a.stop
  }

  @Test def testListener = {
    val latch = new CountDownLatch(2)
    val foreachListener = new CountDownLatch(2)
    val num = new AtomicInteger(0)
    val i = actorOf(new Actor with Listeners {
      def receive = listenerManagement orElse {
        case "foo" =>  gossip("bar")
      }
    })
    i.start

    def newListener = actorOf(new Actor {
      def receive = {
        case "bar" =>
          num.incrementAndGet
          latch.countDown
        case "foo" => foreachListener.countDown
      }
    }).start

    val a1 = newListener
    val a2 = newListener
    val a3 = newListener

    i ! Listen(a1)
    i ! Listen(a2)
    i ! Listen(a3)
    i ! Deafen(a3)
    i ! WithListeners(_ ! "foo")
    i ! "foo"

    val done = latch.await(5,TimeUnit.SECONDS)
    done must be (true)
    num.get must be (2)
    val withListeners = foreachListener.await(5,TimeUnit.SECONDS)
    withListeners must be (true)
    for(a <- List(i,a1,a2,a3)) a.stop
  }

  @Test def testIsDefinedAt = {
        import akka.actor.ActorRef

        val (testMsg1,testMsg2,testMsg3,testMsg4) = ("test1","test2","test3","test4")

    val t1 = actorOf( new Actor() {
      def receive = {
        case `testMsg1` => self.reply(3)
        case `testMsg2` => self.reply(7)
      }
    } ).start

    val t2 = actorOf( new Actor() {
      def receive = {
        case `testMsg1` => self.reply(3)
        case `testMsg2` => self.reply(7)
      }
    } ).start

    val t3 = actorOf( new Actor() {
      def receive = {
        case `testMsg1` => self.reply(3)
        case `testMsg2` => self.reply(7)
      }
    } ).start

    val t4 = actorOf( new Actor() {
      def receive = {
        case `testMsg1` => self.reply(3)
        case `testMsg2` => self.reply(7)
      }
    } ).start

    val d1 = loadBalancerActor(new SmallestMailboxFirstIterator(t1 :: t2 :: Nil))
    val d2 = loadBalancerActor(new CyclicIterator[ActorRef](t3 :: t4 :: Nil))

    t1.isDefinedAt(testMsg1) must be (true)
    t1.isDefinedAt(testMsg3) must be (false)
    t2.isDefinedAt(testMsg1) must be (true)
    t2.isDefinedAt(testMsg3) must be (false)
    d1.isDefinedAt(testMsg1) must be (true)
    d1.isDefinedAt(testMsg3) must be (false)
    d2.isDefinedAt(testMsg1) must be (true)
    d2.isDefinedAt(testMsg3) must be (false)

    for(a <- List(t1,t2,d1,d2)) a.stop
  }
}
