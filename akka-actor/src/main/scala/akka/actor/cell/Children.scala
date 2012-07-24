/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.cell

import scala.annotation.tailrec
import scala.collection.JavaConverters.asJavaIterableConverter
import scala.util.control.NonFatal

import akka.actor.{ RepointableRef, Props, NoSerializationVerificationNeeded, InvalidActorNameException, InternalActorRef, ChildRestartStats, ActorRef }
import akka.actor.ActorCell
import akka.actor.ActorPath.ElementRegex
import akka.serialization.SerializationExtension
import akka.util.{ Unsafe, Helpers }

private[akka] trait Children { this: ActorCell ⇒

  import ChildrenContainer._

  @volatile
  private var _childrenRefsDoNotCallMeDirectly: ChildrenContainer = EmptyChildrenContainer

  def childrenRefs: ChildrenContainer =
    Unsafe.instance.getObjectVolatile(this, AbstractActorCell.childrenOffset).asInstanceOf[ChildrenContainer]

  final def children: Iterable[ActorRef] = childrenRefs.children
  final def getChildren(): java.lang.Iterable[ActorRef] = children.asJava

  def actorOf(props: Props): ActorRef = makeChild(this, props, randomName(), async = false)
  def actorOf(props: Props, name: String): ActorRef = makeChild(this, props, checkName(name), async = false)
  private[akka] def attachChild(props: Props): ActorRef = makeChild(this, props, randomName(), async = true)
  private[akka] def attachChild(props: Props, name: String): ActorRef = makeChild(this, props, checkName(name), async = true)

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

  @tailrec final protected def reserveChild(name: String): Boolean = {
    val c = childrenRefs
    swapChildrenRefs(c, c.reserve(name)) || reserveChild(name)
  }

  @tailrec final protected def unreserveChild(name: String): Boolean = {
    val c = childrenRefs
    swapChildrenRefs(c, c.unreserve(name)) || unreserveChild(name)
  }

  final protected def addChild(ref: ActorRef): Boolean = {
    @tailrec def rec(): Boolean = {
      val c = childrenRefs
      swapChildrenRefs(c, c.add(ref)) || rec()
    }
    /*
     * This does not need to check getByRef every tailcall, because the change 
     * cannot happen in that direction as a race: the only entity removing a 
     * child is the actor itself, and the only entity which could be racing is 
     * somebody who calls attachChild, and there we are guaranteed that that 
     * child cannot yet have died (since it has not yet been created).
     */
    if (childrenRefs.getByRef(ref).isEmpty) rec() else false
  }

  @tailrec final protected def shallDie(ref: ActorRef): Boolean = {
    val c = childrenRefs
    swapChildrenRefs(c, c.shallDie(ref)) || shallDie(ref)
  }

  @tailrec final private def removeChild(ref: ActorRef): ChildrenContainer = {
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

  protected def suspendChildren(skip: Set[ActorRef] = Set.empty): Unit =
    childrenRefs.stats foreach {
      case ChildRestartStats(child, _, _) if !(skip contains child) ⇒ child.asInstanceOf[InternalActorRef].suspend()
      case _ ⇒
    }

  protected def resumeChildren(): Unit =
    childrenRefs.stats foreach (_.child.asInstanceOf[InternalActorRef].resume(inResponseToFailure = false))

  def getChildByName(name: String): Option[ChildRestartStats] = childrenRefs.getByName(name)

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

  private def makeChild(cell: ActorCell, props: Props, name: String, async: Boolean): ActorRef = {
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
            systemService = false, deploy = None, lookupDeploy = true, async = async)
        } catch {
          case NonFatal(e) ⇒
            unreserveChild(name)
            throw e
        }
      addChild(actor)
      actor
    }
  }

}