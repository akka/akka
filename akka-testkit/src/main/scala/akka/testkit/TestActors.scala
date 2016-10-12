/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit

import akka.actor.{ Props, Actor, ActorRef }

/**
 * A collection of common actor patterns used in tests.
 */
object TestActors {

  /**
   * EchoActor sends back received messages (unmodified).
   */
  class EchoActor extends Actor {
    override def receive = {
      case message ⇒ sender() ! message
    }
  }

  /**
   * BlackholeActor does nothing for incoming messages, its like a blackhole.
   */
  class BlackholeActor extends Actor {
    override def receive = {
      case _ ⇒ // ignore... 
    }
  }

  /**
   * ForwardActor forwards all messages as-is to specified ActorRef.
   *
   * @param ref target ActorRef to forward messages to
   */
  class ForwardActor(ref: ActorRef) extends Actor {
    override def receive = {
      case message ⇒ ref forward message
    }
  }

  val echoActorProps = Props[EchoActor]()
  val blackholeProps = Props[BlackholeActor]()
  def forwardActorProps(ref: ActorRef) = Props(classOf[ForwardActor], ref)

}
