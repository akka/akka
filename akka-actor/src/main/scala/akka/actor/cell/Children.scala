/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.cell

import scala.annotation.tailrec
import scala.collection.JavaConverters.asJavaIterableConverter
import scala.util.control.NonFatal
import akka.actor._
import akka.actor.ActorCell
import akka.actor.ActorPath.ElementRegex
import akka.serialization.SerializationExtension
import akka.util.{ Unsafe, Helpers }
import akka.actor.ChildNameReserved

private[akka] trait Children { this: ActorCell ⇒

  import ChildrenContainer._

  @volatile
  private var _childrenRefsDoNotCallMeDirectly: ChildrenContainer = EmptyChildrenContainer

  def childrenRefs: ChildrenContainer =
    Unsafe.instance.getObjectVolatile(this, AbstractActorCell.childrenOffset).asInstanceOf[ChildrenContainer]

  final def children: Iterable[ActorRef] = childrenRefs.children
  final def getChildren(): java.lang.Iterable[ActorRef] = children.asJava

  final def childExists(name: String): Boolean = childrenRefs.getByName(name).isDefined

  def actorOf(props: Props): ActorRef =
    makeChild(this, props, randomName(), async = false, systemService = false)
  def actorOf(props: Props, name: String): ActorRef =
    makeChild(this, props, checkName(name), async = false, systemService = false)
  private[akka] def attachChild(props: Props, systemService: Boolean): ActorRef =
    makeChild(this, props, randomName(), async = true, systemService = systemService)
  private[akka] def attachChild(props: Props, name: String, systemService: Boolean): ActorRef =
    makeChild(this, props, checkName(name), async = true, systemService = systemService)

  @volatile private var _nextNameDoNotCallMeDirectly = 0L
  final protected def randomName(): String = {
    @tailrec def inc(): Long = {
      val current = Unsafe.instance.getLongVolatile(this, AbstractActorCell.nextNameOffset)
      if (Unsafe.instance.compareAndSwapLong(this, AbstractActorCell.nextNameOffset, current, current + 1)) current
      else inc()
    }
    Helpers.base64(inc())
  }

  final def stop(actor: ActorRef): Unit = {
    val started = actor match {
      case r: RepointableRef ⇒ r.isStarted
      case _                 ⇒ true
    }
    if (childrenRefs.getByRef(actor).isDefined && started) shallDie(actor)
    actor.asInstanceOf[InternalActorRef].stop()
  }

  /*
   * low level CAS helpers
   */

  @inline private def swapChildrenRefs(oldChildren: ChildrenContainer, newChildren: ChildrenContainer): Boolean =
    Unsafe.instance.compareAndSwapObject(this, AbstractActorCell.childrenOffset, oldChildren, newChildren)

  @tailrec final def reserveChild(name: String): Boolean = {
    val c = childrenRefs
    swapChildrenRefs(c, c.reserve(name)) || reserveChild(name)
  }

  @tailrec final protected def unreserveChild(name: String): Boolean = {
    val c = childrenRefs
    swapChildrenRefs(c, c.unreserve(name)) || unreserveChild(name)
  }

  @tailrec final def initChild(ref: ActorRef): Option[ChildRestartStats] =
    childrenRefs.getByName(ref.path.name) match {
      case old @ Some(_: ChildRestartStats) ⇒ old.asInstanceOf[Option[ChildRestartStats]]
      case Some(ChildNameReserved) ⇒
        val crs = ChildRestartStats(ref)
        val name = ref.path.name
        val c = childrenRefs
        if (swapChildrenRefs(c, c.add(name, crs))) Some(crs) else initChild(ref)
      case None ⇒ None
    }

  @tailrec final protected def shallDie(ref: ActorRef): Boolean = {
    val c = childrenRefs
    swapChildrenRefs(c, c.shallDie(ref)) || shallDie(ref)
  }

  @tailrec final protected def removeChild(ref: ActorRef): ChildrenContainer = {
    val c = childrenRefs
    val n = c.remove(ref)
    if (swapChildrenRefs(c, n)) n
    else removeChild(ref)
  }

  @tailrec final protected def setChildrenTerminationReason(reason: ChildrenContainer.SuspendReason): Boolean = {
    childrenRefs match {
      case c: ChildrenContainer.TerminatingChildrenContainer ⇒
        swapChildrenRefs(c, c.copy(reason = reason)) || setChildrenTerminationReason(reason)
      case _ ⇒ false
    }
  }

  final protected def setTerminated(): Unit = Unsafe.instance.putObjectVolatile(this, AbstractActorCell.childrenOffset, TerminatedChildrenContainer)

  /*
   * ActorCell-internal API
   */

  protected def isNormal = childrenRefs.isNormal

  protected def isTerminating = childrenRefs.isTerminating

  protected def recreationOrNull = childrenRefs match {
    case TerminatingChildrenContainer(_, _, r: Recreation) ⇒ r
    case _ ⇒ null
  }

  protected def suspendChildren(exceptFor: Set[ActorRef] = Set.empty): Unit =
    childrenRefs.stats foreach {
      case ChildRestartStats(child, _, _) if !(exceptFor contains child) ⇒ child.asInstanceOf[InternalActorRef].suspend()
      case _ ⇒
    }

  protected def resumeChildren(causedByFailure: Throwable, perp: ActorRef): Unit =
    childrenRefs.stats foreach {
      case ChildRestartStats(child: InternalActorRef, _, _) ⇒
        child.resume(if (perp == child) causedByFailure else null)
    }

  def getChildByName(name: String): Option[ChildStats] = childrenRefs.getByName(name)

  protected def getChildByRef(ref: ActorRef): Option[ChildRestartStats] = childrenRefs.getByRef(ref)

  protected def getAllChildStats: Iterable[ChildRestartStats] = childrenRefs.stats

  protected def removeChildAndGetStateChange(child: ActorRef): Option[SuspendReason] = {
    childrenRefs match {
      case TerminatingChildrenContainer(_, _, reason) ⇒
        val newContainer = removeChild(child)
        if (!newContainer.isInstanceOf[TerminatingChildrenContainer]) Some(reason) else None
      case _ ⇒
        removeChild(child)
        None
    }
  }

  /*
   * Private helpers
   */

  private def checkName(name: String): String = {
    name match {
      case null           ⇒ throw new InvalidActorNameException("actor name must not be null")
      case ""             ⇒ throw new InvalidActorNameException("actor name must not be empty")
      case ElementRegex() ⇒ name
      case _              ⇒ throw new InvalidActorNameException("illegal actor name '" + name + "', must conform to " + ElementRegex)
    }
  }

  private def makeChild(cell: ActorCell, props: Props, name: String, async: Boolean, systemService: Boolean): ActorRef = {
    if (cell.system.settings.SerializeAllCreators && !props.creator.isInstanceOf[NoSerializationVerificationNeeded]) {
      val ser = SerializationExtension(cell.system)
      ser.serialize(props.creator) match {
        case Left(t) ⇒ throw t
        case Right(bytes) ⇒ ser.deserialize(bytes, props.creator.getClass) match {
          case Left(t) ⇒ throw t
          case _       ⇒ //All good
        }
      }
    }
    /*
     * in case we are currently terminating, fail external attachChild requests
     * (internal calls cannot happen anyway because we are suspended)
     */
    if (cell.childrenRefs.isTerminating) throw new IllegalStateException("cannot create children while terminating or terminated")
    else {
      reserveChild(name)
      // this name will either be unreserved or overwritten with a real child below
      val actor =
        try {
          cell.provider.actorOf(cell.systemImpl, props, cell.self, cell.self.path / name,
            systemService = systemService, deploy = None, lookupDeploy = true, async = async)
        } catch {
          case NonFatal(e) ⇒
            unreserveChild(name)
            throw e
        }
      // mailbox==null during RoutedActorCell constructor, where suspends are queued otherwise
      if (mailbox ne null) for (_ ← 1 to mailbox.suspendCount) actor.suspend()
      initChild(actor)
      actor
    }
  }

}