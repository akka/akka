/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import scala.annotation.tailrec

import akka.actor.{ VirtualPathContainer, Terminated, Deploy, Props, Nobody, LocalActorRef, InternalActorRef, Address, ActorSystemImpl, ActorRef, ActorPathExtractor, ActorPath, Actor }
import akka.event.LoggingAdapter

private[akka] sealed trait DaemonMsg
private[akka] case class DaemonMsgCreate(props: Props, deploy: Deploy, path: String, supervisor: ActorRef) extends DaemonMsg
private[akka] case class DaemonMsgWatch(watcher: ActorRef, watched: ActorRef) extends DaemonMsg

/**
 * Internal system "daemon" actor for remote internal communication.
 *
 * It acts as the brain of the remote that responds to system remote events (messages) and undertakes action.
 *
 * INTERNAL USE ONLY!
 */
private[akka] class RemoteSystemDaemon(system: ActorSystemImpl, _path: ActorPath, _parent: InternalActorRef, _log: LoggingAdapter)
  extends VirtualPathContainer(system.provider, _path, _parent, _log) {

  /**
   * Find the longest matching path which we know about and return that ref
   * (or ask that ref to continue searching if elements are left).
   */
  override def getChild(names: Iterator[String]): InternalActorRef = {

    @tailrec
    def rec(s: String, n: Int): (InternalActorRef, Int) = {
      getChild(s) match {
        case null ⇒
          val last = s.lastIndexOf('/')
          if (last == -1) (Nobody, n)
          else rec(s.substring(0, last), n + 1)
        case ref ⇒ (ref, n)
      }
    }

    val full = Vector() ++ names
    rec(full.mkString("/"), 0) match {
      case (Nobody, _) ⇒ Nobody
      case (ref, 0)    ⇒ ref
      case (ref, n)    ⇒ ref.getChild(full.takeRight(n).iterator)
    }
  }

  override def !(msg: Any)(implicit sender: ActorRef = null): Unit = msg match {
    case message: DaemonMsg ⇒
      log.debug("Received command [{}] to RemoteSystemDaemon on [{}]", message, path.address)
      message match {
        case DaemonMsgCreate(props, deploy, path, supervisor) ⇒
          path match {
            case ActorPathExtractor(address, elems) if elems.nonEmpty && elems.head == "remote" ⇒
              // TODO RK currently the extracted “address” is just ignored, is that okay?
              // TODO RK canonicalize path so as not to duplicate it always #1446
              val subpath = elems.drop(1)
              val path = this.path / subpath
              val actor = system.provider.actorOf(system, props, supervisor.asInstanceOf[InternalActorRef],
                path, false, Some(deploy), true)
              addChild(subpath.mkString("/"), actor)
              system.deathWatch.subscribe(this, actor)
            case _ ⇒
              log.error("remote path does not match path from message [{}]", message)
          }
        case DaemonMsgWatch(watcher, watched) ⇒
          val other = system.actorFor(watcher.path.root / "remote")
          system.deathWatch.subscribe(other, watched)
      }

    case Terminated(child: LocalActorRef) ⇒ removeChild(child.path.elements.drop(1).mkString("/"))

    case t: Terminated                    ⇒ system.deathWatch.publish(t)

    case unknown                          ⇒ log.warning("Unknown message {} received by {}", unknown, this)
  }

}
