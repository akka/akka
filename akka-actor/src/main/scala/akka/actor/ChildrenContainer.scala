/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import scala.collection.immutable.TreeMap
import scala.annotation.tailrec
import akka.util.Unsafe
import akka.serialization.SerializationExtension
import akka.util.NonFatal

/**
 * INTERNAL API
 */
private[akka] trait ChildrenContainer {

  def add(child: ActorRef): ChildrenContainer
  def remove(child: ActorRef): ChildrenContainer

  def getByName(name: String): Option[ChildRestartStats]
  def getByRef(actor: ActorRef): Option[ChildRestartStats]

  def children: Iterable[ActorRef]
  def stats: Iterable[ChildRestartStats]

  def shallDie(actor: ActorRef): ChildrenContainer

  // reserve that name or throw an exception
  def reserve(name: String): ChildrenContainer
  // cancel a reservation
  def unreserve(name: String): ChildrenContainer

  def isTerminating: Boolean = false
  def isNormal: Boolean = true

  def suspendChildren(skip: Set[ActorRef] = Set.empty): Unit =
    stats collect {
      case ChildRestartStats(child, _, _) if !(skip contains child) ⇒ child
    } foreach (_.asInstanceOf[InternalActorRef].suspend())

  def resumeChildren(): Unit = stats foreach (_.child.asInstanceOf[InternalActorRef].suspend())
}

/**
 * INTERNAL API
 *
 * This object holds the classes performing the logic of managing the children
 * of an actor, hence they are intimately tied to ActorCell.
 */
private[akka] object ChildrenContainer {

  // low level CAS helpers
  @inline private def swapChildrenRefs(cell: ActorCell, oldChildren: ChildrenContainer, newChildren: ChildrenContainer): Boolean =
    Unsafe.instance.compareAndSwapObject(cell, AbstractActorCell.childrenOffset, oldChildren, newChildren)

  @tailrec final def reserveChild(cell: ActorCell, name: String): Boolean = {
    val c = cell.childrenRefs
    swapChildrenRefs(cell, c, c.reserve(name)) || reserveChild(cell, name)
  }

  @tailrec final def unreserveChild(cell: ActorCell, name: String): Boolean = {
    val c = cell.childrenRefs
    swapChildrenRefs(cell, c, c.unreserve(name)) || unreserveChild(cell, name)
  }

  @tailrec final def addChild(cell: ActorCell, ref: ActorRef): Boolean = {
    val c = cell.childrenRefs
    swapChildrenRefs(cell, c, c.add(ref)) || addChild(cell, ref)
  }

  @tailrec final def shallDie(cell: ActorCell, ref: ActorRef): Boolean = {
    val c = cell.childrenRefs
    swapChildrenRefs(cell, c, c.shallDie(ref)) || shallDie(cell, ref)
  }

  @tailrec final def removeChild(cell: ActorCell, ref: ActorRef): ChildrenContainer = {
    val c = cell.childrenRefs
    val n = c.remove(ref)
    if (swapChildrenRefs(cell, c, n)) n
    else removeChild(cell, ref)
  }

  @tailrec final def setChildrenTerminationReason(cell: ActorCell, reason: ChildrenContainer.SuspendReason): Boolean = {
    cell.childrenRefs match {
      case c: ChildrenContainer.TerminatingChildrenContainer ⇒
        swapChildrenRefs(cell, c, c.copy(reason = reason)) || setChildrenTerminationReason(cell, reason)
      case _ ⇒ false
    }
  }

  def checkName(name: String): String = {
    import ActorPath.ElementRegex
    name match {
      case null           ⇒ throw new InvalidActorNameException("actor name must not be null")
      case ""             ⇒ throw new InvalidActorNameException("actor name must not be empty")
      case ElementRegex() ⇒ name
      case _              ⇒ throw new InvalidActorNameException("illegal actor name '" + name + "', must conform to " + ElementRegex)
    }
  }

  def makeChild(cell: ActorCell, props: Props, name: String, async: Boolean): ActorRef = {
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
      reserveChild(cell, name)
      // this name will either be unreserved or overwritten with a real child below
      val actor =
        try {
          cell.provider.actorOf(cell.systemImpl, props, cell.self, cell.self.path / name,
            systemService = false, deploy = None, lookupDeploy = true, async = async)
        } catch {
          case NonFatal(e) ⇒
            unreserveChild(cell, name)
            throw e
        }
      addChild(cell, actor)
      actor
    }
  }

  sealed trait SuspendReason
  case object UserRequest extends SuspendReason
  case class Recreation(cause: Throwable) extends SuspendReason
  case object Termination extends SuspendReason

  trait EmptyChildrenContainer extends ChildrenContainer {
    val emptyStats = TreeMap.empty[String, ChildStats]
    def add(child: ActorRef): ChildrenContainer =
      new NormalChildrenContainer(emptyStats.updated(child.path.name, ChildRestartStats(child)))
    def remove(child: ActorRef): ChildrenContainer = this
    def getByName(name: String): Option[ChildRestartStats] = None
    def getByRef(actor: ActorRef): Option[ChildRestartStats] = None
    def children: Iterable[ActorRef] = Nil
    def stats: Iterable[ChildRestartStats] = Nil
    def shallDie(actor: ActorRef): ChildrenContainer = this
    def reserve(name: String): ChildrenContainer = new NormalChildrenContainer(emptyStats.updated(name, ChildNameReserved))
    def unreserve(name: String): ChildrenContainer = this
    override def toString = "no children"
  }

  /**
   * This is the empty container, shared among all leaf actors.
   */
  object EmptyChildrenContainer extends EmptyChildrenContainer

  /**
   * This is the empty container which is installed after the last child has
   * terminated while stopping; it is necessary to distinguish from the normal
   * empty state while calling handleChildTerminated() for the last time.
   */
  object TerminatedChildrenContainer extends EmptyChildrenContainer {
    override def add(child: ActorRef): ChildrenContainer = this
    override def reserve(name: String): ChildrenContainer =
      throw new IllegalStateException("cannot reserve actor name '" + name + "': already terminated")
    override def isTerminating: Boolean = true
    override def isNormal: Boolean = false
  }

  /**
   * Normal children container: we do have at least one child, but none of our
   * children are currently terminating (which is the time period between
   * calling context.stop(child) and processing the ChildTerminated() system
   * message).
   */
  class NormalChildrenContainer(c: TreeMap[String, ChildStats]) extends ChildrenContainer {

    def add(child: ActorRef): ChildrenContainer =
      new NormalChildrenContainer(c.updated(child.path.name, ChildRestartStats(child)))

    def remove(child: ActorRef): ChildrenContainer = NormalChildrenContainer(c - child.path.name)

    def getByName(name: String): Option[ChildRestartStats] = c.get(name) match {
      case s @ Some(_: ChildRestartStats) ⇒ s.asInstanceOf[Option[ChildRestartStats]]
      case _                              ⇒ None
    }

    def getByRef(actor: ActorRef): Option[ChildRestartStats] = c.get(actor.path.name) match {
      case c @ Some(crs: ChildRestartStats) if (crs.child == actor) ⇒ c.asInstanceOf[Option[ChildRestartStats]]
      case _ ⇒ None
    }

    def children: Iterable[ActorRef] = c.values.view.collect { case ChildRestartStats(child, _, _) ⇒ child }

    def stats: Iterable[ChildRestartStats] = c.values.collect { case c: ChildRestartStats ⇒ c }

    def shallDie(actor: ActorRef): ChildrenContainer = TerminatingChildrenContainer(c, Set(actor), UserRequest)

    def reserve(name: String): ChildrenContainer =
      if (c contains name)
        throw new InvalidActorNameException("actor name " + name + " is not unique!")
      else new NormalChildrenContainer(c.updated(name, ChildNameReserved))

    def unreserve(name: String): ChildrenContainer = c.get(name) match {
      case Some(ChildNameReserved) ⇒ NormalChildrenContainer(c - name)
      case _                       ⇒ this
    }

    override def toString =
      if (c.size > 20) c.size + " children"
      else c.mkString("children:\n    ", "\n    ", "")
  }

  object NormalChildrenContainer {
    def apply(c: TreeMap[String, ChildStats]): ChildrenContainer =
      if (c.isEmpty) EmptyChildrenContainer
      else new NormalChildrenContainer(c)
  }

  /**
   * Waiting state: there are outstanding termination requests (i.e. context.stop(child)
   * was called but the corresponding ChildTerminated() system message has not yet been
   * processed). There could be no specific reason (UserRequested), we could be Restarting
   * or Terminating.
   *
   * Removing the last child which was supposed to be terminating will return a different
   * type of container, depending on whether or not children are left and whether or not
   * the reason was “Terminating”.
   */
  case class TerminatingChildrenContainer(c: TreeMap[String, ChildStats], toDie: Set[ActorRef], reason: SuspendReason)
    extends ChildrenContainer {

    def add(child: ActorRef): ChildrenContainer = copy(c.updated(child.path.name, ChildRestartStats(child)))

    def remove(child: ActorRef): ChildrenContainer = {
      val t = toDie - child
      if (t.isEmpty) reason match {
        case Termination ⇒ TerminatedChildrenContainer
        case _           ⇒ NormalChildrenContainer(c - child.path.name)
      }
      else copy(c - child.path.name, t)
    }

    def getByName(name: String): Option[ChildRestartStats] = c.get(name) match {
      case s @ Some(_: ChildRestartStats) ⇒ s.asInstanceOf[Option[ChildRestartStats]]
      case _                              ⇒ None
    }

    def getByRef(actor: ActorRef): Option[ChildRestartStats] = c.get(actor.path.name) match {
      case c @ Some(crs: ChildRestartStats) if (crs.child == actor) ⇒ c.asInstanceOf[Option[ChildRestartStats]]
      case _ ⇒ None
    }

    def children: Iterable[ActorRef] = c.values.view.collect { case ChildRestartStats(child, _, _) ⇒ child }

    def stats: Iterable[ChildRestartStats] = c.values.collect { case c: ChildRestartStats ⇒ c }

    def shallDie(actor: ActorRef): ChildrenContainer = copy(toDie = toDie + actor)

    def reserve(name: String): ChildrenContainer = reason match {
      case Termination ⇒ throw new IllegalStateException("cannot reserve actor name '" + name + "': terminating")
      case _ ⇒
        if (c contains name)
          throw new InvalidActorNameException("actor name " + name + " is not unique!")
        else copy(c = c.updated(name, ChildNameReserved))
    }

    def unreserve(name: String): ChildrenContainer = c.get(name) match {
      case Some(ChildNameReserved) ⇒ copy(c = c - name)
      case _                       ⇒ this
    }

    override def isTerminating: Boolean = reason == Termination
    override def isNormal: Boolean = reason == UserRequest

    override def toString =
      if (c.size > 20) c.size + " children"
      else c.mkString("children (" + toDie.size + " terminating):\n    ", "\n    ", "\n") + toDie
  }

}