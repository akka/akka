package akka.routing

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers
import akka.dispatch.{ KeptPromise, Future }
import akka.actor._
import akka.actor.Actor._
import akka.testkit.Testing._
import akka.actor.{ TypedActor, Actor, Props }
import akka.testkit.{ TestLatch, filterEvents, EventFilter, filterException }
import akka.util.duration._
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

object ActorPoolSpec {

  trait Foo {
    def sq(x: Int, sleep: Long): Future[Int]
  }

  class FooImpl extends Foo {
    def sq(x: Int, sleep: Long): Future[Int] = {
      if (sleep > 0) Thread.sleep(sleep)
      new KeptPromise(Right(x * x))
    }
  }

  val faultHandler = OneForOneStrategy(List(classOf[Exception]), 5, 1000)
}

class ActorPoolSpec extends WordSpec with MustMatchers {
  import akka.routing.ActorPoolSpec._

  "Actor Pool" must {

    "have expected capacity" in {
      val latch = TestLatch(2)
      val count = new AtomicInteger(0)

      val pool = actorOf(
        Props(new Actor with DefaultActorPool with FixedCapacityStrategy with SmallestMailboxSelector {
          def factory = actorOf(new Actor {
            def receive = {
              case _ ⇒
                count.incrementAndGet
                latch.countDown()
                tryReply("success")
            }
          })

          def limit = 2
          def selectionCount = 1
          def partialFill = true
          def instance = factory
          def receive = _route
        }).withFaultHandler(faultHandler))

      val successes = TestLatch(2)
      val successCounter = actorOf(new Actor {
        def receive = {
          case "success" ⇒ successes.countDown()
        }
      })

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
        Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with BasicFilter {
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
                tryReply("Response")
              }
            }
          })
        }).withFaultHandler(faultHandler))

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
        Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with ActiveFuturesPressureCapacitor with SmallestMailboxSelector with BasicNoBackoffFilter {
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
        }).withFaultHandler(faultHandler))

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
        Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with BasicNoBackoffFilter {
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
        }).withFaultHandler(faultHandler))

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
        Props(new Actor with DefaultActorPool with FixedCapacityStrategy with RoundRobinSelector with BasicNoBackoffFilter {
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
        }).withFaultHandler(faultHandler))

      pool1 ! "a"
      pool1 ! "b"

      latch1.await
      delegates.size must be(1)

      pool1.stop()

      val latch2 = TestLatch(2)
      delegates.clear()

      val pool2 = actorOf(
        Props(new Actor with DefaultActorPool with FixedCapacityStrategy with RoundRobinSelector with BasicNoBackoffFilter {
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
        }).withFaultHandler(faultHandler))

      pool2 ! "a"
      pool2 ! "b"

      latch2.await
      delegates.size must be(2)

      pool2.stop()
    }

    "backoff" in {
      val latch = TestLatch(10)

      val pool = actorOf(
        Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with Filter with RunningMeanBackoff with BasicRampup {
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
        }).withFaultHandler(faultHandler))

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

      val pool = createProxy[Foo](createPool, Props().withFaultHandler(faultHandler))

      val results = for (i ← 1 to 100) yield (i, pool.sq(i, 100))

      for ((i, r) ← results) r.get must equal(i * i)
    }

    "provide default supervision of pooled actors" in {
      filterException[RuntimeException] {
        val pingCount = new AtomicInteger(0)
        val deathCount = new AtomicInteger(0)
        val keepDying = new AtomicBoolean(false)

        val pool1 = actorOf(
          Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with ActiveFuturesPressureCapacitor with SmallestMailboxSelector with BasicFilter {
            def lowerBound = 2
            def upperBound = 5
            def rampupRate = 0.1
            def backoffRate = 0.1
            def backoffThreshold = 0.5
            def partialFill = true
            def selectionCount = 1
            def instance = factory
            def receive = _route
            def pressureThreshold = 1
            def factory = actorOf(Props(new Actor {
              if (deathCount.get > 5) deathCount.set(0)
              if (deathCount.get > 0) { deathCount.incrementAndGet; throw new IllegalStateException("keep dying") }
              def receive = {
                case akka.Die ⇒
                  if (keepDying.get) deathCount.incrementAndGet
                  throw new RuntimeException
                case _ ⇒ pingCount.incrementAndGet
              }
            }).withSupervisor(self))
          }).withFaultHandler(faultHandler))

        val pool2 = actorOf(
          Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with ActiveFuturesPressureCapacitor with SmallestMailboxSelector with BasicFilter {
            def lowerBound = 2
            def upperBound = 5
            def rampupRate = 0.1
            def backoffRate = 0.1
            def backoffThreshold = 0.5
            def partialFill = true
            def selectionCount = 1
            def instance = factory
            def receive = _route
            def pressureThreshold = 1
            def factory = actorOf(Props(new Actor {
              if (deathCount.get > 5) deathCount.set(0)
              if (deathCount.get > 0) { deathCount.incrementAndGet; throw new IllegalStateException("keep dying") }
              def receive = {
                case akka.Die ⇒
                  if (keepDying.get) deathCount.incrementAndGet
                  throw new RuntimeException
                case _ ⇒ pingCount.incrementAndGet
              }
            }).withSupervisor(self))
          }).withFaultHandler(faultHandler))

        val pool3 = actorOf(
          Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with ActiveFuturesPressureCapacitor with RoundRobinSelector with BasicFilter {
            def lowerBound = 2
            def upperBound = 5
            def rampupRate = 0.1
            def backoffRate = 0.1
            def backoffThreshold = 0.5
            def partialFill = true
            def selectionCount = 1
            def instance = factory
            def receive = _route
            def pressureThreshold = 1
            def factory = actorOf(Props(new Actor {

              if (deathCount.get > 5) deathCount.set(0)
              if (deathCount.get > 0) { deathCount.incrementAndGet; throw new IllegalStateException("keep dying") }
              def receive = {
                case akka.Die ⇒
                  if (keepDying.get)
                    deathCount.incrementAndGet

                  throw new RuntimeException
                case _ ⇒ pingCount.incrementAndGet
              }
            }).withSupervisor(self))
          }).withFaultHandler(OneForOneStrategy(List(classOf[Exception]), Some(0))))

        // default lifecycle
        // actor comes back right away
        pingCount.set(0)
        keepDying.set(false)
        pool1 ! "ping"
        (pool1 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pool1 ! akka.Die
        sleepFor(2 seconds)
        (pool1 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pingCount.get must be(1)

        // default lifecycle
        // actor dies completely
        pingCount.set(0)
        keepDying.set(true)
        pool1 ! "ping"
        (pool1 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pool1 ! akka.Die
        sleepFor(2 seconds)
        (pool1 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(1)
        pool1 ! "ping"
        (pool1 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pingCount.get must be(2)

        // permanent lifecycle
        // actor comes back right away
        pingCount.set(0)
        keepDying.set(false)
        pool2 ! "ping"
        (pool2 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pool2 ! akka.Die
        sleepFor(2 seconds)
        (pool2 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pingCount.get must be(1)

        // permanent lifecycle
        // actor dies completely
        pingCount.set(0)
        keepDying.set(true)
        pool2 ! "ping"
        (pool2 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pool2 ! akka.Die
        sleepFor(2 seconds)
        (pool2 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(1)
        pool2 ! "ping"
        (pool2 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pingCount.get must be(2)

        // temporary lifecycle
        pingCount.set(0)
        keepDying.set(false)
        pool3 ! "ping"
        (pool3 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pool3 ! akka.Die
        sleepFor(2 seconds)
        (pool3 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(1)
        pool3 ! "ping"
        pool3 ! "ping"
        pool3 ! "ping"
        (pool3 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pingCount.get must be(4)
      }
    }

    "support customizable supervision config of pooled actors" in {
      filterEvents(EventFilter[IllegalStateException], EventFilter[RuntimeException]) {
        val pingCount = new AtomicInteger(0)
        val deathCount = new AtomicInteger(0)
        var keepDying = new AtomicBoolean(false)

        object BadState

        val pool1 = actorOf(
          Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with ActiveFuturesPressureCapacitor with SmallestMailboxSelector with BasicFilter {
            def lowerBound = 2
            def upperBound = 5
            def rampupRate = 0.1
            def backoffRate = 0.1
            def backoffThreshold = 0.5
            def partialFill = true
            def selectionCount = 1
            def instance = factory
            def receive = _route
            def pressureThreshold = 1
            def factory = actorOf(Props(new Actor {
              if (deathCount.get > 5) deathCount.set(0)
              if (deathCount.get > 0) { deathCount.incrementAndGet; throw new IllegalStateException("keep dying") }
              def receive = {
                case BadState ⇒
                  if (keepDying.get) deathCount.incrementAndGet
                  throw new IllegalStateException
                case akka.Die ⇒
                  throw new RuntimeException
                case _ ⇒ pingCount.incrementAndGet
              }
            }).withSupervisor(self))
          }).withFaultHandler(OneForOneStrategy(List(classOf[IllegalStateException]), 5, 1000)))

        // actor comes back right away
        pingCount.set(0)
        keepDying.set(false)
        pool1 ! "ping"
        (pool1 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pool1 ! BadState
        sleepFor(2 seconds)
        (pool1 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pingCount.get must be(1)

        // actor dies completely
        pingCount.set(0)
        keepDying.set(true)
        pool1 ! "ping"
        (pool1 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pool1 ! BadState
        sleepFor(2 seconds)
        (pool1 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(1)
        pool1 ! "ping"
        (pool1 ? ActorPool.Stat).as[ActorPool.Stats].get.size must be(2)
        pingCount.get must be(2)

        // kill it
        intercept[RuntimeException](pool1.?(akka.Die).get)
      }
    }
  }
}
