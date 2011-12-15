/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.routing

import akka.actor.ActorRef

//#boundedCapacitor
trait BoundedCapacitor {
  def lowerBound: Int
  def upperBound: Int

  def capacity(delegates: Seq[ActorRef]): Int = {
    val current = delegates length
    var delta = _eval(delegates)
    val proposed = current + delta

    if (proposed < lowerBound) delta += (lowerBound - proposed)
    else if (proposed > upperBound) delta -= (proposed - upperBound)

    delta
  }

  protected def _eval(delegates: Seq[ActorRef]): Int
}
//#boundedCapacitor
