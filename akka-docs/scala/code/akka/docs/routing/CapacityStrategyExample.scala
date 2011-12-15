package akka.docs.routing

import akka.routing.ActorPool
import akka.actor.ActorRef

//#capacityStrategy
trait CapacityStrategy {
  import ActorPool._

  def pressure(delegates: Seq[ActorRef]): Int
  def filter(pressure: Int, capacity: Int): Int

  protected def _eval(delegates: Seq[ActorRef]): Int =
    filter(pressure(delegates), delegates.size)
}
//#capacityStrategy