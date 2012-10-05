/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import scala.annotation.tailrec
import akka.actor.{ VirtualPathContainer, Terminated, Deploy, Props, Nobody, LocalActorRef, InternalActorRef, Address, ActorSystemImpl, ActorRef, ActorPathExtractor, ActorPath, Actor, AddressTerminated }
import akka.event.LoggingAdapter
import akka.dispatch.Watch
import akka.actor.ActorRefWithCell
import akka.actor.ActorRefScope
import akka.util.Switch

private[akka] sealed trait DaemonMsg
private[akka] case class DaemonMsgCreate(props: Props, deploy: Deploy, path: String, supervisor: ActorRef) extends DaemonMsg

/**
 * Internal system "daemon" actor for remote internal communication.
 *
 * It acts as the brain of the remote that responds to system remote events (messages) and undertakes action.
 *
 * INTERNAL USE ONLY!
 */
private[akka] class RemoteSystemDaemon(
  system: ActorSystemImpl,
  _path: ActorPath,
  _parent: InternalActorRef,
  _log: LoggingAdapter,
  val untrustedMode: Boolean)
  extends VirtualPathContainer(system.provider, _path, _parent, _log) {

  import akka.actor.SystemGuardian._

  private val terminating = new Switch(false)

  system.provider.systemGuardian.tell(RegisterTerminationHook, this)

  system.eventStream.subscribe(this, classOf[AddressTerminated])

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
        case DaemonMsgCreate(_, _, path, _) if untrustedMode ⇒ log.debug("does not accept deployments (untrusted) for {}", path)
        case DaemonMsgCreate(props, deploy, path, supervisor) ⇒
          path match {
            case ActorPathExtractor(address, elems) if elems.nonEmpty && elems.head == "remote" ⇒
              // TODO RK currently the extracted “address” is just ignored, is that okay?
              // TODO RK canonicalize path so as not to duplicate it always #1446
              val subpath = elems.drop(1)
              val path = this.path / subpath
              val isTerminating = !terminating.whileOff {
                val actor = system.provider.actorOf(system, props, supervisor.asInstanceOf[InternalActorRef],
                  path, systemService = false, Some(deploy), lookupDeploy = true, async = false)
                addChild(subpath.mkString("/"), actor)
                actor.sendSystemMessage(Watch(actor, this))
              }
              if (isTerminating) log.error("Skipping [{}] to RemoteSystemDaemon on [{}] while terminating", message, path.address)
            case _ ⇒
              log.debug("remote path does not match path from message [{}]", message)
          }
      }

    case Terminated(child: ActorRefWithCell) if child.asInstanceOf[ActorRefScope].isLocal ⇒
      terminating.locked {
        removeChild(child.path.elements.drop(1).mkString("/"))
        terminationHookDoneWhenNoChildren()
      }

    case t: Terminated ⇒

    case TerminationHook ⇒
      terminating.switchOn {
        terminationHookDoneWhenNoChildren()
        foreachChild { system.stop }
      }

    case AddressTerminated(address) ⇒
      foreachChild { case a: InternalActorRef if a.getParent.path.address == address ⇒ system.stop(a) }

    case unknown ⇒ log.warning("Unknown message {} received by {}", unknown, this)
  }

  def terminationHookDoneWhenNoChildren(): Unit = terminating.whileOn {
    if (!hasChildren) system.provider.systemGuardian.tell(TerminationHookDone, this)
  }

}
