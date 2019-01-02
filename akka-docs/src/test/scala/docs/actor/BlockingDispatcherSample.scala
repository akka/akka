/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.actor

import akka.actor.{ Actor, ActorSystem, Props }
import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.{ ExecutionContext, Future }

// #blocking-in-actor
class BlockingActor extends Actor {
  def receive = {
    case i: Int ⇒
      Thread.sleep(5000) //block for 5 seconds, representing blocking I/O, etc
      println(s"Blocking operation finished: ${i}")
  }
}
// #blocking-in-actor

// #blocking-in-future
class BlockingFutureActor extends Actor {
  implicit val executionContext: ExecutionContext = context.dispatcher

  def receive = {
    case i: Int ⇒
      println(s"Calling blocking Future: ${i}")
      Future {
        Thread.sleep(5000) //block for 5 seconds
        println(s"Blocking future finished ${i}")
      }
  }
}
// #blocking-in-future

// #separate-dispatcher
class SeparateDispatcherFutureActor extends Actor {
  implicit val executionContext: ExecutionContext = context.system.dispatchers.lookup("my-blocking-dispatcher")

  def receive = {
    case i: Int ⇒
      println(s"Calling blocking Future: ${i}")
      Future {
        Thread.sleep(5000) //block for 5 seconds
        println(s"Blocking future finished ${i}")
      }
  }
}
// #separate-dispatcher

// #print-actor
class PrintActor extends Actor {
  def receive = {
    case i: Int ⇒
      println(s"PrintActor: ${i}")
  }
}
// #print-actor

object BlockingDispatcherSample {
  def main(args: Array[String]) = {
    val system = ActorSystem("BlockingDispatcherSample")

    try {
      // #blocking-main
      val actor1 = system.actorOf(Props(new BlockingFutureActor))
      val actor2 = system.actorOf(Props(new PrintActor))

      for (i ← 1 to 100) {
        actor1 ! i
        actor2 ! i
      }
      // #blocking-main
    } finally {
      Thread.sleep(5000 * 6)
      system.terminate()
    }
  }
}

object SeparateDispatcherSample {
  def main(args: Array[String]) = {

    val config = ConfigFactory.parseString(
      """
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
      """
    )
    val system = ActorSystem("SeparateDispatcherSample", config)

    try {
      // #separate-dispatcher-main
      val actor1 = system.actorOf(Props(new SeparateDispatcherFutureActor))
      val actor2 = system.actorOf(Props(new PrintActor))

      for (i ← 1 to 100) {
        actor1 ! i
        actor2 ! i
      }
      // #separate-dispatcher-main
    } finally {
      Thread.sleep(5000 * 6)
      system.terminate()
    }
  }
}
