/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.actor.typed

import akka.actor.typed._
import akka.actor.typed.scaladsl._
import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.{ ExecutionContext, Future }

// #blocking-in-actor
object BlockingActor {
  val behavior: Behavior[Int] = Behaviors.receiveMessage {
    case i: Int =>
      Thread.sleep(5000) //block for 5 seconds, representing blocking I/O, etc
      println(s"Blocking operation finished: ${i}")
      Behaviors.same
  }
}
// #blocking-in-actor

// #blocking-in-future
object BlockingFutureActor {
  def apply(): Behavior[Int] =
    Behaviors.setup { context =>
      implicit val executionContext: ExecutionContext = context.executionContext

      Behaviors.receiveMessage {
        case i: Int =>
          triggerFutureBlockingOperation(i)
          Behaviors.same
      }
    }

  def triggerFutureBlockingOperation(i: Int)(implicit ec: ExecutionContext) = {
    println(s"Calling blocking Future: ${i}")
    Future {
      Thread.sleep(5000) //block for 5 seconds
      println(s"Blocking future finished ${i}")
    }
  }
}
// #blocking-in-future

// #separate-dispatcher
object SeparateDispatcherFutureActor {
  def apply(): Behavior[Int] =
    Behaviors.setup { context =>
      implicit val executionContext: ExecutionContext =
        context.system.dispatchers.lookup(DispatcherSelector.fromConfig("my-blocking-dispatcher"))

      Behaviors.receiveMessage {
        case i: Int =>
          triggerFutureBlockingOperation(i)
          Behaviors.same
      }
    }

  def triggerFutureBlockingOperation(i: Int)(implicit ec: ExecutionContext) = {
    println(s"Calling blocking Future: ${i}")
    Future {
      Thread.sleep(5000) //block for 5 seconds
      println(s"Blocking future finished ${i}")
    }
  }
}
// #separate-dispatcher

// #print-actor
object PrintActor {
  val behavior: Behavior[Integer] =
    Behaviors.receiveMessage(i => {
      println(s"PrintActor: ${i}")
      Behaviors.same
    })
}
// #print-actor

object BlockingDispatcherSample {
  def main(args: Array[String]) = {
    // #blocking-main
    val root = Behaviors.setup[Nothing] { context =>
      val actor1 = context.spawn(BlockingFutureActor(), "futureActor")
      val actor2 = context.spawn(PrintActor.behavior, "printActor")

      for (i <- 1 to 100) {
        actor1 ! i
        actor2 ! i
      }
      Behaviors.empty
    }
    val system = ActorSystem[Nothing](root, "BlockingDispatcherSample")
    // #blocking-main
  }
}

object SeparateDispatcherSample {
  def main(args: Array[String]) = {

    val config = ConfigFactory.parseString("""
      //#my-blocking-dispatcher-config
      my-blocking-dispatcher {
        type = Dispatcher
        executor = "thread-pool-executor"
        thread-pool-executor {
          fixed-pool-size = 16
        }
        throughput = 1
      }
      //#my-blocking-dispatcher-config
      """)

    // #separate-dispatcher-main
    val root = Behaviors.setup[Nothing] { context =>
      val actor1 = context.spawn(SeparateDispatcherFutureActor(), "futureActor")
      val actor2 = context.spawn(PrintActor.behavior, "printActor")

      for (i <- 1 to 100) {
        actor1 ! i
        actor2 ! i
      }
      Behaviors.ignore
    }
    // #separate-dispatcher-main
    val system = ActorSystem[Nothing](root, "SeparateDispatcherSample", config)

  }
}
