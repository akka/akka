package akka.routing

import java.util.concurrent.atomic.AtomicInteger
import akka.actor._
import collection.mutable.LinkedList
import akka.routing.Routing.Broadcast
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import akka.testkit._

object RoutingSpec {

  class TestActor extends Actor with Serializable {
    def receive = {
      case _ ⇒
        println("Hello")
    }
  }

}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class RoutingSpec extends AkkaSpec with DefaultTimeout {

  val impl = system.asInstanceOf[ActorSystemImpl]

  import akka.routing.RoutingSpec._

  "no router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props(new TestActor).withRouting(NoRouter))
      routedActor.isTerminated must be(false)
    }

    "send message to connection" in {
      val doneLatch = new CountDownLatch(1)

      val counter = new AtomicInteger(0)

      class Actor1 extends Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case _     ⇒ counter.incrementAndGet
        }
      }

      val routedActor = system.actorOf(Props(new Actor1).withRouting(NoRouter))
      routedActor ! "hello"
      routedActor ! "end"

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter.get must be(1)
    }
  }

  "round robin router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props(new TestActor).withRouting(RoundRobinRouter(nrOfInstances = 1)))
      routedActor.isTerminated must be(false)
    }

    //In this test a bunch of actors are created and each actor has its own counter.
    //to test round robin, the routed actor receives the following sequence of messages 1 2 3 .. 1 2 3 .. 1 2 3 which it
    //uses to increment his counter.
    //So after n iteration, the first actor his counter should be 1*n, the second 2*n etc etc.
    "deliver messages in a round robin fashion" in {
      val connectionCount = 10
      val iterationCount = 10
      val doneLatch = new CountDownLatch(connectionCount)

      //lets create some connections.
      var actors = new LinkedList[ActorRef]
      var counters = new LinkedList[AtomicInteger]
      for (i ← 0 until connectionCount) {
        counters = counters :+ new AtomicInteger()

        val actor = system.actorOf(new Actor {
          def receive = {
            case "end"    ⇒ doneLatch.countDown()
            case msg: Int ⇒ counters.get(i).get.addAndGet(msg)
          }
        })
        actors = actors :+ actor
      }

      val routedActor = system.actorOf(Props(new TestActor).withRouting(RoundRobinRouter(targets = actors)))

      //send messages to the actor.
      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          routedActor ! (k + 1)
        }
      }

      routedActor ! Broadcast("end")
      //now wait some and do validations.
      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      for (i ← 0 until connectionCount) {
        val counter = counters.get(i).get
        counter.get must be((iterationCount * (i + 1)))
      }
    }

    "deliver a broadcast message using the !" in {
      val doneLatch = new CountDownLatch(2)

      val counter1 = new AtomicInteger
      val actor1 = system.actorOf(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      })

      val counter2 = new AtomicInteger
      val actor2 = system.actorOf(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      })

      val routedActor = system.actorOf(Props(new TestActor).withRouting(RoundRobinRouter(targets = List(actor1, actor2))))

      routedActor ! Broadcast(1)
      routedActor ! Broadcast("end")

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }

    // TODO (HE) : Is this still a valid test case?
    /*
    "fail to deliver a broadcast message using the ?" in {
      val doneLatch = new CountDownLatch(1)

      val counter1 = new AtomicInteger
      val connection1 = system.actorOf(new Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case _     ⇒ counter1.incrementAndGet()
        }
      })

      val routedActor = system.actorOf(Props(new TestActor).withRouting(RoundRobinRouter(targets = List(connection1))))

      intercept[RoutingException] {
        routedActor ? Broadcast(1)
      }

      routedActor ! "end"
      doneLatch.await(5, TimeUnit.SECONDS) must be(true)
      counter1.get must be(0)
    }
    */

  }

  "random router" must {

    "be started when constructed" in {
      val routedActor = system.actorOf(Props(new TestActor).withRouting(RandomRouter(nrOfInstances = 1)))
      routedActor.isTerminated must be(false)
    }

    "deliver a broadcast message" in {
      val doneLatch = new CountDownLatch(2)

      val counter1 = new AtomicInteger
      val actor1 = system.actorOf(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      })

      val counter2 = new AtomicInteger
      val actor2 = system.actorOf(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      })

      val routedActor = system.actorOf(Props(new TestActor).withRouting(RandomRouter(targets = List(actor1, actor2))))

      routedActor ! Broadcast(1)
      routedActor ! Broadcast("end")

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }

    // TODO (HE) : Is this still a valid test case?
    /*
  "fail to deliver a broadcast message using the ?" in {
    val doneLatch = new CountDownLatch(1)

    val counter1 = new AtomicInteger
    val connection1 = system.actorOf(new Actor {
      def receive = {
        case "end" ⇒ doneLatch.countDown()
        case _     ⇒ counter1.incrementAndGet()
      }
    })

    val props = RoutedProps(routerFactory = () ⇒ new RandomRouter, connectionManager = new LocalConnectionManager(List(connection1)))
    val actor = new RoutedActorRef(system, props, impl.guardian, "foo")

    try {
      actor ? Broadcast(1)
      fail()
    } catch {
      case e: RoutingException ⇒
    }

    actor ! "end"
    doneLatch.await(5, TimeUnit.SECONDS) must be(true)
    counter1.get must be(0)
  }
  */
  }

  "broadcast router" must {
    "be started when constructed" in {
      val routedActor = system.actorOf(Props(new TestActor).withRouting(BroadcastRouter(nrOfInstances = 1)))
      routedActor.isTerminated must be(false)
    }

    "broadcast message using !" in {
      val doneLatch = new CountDownLatch(2)

      val counter1 = new AtomicInteger
      val actor1 = system.actorOf(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      })

      val counter2 = new AtomicInteger
      val actor2 = system.actorOf(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      })

      val routedActor = system.actorOf(Props(new TestActor).withRouting(BroadcastRouter(targets = List(actor1, actor2))))
      routedActor ! 1
      routedActor ! "end"

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }

    "broadcast message using ?" in {
      val doneLatch = new CountDownLatch(2)

      val counter1 = new AtomicInteger
      val actor1 = system.actorOf(new Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case msg: Int ⇒
            counter1.addAndGet(msg)
            sender ! "ack"
        }
      })

      val counter2 = new AtomicInteger
      val actor2 = system.actorOf(new Actor {
        def receive = {
          case "end"    ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      })

      val routedActor = system.actorOf(Props(new TestActor).withRouting(BroadcastRouter(targets = List(actor1, actor2))))
      routedActor ? 1
      routedActor ! "end"

      doneLatch.await(5, TimeUnit.SECONDS) must be(true)

      counter1.get must be(1)
      counter2.get must be(1)
    }
  }

  // TODO (HE) : add tests below
  /*

"Scatter-gather router" must {

  "return response, even if one of the actors has stopped" in {
    val shutdownLatch = new TestLatch(1)
    val actor1 = newActor(1, Some(shutdownLatch))
    val actor2 = newActor(2, Some(shutdownLatch))
    val routedActor = system.actorOf(Props(new TestActor).withRouting(ScatterGatherFirstCompletedRouter(targets = List(actor1, actor2))))

    routedActor ! Broadcast(Stop(Some(1)))
    shutdownLatch.await
    (routedActor ? Broadcast(0)).get.asInstanceOf[Int] must be(1)
  }

    "throw an exception, if all the connections have stopped" in {

      val shutdownLatch = new TestLatch(2)

      val props = RoutedProps(routerFactory = () ⇒ new ScatterGatherFirstCompletedRouter, connectionManager = new LocalConnectionManager(List(newActor(0, Some(shutdownLatch)), newActor(1, Some(shutdownLatch)))))

      val actor = new RoutedActorRef(system, props, impl.guardian, "foo")

      actor ! Broadcast(Stop())

      shutdownLatch.await

      (intercept[RoutingException] {
        actor ? Broadcast(0)
      }) must not be (null)

    }

    "return the first response from connections, when all of them replied" in {

      val props = RoutedProps(routerFactory = () ⇒ new ScatterGatherFirstCompletedRouter, connectionManager = new LocalConnectionManager(List(newActor(0), newActor(1))))

      val actor = new RoutedActorRef(system, props, impl.guardian, "foo")

      (actor ? Broadcast("Hi!")).get.asInstanceOf[Int] must be(0)

    }

    "return the first response from connections, when some of them failed to reply" in {
      val props = RoutedProps(routerFactory = () ⇒ new ScatterGatherFirstCompletedRouter, connectionManager = new LocalConnectionManager(List(newActor(0), newActor(1))))

      val actor = new RoutedActorRef(system, props, impl.guardian, "foo")

      (actor ? Broadcast(0)).get.asInstanceOf[Int] must be(1)
    }

    "be started when constructed" in {
      val props = RoutedProps(routerFactory = () ⇒ new ScatterGatherFirstCompletedRouter, connectionManager = new LocalConnectionManager(List(newActor(0))))
      val actor = new RoutedActorRef(system, props, impl.guardian, "foo")

      actor.isTerminated must be(false)
    }

    "deliver one-way messages in a round robin fashion" in {
      val connectionCount = 10
      val iterationCount = 10
      val doneLatch = new TestLatch(connectionCount)

      var connections = new LinkedList[ActorRef]
      var counters = new LinkedList[AtomicInteger]
      for (i ← 0 until connectionCount) {
        counters = counters :+ new AtomicInteger()

        val connection = system.actorOf(new Actor {
          def receive = {
            case "end" ⇒ doneLatch.countDown()
            case msg: Int ⇒ counters.get(i).get.addAndGet(msg)
          }
        })
        connections = connections :+ connection
      }

      val props = RoutedProps(routerFactory = () ⇒ new ScatterGatherFirstCompletedRouter, connectionManager = new LocalConnectionManager(connections))

      val actor = new RoutedActorRef(system, props, impl.guardian, "foo")

      for (i ← 0 until iterationCount) {
        for (k ← 0 until connectionCount) {
          actor ! (k + 1)
        }
      }

      actor ! Broadcast("end")

      doneLatch.await

      for (i ← 0 until connectionCount) {
        val counter = counters.get(i).get
        counter.get must be((iterationCount * (i + 1)))
      }
    }

    "deliver a broadcast message using the !" in {
      val doneLatch = new TestLatch(2)

      val counter1 = new AtomicInteger
      val connection1 = system.actorOf(new Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter1.addAndGet(msg)
        }
      })

      val counter2 = new AtomicInteger
      val connection2 = system.actorOf(new Actor {
        def receive = {
          case "end" ⇒ doneLatch.countDown()
          case msg: Int ⇒ counter2.addAndGet(msg)
        }
      })

      val props = RoutedProps(routerFactory = () ⇒ new ScatterGatherFirstCompletedRouter, connectionManager = new LocalConnectionManager(List(connection1, connection2)))

      val actor = new RoutedActorRef(system, props, impl.guardian, "foo")

      actor ! Broadcast(1)
      actor ! Broadcast("end")

      doneLatch.await

      counter1.get must be(1)
      counter2.get must be(1)
    }


  case class Stop(id: Option[Int] = None)

  def newActor(id: Int, shudownLatch: Option[TestLatch] = None) = system.actorOf(new Actor {
    def receive = {
      case Stop(None) ⇒
        println(">>>> STOPPING  : " + id)
        self.stop()
      case Stop(Some(_id)) if (_id == id) ⇒
        println(">>>> STOPPING >: " + id)
        self.stop()
      case _id: Int if (_id == id) ⇒
        println("-----> ID MATCH - do nothing")
      case x ⇒ {
        Thread sleep 100 * id
        println("-----> SENDING REPLY: " + id)
        sender.tell(id)
      }
    }

    override def postStop = {
      println("***** POSTSTOP")
      shudownLatch foreach (_.countDown())
    }
  })
}
*/
}
