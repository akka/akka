/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.dispatcher

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit.AkkaSpec
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.util.duration._
import akka.actor.{ Props, Actor, PoisonPill, ActorSystem }

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
      # Throughput defines the maximum number of messages to be
      # processed per actor before the thread jumps to the next actor.
      # Set to 1 for as fair as possible.
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
      # Throughput defines the maximum number of messages to be
      # processed per actor before the thread jumps to the next actor.
      # Set to 1 for as fair as possible.
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
      mailbox-type = "docs.dispatcher.DispatcherDocSpec$MyPrioMailbox"
    }
    //#prio-dispatcher-config

    //#prio-dispatcher-config-java
    prio-dispatcher-java {
      mailbox-type = "docs.dispatcher.DispatcherDocTestBase$MyPrioMailbox"
      //Other dispatcher configuration goes here
    }
    //#prio-dispatcher-config-java
  """

  //#prio-mailbox
  import akka.dispatch.PriorityGenerator
  import akka.dispatch.UnboundedPriorityMailbox
  import com.typesafe.config.Config

  // We inherit, in this case, from UnboundedPriorityMailbox
  // and seed it with the priority generator
  class MyPrioMailbox(settings: ActorSystem.Settings, config: Config) extends UnboundedPriorityMailbox(
    // Create a new PriorityGenerator, lower prio means more important
    PriorityGenerator {
      // 'highpriority messages should be treated first if possible
      case 'highpriority ⇒ 0

      // 'lowpriority messages should be treated last if possible
      case 'lowpriority  ⇒ 2

      // PoisonPill when no other left
      case PoisonPill    ⇒ 3

      // We default to 1, which is in between high and low
      case otherwise     ⇒ 1
    })
  //#prio-mailbox

  class MyActor extends Actor {
    def receive = {
      case x ⇒
    }
  }

  //#mailbox-implementation-example
  class MyUnboundedMailbox extends akka.dispatch.MailboxType {
    import akka.actor.{ ActorRef, ActorSystem }
    import com.typesafe.config.Config
    import java.util.concurrent.ConcurrentLinkedQueue
    import akka.dispatch.{
      Envelope,
      MessageQueue,
      QueueBasedMessageQueue,
      UnboundedMessageQueueSemantics
    }

    // This constructor signature must exist, it will be called by Akka
    def this(settings: ActorSystem.Settings, config: Config) = this()

    // The create method is called to create the MessageQueue
    final override def create(owner: Option[ActorRef], system: Option[ActorSystem]): MessageQueue =
      new QueueBasedMessageQueue with UnboundedMessageQueueSemantics {
        final val queue = new ConcurrentLinkedQueue[Envelope]()
      }
  }
  //#mailbox-implementation-example
}

class DispatcherDocSpec extends AkkaSpec(DispatcherDocSpec.config) {

  import DispatcherDocSpec.MyActor

  "defining dispatcher" in {
    val context = system
    //#defining-dispatcher
    import akka.actor.Props
    val myActor =
      context.actorOf(Props[MyActor].withDispatcher("my-dispatcher"), "myactor1")
    //#defining-dispatcher
  }

  "defining dispatcher with bounded queue" in {
    val dispatcher = system.dispatchers.lookup("my-dispatcher-bounded-queue")
  }

  "defining pinned dispatcher" in {
    val context = system
    //#defining-pinned-dispatcher
    val myActor =
      context.actorOf(Props[MyActor].withDispatcher("my-pinned-dispatcher"), "myactor2")
    //#defining-pinned-dispatcher
  }

  "defining priority dispatcher" in {
    //#prio-dispatcher

    // We create a new Actor that just prints out what it processes
    val a = system.actorOf(
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
