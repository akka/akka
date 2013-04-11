/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote

import scala.annotation.tailrec
import scala.util.control.NonFatal
import akka.actor.{ VirtualPathContainer, Terminated, Deploy, Props, Nobody, LocalActorRef, InternalActorRef, Address, ActorSystemImpl, ActorRef, ActorPathExtractor, ActorPath, Actor, AddressTerminated }
import akka.event.LoggingAdapter
import akka.dispatch.sysmsg.{ DeathWatchNotification, SystemMessage, Watch }
import akka.actor.ActorRefWithCell
import akka.actor.ActorRefScope
import akka.util.Switch
import akka.actor.RootActorPath
import akka.actor.SelectParent
import akka.actor.SelectChildName
import akka.actor.SelectChildPattern
import akka.actor.Identify
import akka.actor.ActorIdentity

/**
 * INTERNAL API
 */
private[akka] sealed trait DaemonMsg

/**
 * INTERNAL API
 */
@SerialVersionUID(1L)
private[akka] case class DaemonMsgCreate(props: Props, deploy: Deploy, path: String, supervisor: ActorRef) extends DaemonMsg

/**
 * INTERNAL API
 *
 * Internal system "daemon" actor for remote internal communication.
 *
 * It acts as the brain of the remote that responds to system remote events (messages) and undertakes action.
 */
private[akka] class RemoteSystemDaemon(
  system: ActorSystemImpl,
  _path: ActorPath,
  _parent: InternalActorRef,
  terminator: ActorRef,
  _log: LoggingAdapter,
  val untrustedMode: Boolean)
  extends VirtualPathContainer(system.provider, _path, _parent, _log) {

  import akka.actor.SystemGuardian._

  private val terminating = new Switch(false)

  system.eventStream.subscribe(this, classOf[AddressTerminated])

  /**
   * Find the longest matching path which we know about and return that ref
   * (or ask that ref to continue searching if elements are left).
   */
  override def getChild(names: Iterator[String]): InternalActorRef = {

    @tailrec
    def rec(s: String, n: Int): (InternalActorRef, Int) = {
      import akka.actor.ActorCell._
      val (childName, uid) = splitNameAndUid(s)
      getChild(childName) match {
        case null ⇒
          val last = s.lastIndexOf('/')
          if (last == -1) (Nobody, n)
          else rec(s.substring(0, last), n + 1)
        case ref if uid != undefinedUid && uid != ref.path.uid ⇒ (Nobody, n)
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

  override def sendSystemMessage(message: SystemMessage): Unit = message match {
    case DeathWatchNotification(child: ActorRefWithCell with ActorRefScope, _, _) if child.isLocal ⇒
      terminating.locked {
        removeChild(child.path.elements.drop(1).mkString("/"))
        terminationHookDoneWhenNoChildren()
      }
    case _ ⇒ super.sendSystemMessage(message)
  }

  override def !(msg: Any)(implicit sender: ActorRef = Actor.noSender): Unit = try msg match {
    case message: DaemonMsg ⇒
      log.debug("Received command [{}] to RemoteSystemDaemon on [{}]", message, path.address)
      message match {
        case DaemonMsgCreate(_, _, path, _) if untrustedMode ⇒ log.debug("does not accept deployments (untrusted) for [{}]", path)
        case DaemonMsgCreate(props, deploy, path, supervisor) ⇒
          path match {
            case ActorPathExtractor(address, elems) if elems.nonEmpty && elems.head == "remote" ⇒
              // TODO RK currently the extracted “address” is just ignored, is that okay?
              // TODO RK canonicalize path so as not to duplicate it always #1446
              val subpath = elems.drop(1)
              val p = this.path / subpath
              val childName = {
                val s = subpath.mkString("/")
                val i = s.indexOf('#')
                if (i < 0) s
                else s.substring(0, i)
              }
              val isTerminating = !terminating.whileOff {
                val actor = system.provider.actorOf(system, props, supervisor.asInstanceOf[InternalActorRef],
                  p, systemService = false, Some(deploy), lookupDeploy = true, async = false)
                addChild(childName, actor)
                actor.sendSystemMessage(Watch(actor, this))
                actor.start()
              }
              if (isTerminating) log.error("Skipping [{}] to RemoteSystemDaemon on [{}] while terminating", message, p.address)
            case _ ⇒
              log.debug("remote path does not match path from message [{}]", message)
          }
      }

    case SelectParent(m) ⇒ getParent.tell(m, sender)

    case s @ SelectChildName(name, m) ⇒
      getChild(s.allChildNames.iterator) match {
        case Nobody ⇒
          s.identifyRequest foreach { x ⇒ sender ! ActorIdentity(x.messageId, None) }
        case child ⇒
          child.tell(s.wrappedMessage, sender)
      }

    case SelectChildPattern(p, m) ⇒
      log.error("SelectChildPattern not allowed in actorSelection of remote deployed actors")

    case Identify(messageId) ⇒ sender ! ActorIdentity(messageId, Some(this))

    case t: Terminated       ⇒

    case TerminationHook ⇒
      terminating.switchOn {
        terminationHookDoneWhenNoChildren()
        foreachChild { system.stop }
      }

    case AddressTerminated(address) ⇒
      foreachChild {
        case a: InternalActorRef if a.getParent.path.address == address ⇒ system.stop(a)
        case _ ⇒ // skip, this child doesn't belong to the terminated address
      }

    case unknown ⇒ log.warning("Unknown message [{}] received by [{}]", unknown, this)

  } catch {
    case NonFatal(e) ⇒ log.error(e, "exception while processing remote command [{}] from [{}]", msg, sender)
  }

  def terminationHookDoneWhenNoChildren(): Unit = terminating.whileOn {
    if (!hasChildren) terminator.tell(TerminationHookDone, this)
  }

}
