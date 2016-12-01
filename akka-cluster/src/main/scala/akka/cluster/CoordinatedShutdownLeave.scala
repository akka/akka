/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.cluster

import akka.Done
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.cluster.ClusterEvent._
import akka.cluster.MemberStatus._

/**
 * INTERNAL API
 */
private[akka] object CoordinatedShutdownLeave {
  def props(): Props = Props[CoordinatedShutdownLeave]

  case object LeaveReq
}

/**
 * INTERNAL API
 */
private[akka] class CoordinatedShutdownLeave extends Actor {
  import CoordinatedShutdownLeave.LeaveReq

  val cluster = Cluster(context.system)

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  def receive = {
    case LeaveReq ⇒
      // MemberRemoved is needed in case it was downed instead
      cluster.leave(cluster.selfAddress)
      cluster.subscribe(self, classOf[MemberLeft], classOf[MemberRemoved])
      context.become(waitingLeaveCompleted(sender()))
  }

  def waitingLeaveCompleted(replyTo: ActorRef): Receive = {
    case s: CurrentClusterState ⇒
      if (s.members.exists(m ⇒ m.uniqueAddress == cluster.selfUniqueAddress &&
        (m.status == Leaving || m.status == Exiting || m.status == Down))) {
        replyTo ! Done
        context.stop(self)
      }
    case evt: MemberEvent ⇒
      if (evt.member.uniqueAddress == cluster.selfUniqueAddress) {
        replyTo ! Done
        context.stop(self)
      }
  }

}
