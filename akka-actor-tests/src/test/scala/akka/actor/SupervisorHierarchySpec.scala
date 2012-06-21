/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import language.postfixOps

import akka.testkit._
import java.util.concurrent.{ TimeUnit, CountDownLatch }
import akka.dispatch.Await
import akka.pattern.ask
import akka.util.Duration
import akka.util.duration._

object SupervisorHierarchySpec {
  class FireWorkerException(msg: String) extends Exception(msg)

  /**
   * For testing Supervisor behavior, normally you don't supply the strategy
   * from the outside like this.
   */
  class CountDownActor(countDown: CountDownLatch, override val supervisorStrategy: SupervisorStrategy) extends Actor {

    def receive = {
      case p: Props ⇒ sender ! context.actorOf(p)
    }
    // test relies on keeping children around during restart
    override def preRestart(cause: Throwable, msg: Option[Any]) {}
    override def postRestart(reason: Throwable) = {
      countDown.countDown()
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class SupervisorHierarchySpec extends AkkaSpec with DefaultTimeout {
  import SupervisorHierarchySpec._

  "A Supervisor Hierarchy" must {

    "restart manager and workers in AllForOne" in {
      val countDown = new CountDownLatch(4)

      val boss = system.actorOf(Props(new Supervisor(OneForOneStrategy()(List(classOf[Exception])))))

      val managerProps = Props(new CountDownActor(countDown, AllForOneStrategy()(List())))
      val manager = Await.result((boss ? managerProps).mapTo[ActorRef], timeout.duration)

      val workerProps = Props(new CountDownActor(countDown, SupervisorStrategy.defaultStrategy))
      val workerOne, workerTwo, workerThree = Await.result((manager ? workerProps).mapTo[ActorRef], timeout.duration)

      filterException[ActorKilledException] {
        workerOne ! Kill

        // manager + all workers should be restarted by only killing a worker
        // manager doesn't trap exits, so boss will restart manager

        assert(countDown.await(2, TimeUnit.SECONDS))
      }
    }

    "send notification to supervisor when permanent failure" in {
      val countDownMessages = new CountDownLatch(1)
      val countDownMax = new CountDownLatch(1)
      val boss = system.actorOf(Props(new Actor {
        override val supervisorStrategy =
          OneForOneStrategy(maxNrOfRetries = 1, withinTimeRange = 5 seconds)(List(classOf[Throwable]))

        val crasher = context.watch(context.actorOf(Props(new CountDownActor(countDownMessages, SupervisorStrategy.defaultStrategy))))

        def receive = {
          case "killCrasher" ⇒ crasher ! Kill
          case Terminated(_) ⇒ countDownMax.countDown()
        }
      }))

      filterException[ActorKilledException] {
        boss ! "killCrasher"
        boss ! "killCrasher"

        assert(countDownMessages.await(2, TimeUnit.SECONDS))
        assert(countDownMax.await(2, TimeUnit.SECONDS))
      }
    }
  }
}

