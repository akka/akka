package akka.routing

import akka.dispatch.{ KeptPromise, Future }
import akka.actor._
import akka.testkit._
import akka.util.duration._
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import akka.testkit.AkkaSpec

object ActorPoolSpec {

  trait Foo {
    def sq(x: Int, sleep: Long): Future[Int]
  }

  class FooImpl extends Foo {
    import TypedActor.dispatcher
    def sq(x: Int, sleep: Long): Future[Int] = {
      if (sleep > 0) Thread.sleep(sleep)
      new KeptPromise(Right(x * x))
    }
  }

  val faultHandler = OneForOneStrategy(List(classOf[Exception]), 5, 1000)
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class TypedActorPoolSpec extends AkkaSpec {
  import ActorPoolSpec._
  "Actor Pool (2)" must {
    "support typed actors" in {
      val ta = TypedActor(system)
      val pool = ta.createProxy[Foo](new Actor with DefaultActorPool with BoundedCapacityStrategy with MailboxPressureCapacitor with SmallestMailboxSelector with Filter with RunningMeanBackoff with BasicRampup {
        val typedActor = TypedActor(context)
        def lowerBound = 1
        def upperBound = 5
        def pressureThreshold = 1
        def partialFill = true
        def selectionCount = 1
        def rampupRate = 0.1
        def backoffRate = 0.50
        def backoffThreshold = 0.50
        def instance(p: Props) = typedActor.getActorRefFor(typedActor.typedActorOf[Foo, FooImpl](props = p.withTimeout(10 seconds)))
        def receive = _route
      }, Props().withTimeout(10 seconds).withFaultHandler(faultHandler))

      val results = for (i ← 1 to 100) yield (i, pool.sq(i, 0))

      for ((i, r) ← results)
        r.get must equal(i * i)

      ta.stop(pool)
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class ActorPoolSpec extends AkkaSpec {
  import ActorPoolSpec._

  "Actor Pool" must {

    "have expected capacity" in {
      val latch = TestLatch(2)
      val count = new AtomicInteger(0)

      val pool = actorOf(
        Props(new Actor with DefaultActorPool with FixedCapacityStrategy with SmallestMailboxSelector {
          def instance(p: Props) = actorOf(p.withCreator(new Actor {
            def receive = {
              case _ ⇒
                count.incrementAndGet
                latch.countDown()
                sender.tell("success")
            }
          }))

          def limit = 2
          def selectionCount = 1
          def partialFill = true
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
          def receive = _route
          def pressureThreshold = 1
          def instance(p: Props) = actorOf(p.withCreator(new Actor {
            def receive = {
              case req: String ⇒ {
                (10 millis).dilated.sleep
                sender.tell("Response")
              }
            }
          }))
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
        Props(new Actor with DefaultActorPool with BoundedCapacityStrategy with ActiveActorsPressureCapacitor with SmallestMailboxSelector with BasicNoBackoffFilter {
          def instance(p: Props) = actorOf(p.withCreator(new Actor {
            def receive = {
              case n: Int ⇒
                (n millis).dilated.sleep
                count.incrementAndGet
                latch.countDown()
            }
          }))

          def lowerBound = 2
          def upperBound = 4
          def rampupRate = 0.1
          def partialFill = true
          def selectionCount = 1
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
          (50 millis).dilated.sleep
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
          def instance(p: Props) = actorOf(p.withCreator(new Actor {
            def receive = {
              case n: Int ⇒
                (n millis).dilated.sleep
                count.incrementAndGet
                latch.countDown()
            }
          }))

          def lowerBound = 2
          def upperBound = 4
          def pressureThreshold = 3
          def rampupRate = 0.1
          def partialFill = true
          def selectionCount = 1
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

      // send a bunch over the threshold and observe an increment
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

          def instance(p: Props): ActorRef = actorOf(p.withCreator(new Actor {
            def receive = {
              case _ ⇒
                delegates put (self.address, "")
                latch1.countDown()
            }
          }))

          def limit = 1
          def selectionCount = 1
          def rampupRate = 0.1
          def partialFill = true
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
          def instance(p: Props) = actorOf(p.withCreator(new Actor {
            def receive = {
              case _ ⇒
                delegates put (self.address, "")
                latch2.countDown()
            }
          }))

          def limit = 2
          def selectionCount = 1
          def rampupRate = 0.1
          def partialFill = false
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
          def instance(p: Props) = actorOf(p.withCreator(new Actor {
            def receive = {
              case n: Int ⇒
                (n millis).dilated.sleep
                latch.countDown()
            }
          }))

          def lowerBound = 1
          def upperBound = 5
          def pressureThreshold = 1
          def partialFill = true
          def selectionCount = 1
          def rampupRate = 0.1
          def backoffRate = 0.50
          def backoffThreshold = 0.50
          def receive = _route
        }).withFaultHandler(faultHandler))

      // put some pressure on the pool

      for (m ← 0 to 10) pool ! 250

      (5 millis).dilated.sleep

      val z = (pool ? ActorPool.Stat).as[ActorPool.Stats].get.size

      z must be >= (2)

      // let it cool down

      for (m ← 0 to 3) {
        pool ! 1
        (500 millis).dilated.sleep
      }

      (pool ? ActorPool.Stat).as[ActorPool.Stats].get.size must be <= (z)

      pool.stop()
    }
  }
}
