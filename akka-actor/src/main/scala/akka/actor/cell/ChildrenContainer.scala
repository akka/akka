/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.cell

import scala.collection.immutable.TreeMap

import akka.actor.{ InvalidActorNameException, ChildStats, ChildRestartStats, ChildNameReserved, ActorRef }

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
}

/**
 * INTERNAL API
 *
 * This object holds the classes performing the logic of managing the children
 * of an actor, hence they are intimately tied to ActorCell.
 */
private[akka] object ChildrenContainer {

  sealed trait SuspendReason
  case object UserRequest extends SuspendReason
  case class Recreation(cause: Throwable) extends SuspendReason
  case object Termination extends SuspendReason

  trait EmptyChildrenContainer extends ChildrenContainer {
    val emptyStats = TreeMap.empty[String, ChildStats]
    override def add(child: ActorRef): ChildrenContainer =
      new NormalChildrenContainer(emptyStats.updated(child.path.name, ChildRestartStats(child)))
    override def remove(child: ActorRef): ChildrenContainer = this
    override def getByName(name: String): Option[ChildRestartStats] = None
    override def getByRef(actor: ActorRef): Option[ChildRestartStats] = None
    override def children: Iterable[ActorRef] = Nil
    override def stats: Iterable[ChildRestartStats] = Nil
    override def shallDie(actor: ActorRef): ChildrenContainer = this
    override def reserve(name: String): ChildrenContainer = new NormalChildrenContainer(emptyStats.updated(name, ChildNameReserved))
    override def unreserve(name: String): ChildrenContainer = this
  }

  /**
   * This is the empty container, shared among all leaf actors.
   */
  object EmptyChildrenContainer extends EmptyChildrenContainer {
    override def toString = "no children"
  }

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
    override def toString = "terminated"
  }

  /**
   * Normal children container: we do have at least one child, but none of our
   * children are currently terminating (which is the time period between
   * calling context.stop(child) and processing the ChildTerminated() system
   * message).
   */
  class NormalChildrenContainer(val c: TreeMap[String, ChildStats]) extends ChildrenContainer {

    override def add(child: ActorRef): ChildrenContainer =
      new NormalChildrenContainer(c.updated(child.path.name, ChildRestartStats(child)))

    override def remove(child: ActorRef): ChildrenContainer = NormalChildrenContainer(c - child.path.name)

    override def getByName(name: String): Option[ChildRestartStats] = c.get(name) match {
      case s @ Some(_: ChildRestartStats) ⇒ s.asInstanceOf[Option[ChildRestartStats]]
      case _                              ⇒ None
    }

    override def getByRef(actor: ActorRef): Option[ChildRestartStats] = c.get(actor.path.name) match {
      case c @ Some(crs: ChildRestartStats) if (crs.child == actor) ⇒ c.asInstanceOf[Option[ChildRestartStats]]
      case _ ⇒ None
    }

    override def children: Iterable[ActorRef] = c.values.view.collect { case ChildRestartStats(child, _, _) ⇒ child }

    override def stats: Iterable[ChildRestartStats] = c.values.view.collect { case c: ChildRestartStats ⇒ c }

    override def shallDie(actor: ActorRef): ChildrenContainer = TerminatingChildrenContainer(c, Set(actor), UserRequest)

    override def reserve(name: String): ChildrenContainer =
      if (c contains name)
        throw new InvalidActorNameException("actor name " + name + " is not unique!")
      else new NormalChildrenContainer(c.updated(name, ChildNameReserved))

    override def unreserve(name: String): ChildrenContainer = c.get(name) match {
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

    override def add(child: ActorRef): ChildrenContainer = copy(c.updated(child.path.name, ChildRestartStats(child)))

    override def remove(child: ActorRef): ChildrenContainer = {
      val t = toDie - child
      if (t.isEmpty) reason match {
        case Termination ⇒ TerminatedChildrenContainer
        case _           ⇒ NormalChildrenContainer(c - child.path.name)
      }
      else copy(c - child.path.name, t)
    }

    override def getByName(name: String): Option[ChildRestartStats] = c.get(name) match {
      case s @ Some(_: ChildRestartStats) ⇒ s.asInstanceOf[Option[ChildRestartStats]]
      case _                              ⇒ None
    }

    override def getByRef(actor: ActorRef): Option[ChildRestartStats] = c.get(actor.path.name) match {
      case c @ Some(crs: ChildRestartStats) if (crs.child == actor) ⇒ c.asInstanceOf[Option[ChildRestartStats]]
      case _ ⇒ None
    }

    override def children: Iterable[ActorRef] = c.values.view.collect { case ChildRestartStats(child, _, _) ⇒ child }

    override def stats: Iterable[ChildRestartStats] = c.values.view.collect { case c: ChildRestartStats ⇒ c }

    override def shallDie(actor: ActorRef): ChildrenContainer = copy(toDie = toDie + actor)

    override def reserve(name: String): ChildrenContainer = reason match {
      case Termination ⇒ throw new IllegalStateException("cannot reserve actor name '" + name + "': terminating")
      case _ ⇒
        if (c contains name)
          throw new InvalidActorNameException("actor name " + name + " is not unique!")
        else copy(c = c.updated(name, ChildNameReserved))
    }

    override def unreserve(name: String): ChildrenContainer = c.get(name) match {
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