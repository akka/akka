/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.dispatcher

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit.AkkaSpec
import akka.actor.Props
import akka.actor.Actor
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.util.duration._
import akka.actor.PoisonPill
import akka.dispatch.MessageDispatcherConfigurator
import akka.dispatch.MessageDispatcher
import akka.dispatch.DispatcherPrerequisites

object DispatcherDocSpec {
  val config = """
    //#my-dispatcher-config
    my-dispatcher {
      # Dispatcher is the name of the event-based dispatcher
      type = Dispatcher
      # What kind of ExecutionService to use
      executor = "fork-join-executor"
      # Configuration for the fork join pool
      fork-join-executor {
        # Min number of threads to cap factor-based parallelism number to
        parallelism-min = 2
        # Parallelism (threads) ... ceil(available processors * factor)
        parallelism-factor = 2.0
        # Max number of threads to cap factor-based parallelism number to
        parallelism-max = 10
      }
      # Throughput defines the number of messages that are processed in a batch before the
      # thread is returned to the pool. Set to 1 for as fair as possible.
      throughput = 100
    }
    //#my-dispatcher-config

    //#my-thread-pool-dispatcher-config
    my-thread-pool-dispatcher {
      # Dispatcher is the name of the event-based dispatcher
      type = Dispatcher
      # What kind of ExecutionService to use
      executor = "thread-pool-executor"
      # Configuration for the thread pool
      thread-pool-executor {
        # minimum number of threads to cap factor-based core number to
        core-pool-size-min = 2
        # No of core threads ... ceil(available processors * factor)
        core-pool-size-factor = 2.0
        # maximum number of threads to cap factor-based number to
        core-pool-size-max = 10
      }
      # Throughput defines the number of messages that are processed in a batch before the
      # thread is returned to the pool. Set to 1 for as fair as possible.
      throughput = 100
    }
    //#my-thread-pool-dispatcher-config

    //#my-pinned-dispatcher-config
    my-pinned-dispatcher {
      executor = "thread-pool-executor"
      type = PinnedDispatcher
    }
    //#my-pinned-dispatcher-config

    //#my-bounded-config
    my-dispatcher-bounded-queue {
      type = Dispatcher
      executor = "thread-pool-executor"
      thread-pool-executor {
        core-pool-size-factor = 8.0
        max-pool-size-factor  = 16.0
      }
      # Specifies the bounded capacity of the mailbox queue
      mailbox-capacity = 100
      throughput = 3
    }
    //#my-bounded-config

    //#my-balancing-config
    my-balancing-dispatcher {
      type = BalancingDispatcher
      executor = "thread-pool-executor"
      thread-pool-executor {
        core-pool-size-factor = 8.0
        max-pool-size-factor  = 16.0
      }
    }
    //#my-balancing-config

    //#prio-dispatcher-config
    prio-dispatcher {
      mailbox-type = "akka.docs.dispatcher.DispatcherDocSpec$PrioMailbox"
    }
    //#prio-dispatcher-config

    //#prio-dispatcher-config-java
    prio-dispatcher-java {
      mailbox-type = "akka.docs.dispatcher.DispatcherDocTestBase$PrioMailbox"
    }
    //#prio-dispatcher-config-java
  """

  //#prio-mailbox
  import akka.dispatch.PriorityGenerator
  import akka.dispatch.UnboundedPriorityMailbox
  import akka.dispatch.MailboxType
  import akka.actor.ActorContext
  import com.typesafe.config.Config

  val generator = PriorityGenerator { // Create a new PriorityGenerator, lower prio means more important
    case 'highpriority ⇒ 0 // 'highpriority messages should be treated first if possible
    case 'lowpriority  ⇒ 100 // 'lowpriority messages should be treated last if possible
    case PoisonPill    ⇒ 1000 // PoisonPill when no other left
    case otherwise     ⇒ 50 // We default to 50
  }

  // We create a new Priority dispatcher and seed it with the priority generator
  class PrioMailbox(config: Config) extends MailboxType {
    val priorityMailbox = UnboundedPriorityMailbox(generator)
    def create(owner: Option[ActorContext]) = priorityMailbox.create(owner)
  }
  //#prio-mailbox

  class MyActor extends Actor {
    def receive = {
      case x ⇒
    }
  }
}

class DispatcherDocSpec extends AkkaSpec(DispatcherDocSpec.config) {

  import DispatcherDocSpec.MyActor

  "defining dispatcher" in {
    //#defining-dispatcher
    import akka.actor.Props
    val myActor1 = system.actorOf(Props[MyActor].withDispatcher("my-dispatcher"), name = "myactor1")
    val myActor2 = system.actorOf(Props[MyActor].withDispatcher("my-dispatcher"), name = "myactor2")
    //#defining-dispatcher
  }

  "defining dispatcher with bounded queue" in {
    val dispatcher = system.dispatchers.lookup("my-dispatcher-bounded-queue")
  }

  "defining pinned dispatcher" in {
    //#defining-pinned-dispatcher
    val myActor = system.actorOf(Props[MyActor].withDispatcher("my-dispatcher"), name = "myactor")
    //#defining-pinned-dispatcher
  }

  "defining priority dispatcher" in {
    //#prio-dispatcher

    val a = system.actorOf( // We create a new Actor that just prints out what it processes
      Props(new Actor {
        val log: LoggingAdapter = Logging(context.system, this)

        self ! 'lowpriority
        self ! 'lowpriority
        self ! 'highpriority
        self ! 'pigdog
        self ! 'pigdog2
        self ! 'pigdog3
        self ! 'highpriority
        self ! PoisonPill

        def receive = {
          case x ⇒ log.info(x.toString)
        }
      }).withDispatcher("prio-dispatcher"))

    /*
    Logs:
      'highpriority
      'highpriority
      'pigdog
      'pigdog2
      'pigdog3
      'lowpriority
      'lowpriority
    */
    //#prio-dispatcher

    awaitCond(a.isTerminated, 5 seconds)
  }

  "defining balancing dispatcher" in {
    val dispatcher = system.dispatchers.lookup("my-balancing-dispatcher")
  }

}
