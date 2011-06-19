package akka.actor.routing

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.testkit._
import akka.testkit.Testing.sleepFor
import akka.util.duration._

import akka.actor._
import akka.actor.Actor._
import akka.routing._

import java.util.concurrent.atomic.AtomicInteger
import akka.dispatch.{ KeptPromise, Future }

object RoutingSpec {
  trait Foo {
    def sq(x: Int, sleep: Long): Future[Int]
  }

  class FooImpl extends Foo {
    def sq(x: Int, sleep: Long): Future[Int] = {
      if (sleep > 0) Thread.sleep(sleep)
      new KeptPromise(Right(x * x))
    }
  }
}

class RoutingSpec extends WordSpec with MustMatchers {
  import Routing._

  "Routing" must {

    "dispatch" in {
      val Test1 = "test1"
      val Test2 = "test2"
      val Test3 = "test3"

      val t1 = actorOf(new Actor {
        def receive = {
          case Test1 ⇒ self.reply(3)
          case Test2 ⇒ self.reply(7)
        }
      }).start()

      val t2 = actorOf(new Actor() {
        def receive = {
          case Test3 ⇒ self.reply(11)
        }
      }).start()

      val d = routerActor {
        case Test1 | Test2 ⇒ t1
        case Test3         ⇒ t2
      }.start()

      implicit val timeout = Actor.Timeout((5 seconds).dilated)
      val result = for {
        a ← (d ? (Test1)).as[Int]
        b ← (d ? (Test2)).as[Int]
        c ← (d ? (Test3)).as[Int]
      } yield a + b + c

      result.isDefined must be(true)
      result.get must be(21)

      for (a ← List(t1, t2, d)) a.stop()
    }

    "have messages logged" in {
      val msgs = new java.util.concurrent.ConcurrentSkipListSet[Any]
      val latch = TestLatch(2)

      val actor = actorOf(new Actor {
        def receive = { case _ ⇒ }
      }).start()

      val logger = loggerActor(actor, x ⇒ { msgs.add(x); latch.countDown() }).start()

      val foo: Any = "foo"
      val bar: Any = "bar"

      logger ! foo
      logger ! bar

      latch.await

      msgs must (have size (2) and contain(foo) and contain(bar))

      actor.stop()
      logger.stop()
    }

    "dispatch to smallest mailbox" in {
      val t1Count = new AtomicInteger(0)
      val t2Count = new AtomicInteger(0)
      val latch = TestLatch(500)

      val t1 = actorOf(new Actor {
        def receive = {
          case x ⇒
            sleepFor(50 millis) // slow actor
            t1Count.incrementAndGet
            latch.countDown()
        }
      }).start()

      val t2 = actorOf(new Actor {
        def receive = {
          case x ⇒
            t2Count.incrementAndGet
            latch.countDown()
        }
      }).start()

      val d = loadBalancerActor(new SmallestMailboxFirstIterator(t1 :: t2 :: Nil))

      for (i ← 1 to 500) d ! i

      try {
        latch.await(10 seconds)
      } finally {
        // because t1 is much slower and thus has a bigger mailbox all the time
        t1Count.get must be < (t2Count.get)
      }

      for (a ← List(t1, t2, d)) a.stop()
    }

    "listen" in {
      val fooLatch = TestLatch(2)
      val barLatch = TestLatch(2)
      val barCount = new AtomicInteger(0)

      val broadcast = actorOf(new Actor with Listeners {
        def receive = listenerManagement orElse {
          case "foo" ⇒ gossip("bar")
        }
      }).start()

      def newListener = actorOf(new Actor {
        def receive = {
          case "bar" ⇒
            barCount.incrementAndGet
            barLatch.countDown()
          case "foo" ⇒
            fooLatch.countDown()
        }
      }).start()

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

  "Actor Pool" must {

    "have expected capacity" in {
      val latch = TestLatch(2)
      val count = new AtomicInteger(0)

      val pool = actorOf(
        new Actor with DefaultActorPool with FixedCapacityStrategy with SmallestMailboxSelector {
          def factory = actorOf(new Actor {
            def receive = {
              case _ ⇒
                count.incrementAndGet
                latch.countDown()
                self reply_? "success"
            }
          }).start()

          def limit = 2
          def selectionCount = 1
          def partialFill = true
          def instance = factory
          def receive = _route
        }).start()

      val successes = TestLatch(2)
      val successCounter = actorOf(new Actor {
        def receive = {
          case "success" ⇒ successes.countDown()
        }
      }).start()

      implicit val replyTo = successCounter
      pool ! "a"
      pool ! "b"

      latch.await
      successes.await

      count.get must be(2)

      (pool ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)

      pool.stop()
    }

    "pass ticket #705" in {
      val pool = actorOf(
        new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with BasicFilter {
          def lowerBound = 2
          def upperBound = 20
          def rampupRate = 0.1
          def backoffRate = 0.1
          def backoffThreshold = 0.5
          def partialFill = true
          def selectionCount = 1
          def instance = factory
          def receive = _route
          def pressureThreshold = 1
          def factory = actorOf(new Actor {
            def receive = {
              case req: String ⇒ {
                sleepFor(10 millis)
                self.reply_?("Response")
              }
            }
          })
        }).start()

      try {
        (for (count ← 1 to 500) yield pool.?("Test", 20000)) foreach {
          _.await.resultOrException.get must be("Response")
        }
      } finally {
        pool.stop()
      }
    }

    "grow as needed under pressure" in {
      // make sure the pool starts at the expected lower limit and grows to the upper as needed
      // as influenced by the backlog of blocking pooled actors

      var latch = TestLatch(3)
      val count = new AtomicInteger(0)

      val pool = actorOf(
        new Actor with DefaultActorPool with BoundedCapacityStrategy with ActiveFuturesPressureCapacitor with SmallestMailboxSelector with BasicNoBackoffFilter {
          def factory = actorOf(new Actor {
            def receive = {
              case n: Int ⇒
                sleepFor(n millis)
                count.incrementAndGet
                latch.countDown()
            }
          })

          def lowerBound = 2
          def upperBound = 4
          def rampupRate = 0.1
          def partialFill = true
          def selectionCount = 1
          def instance = factory
          def receive = _route
        }).start()

      // first message should create the minimum number of delgates

      pool ! 1

      (pool ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)

      var loops = 0
      def loop(t: Int) = {
        latch = TestLatch(loops)
        count.set(0)
        for (m ← 0 until loops) {
          pool ? t
          sleepFor(50 millis)
        }
      }

      // 2 more should go thru without triggering more

      loops = 2

      loop(500)
      latch.await
      count.get must be(loops)

      (pool ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)

      // a whole bunch should max it out

      loops = 10
      loop(500)
      latch.await
      count.get must be(loops)

      (pool ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(4)

      pool.stop()
    }

    "grow as needed under mailbox pressure" in {
      // make sure the pool starts at the expected lower limit and grows to the upper as needed
      // as influenced by the backlog of messages in the delegate mailboxes

      var latch = TestLatch(3)
      val count = new AtomicInteger(0)

      val pool = actorOf(
        new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with BasicNoBackoffFilter {
          def factory = actorOf(new Actor {
            def receive = {
              case n: Int ⇒
                sleepFor(n millis)
                count.incrementAndGet
                latch.countDown()
            }
          })

          def lowerBound = 2
          def upperBound = 4
          def pressureThreshold = 3
          def rampupRate = 0.1
          def partialFill = true
          def selectionCount = 1
          def instance = factory
          def receive = _route
        }).start()

      var loops = 0
      def loop(t: Int) = {
        latch = TestLatch(loops)
        count.set(0)
        for (m ← 0 until loops) {
          pool ! t
        }
      }

      // send a few messages and observe pool at its lower bound
      loops = 3
      loop(500)
      latch.await
      count.get must be(loops)

      (pool ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)

      // send a bunch over the theshold and observe an increment
      loops = 15
      loop(500)

      latch.await(10 seconds)
      count.get must be(loops)

      (pool ? ActorPool.Stat).as[ActorPool.Stats].get.size must be >= (3)

      pool.stop()
    }

    "round robin" in {
      val latch1 = TestLatch(2)
      val delegates = new java.util.concurrent.ConcurrentHashMap[String, String]

      val pool1 = actorOf(
        new Actor with DefaultActorPool with FixedCapacityStrategy with RoundRobinSelector with BasicNoBackoffFilter {
          def factory = actorOf(new Actor {
            def receive = {
              case _ ⇒
                delegates put (self.uuid.toString, "")
                latch1.countDown()
            }
          })

          def limit = 1
          def selectionCount = 1
          def rampupRate = 0.1
          def partialFill = true
          def instance = factory
          def receive = _route
        }).start()

      pool1 ! "a"
      pool1 ! "b"

      latch1.await
      delegates.size must be(1)

      pool1.stop()

      val latch2 = TestLatch(2)
      delegates.clear()

      val pool2 = actorOf(
        new Actor with DefaultActorPool with FixedCapacityStrategy with RoundRobinSelector with BasicNoBackoffFilter {
          def factory = actorOf(new Actor {
            def receive = {
              case _ ⇒
                delegates put (self.uuid.toString, "")
                latch2.countDown()
            }
          })

          def limit = 2
          def selectionCount = 1
          def rampupRate = 0.1
          def partialFill = false
          def instance = factory
          def receive = _route
        }).start()

      pool2 ! "a"
      pool2 ! "b"

      latch2.await
      delegates.size must be(2)

      pool2.stop()
    }

    "backoff" in {
      val latch = TestLatch(10)

      val pool = actorOf(
        new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with Filter with RunningMeanBackoff with BasicRampup {
          def factory = actorOf(new Actor {
            def receive = {
              case n: Int ⇒
                sleepFor(n millis)
                latch.countDown()
            }
          })

          def lowerBound = 1
          def upperBound = 5
          def pressureThreshold = 1
          def partialFill = true
          def selectionCount = 1
          def rampupRate = 0.1
          def backoffRate = 0.50
          def backoffThreshold = 0.50
          def instance = factory
          def receive = _route
        }).start()

      // put some pressure on the pool

      for (m ← 0 to 10) pool ! 250

      sleepFor(5 millis)

      val z = (pool ? ActorPool.Stat).as[ActorPool.Stats].get.size

      z must be >= (2)

      // let it cool down

      for (m ← 0 to 3) {
        pool ! 1
        sleepFor(500 millis)
      }

      (pool ? ActorPool.Stat).as[ActorPool.Stats].get.size must be <= (z)

      pool.stop()
    }

    "support typed actors" in {
      import RoutingSpec._
      import TypedActor._
      def createPool = new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with Filter with RunningMeanBackoff with BasicRampup {
        def lowerBound = 1
        def upperBound = 5
        def pressureThreshold = 1
        def partialFill = true
        def selectionCount = 1
        def rampupRate = 0.1
        def backoffRate = 0.50
        def backoffThreshold = 0.50
        def instance = getActorRefFor(typedActorOf[Foo, FooImpl]())
        def receive = _route
      }

      val pool = createProxy[Foo](createPool)

      val results = for (i ← 1 to 100) yield (i, pool.sq(i, 100))

      for ((i, r) ← results) r.get must equal(i * i)
    }
  }
}

