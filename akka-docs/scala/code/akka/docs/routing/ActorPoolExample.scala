package akka.docs.routing

import akka.routing.{ BasicNoBackoffFilter, SmallestMailboxSelector, DefaultActorPool }
import akka.actor.{ ActorRef, Props, Actor }

//#testPool
class TestPool extends Actor with DefaultActorPool with SmallestMailboxSelector with BasicNoBackoffFilter {

  def capacity(delegates: Seq[ActorRef]) = 5
  protected def receive = _route
  def rampupRate = 0.1
  def selectionCount = 1
  def partialFill = true

  def instance(defaults: Props) = context.actorOf(defaults.withCreator(new Actor {
    def receive = {
      case _ ⇒ // do something
    }
  }))
}
//#testPool
