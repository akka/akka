/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camelexamples

import akka.actor.{ PoisonPill, Terminated, Props, ActorSystem, Actor }
import ExamplesSupport._
import RichString._

object SupervisedConsumersExample extends App {

  val system = ActorSystem("test1")

  system.actorOf(Props(new Actor {
    context.watch(context.actorOf(Props[EndpointManager]))
    protected def receive = {
      case Terminated(ref) ⇒ system.shutdown()
    }
  }))

  "data/input/CamelConsumer/file1.txt" << "test data " + math.random
}

class EndpointManager extends Actor {
  import context._

  override def supervisorStrategy() = retry3xWithin1s

  watch(actorOf(Props[SysOutConsumer]))
  watch(actorOf(Props[TroubleMaker]))

  protected def receive = {
    case Terminated(ref) ⇒ {
      printf("Hey! One of the endpoints has died: %s. I am doing sepuku...\n", ref)
      self ! PoisonPill
    }
  }
}
