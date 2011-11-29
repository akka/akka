/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor
import scala.annotation.tailrec

object ActorPath {
  // this cannot really be changed due to usage of standard URI syntax
  final val separator = "/"
}

/**
 * Actor path is a unique path to an actor that shows the creation path
 * up through the actor tree to the root actor.
 */
sealed trait ActorPath {
  /**
   * The Address under which this path can be reached; walks up the tree to
   * the RootActorPath.
   */
  def address: Address

  /**
   * The name of the actor that this path refers to.
   */
  def name: String

  /**
   * The path for the parent actor.
   */
  def parent: ActorPath

  /**
   * Create a new child actor path.
   */
  def /(child: String): ActorPath

  /**
   * Recursively create a descendant’s path by appending all child names.
   */
  def /(child: Iterable[String]): ActorPath = (this /: child)(_ / _)

  /**
   * Sequence of names for this path. Performance implication: has to allocate a list.
   */
  def pathElements: Iterable[String]

  /**
   * Walk up the tree to obtain and return the RootActorPath.
   */
  def root: RootActorPath

}

/**
 * Root of the hierarchy of ActorPaths. There is exactly root per ActorSystem
 * and node (for remote-enabled or clustered systems).
 */
final case class RootActorPath(address: Address, name: String = ActorPath.separator) extends ActorPath {

  def parent: ActorPath = this

  def root: RootActorPath = this

  def /(child: String): ActorPath = new ChildActorPath(this, child)

  def pathElements: Iterable[String] = Iterable.empty

  override val toString = address + name
}

final class ChildActorPath(val parent: ActorPath, val name: String) extends ActorPath {

  def address: Address = root.address

  def /(child: String): ActorPath = new ChildActorPath(this, child)

  def pathElements: Iterable[String] = {
    @tailrec
    def rec(p: ActorPath, acc: List[String]): Iterable[String] = p match {
      case r: RootActorPath ⇒ acc
      case _                ⇒ rec(p.parent, p.name :: acc)
    }
    rec(this, Nil)
  }

  def root = {
    @tailrec
    def rec(p: ActorPath): RootActorPath = p match {
      case r: RootActorPath ⇒ r
      case _                ⇒ rec(p.parent)
    }
    rec(this)
  }

  // TODO research whether this should be cached somehow (might be fast enough, but creates GC pressure)
  /*
   * idea: add one field which holds the total length (because that is known) 
   * so that only one String needs to be allocated before traversal; this is
   * cheaper than any cache
   */
  override def toString = {
    @tailrec
    def rec(p: ActorPath, s: String): String = p match {
      case r: RootActorPath ⇒ r + s
      case _ if s.isEmpty   ⇒ rec(p.parent, name)
      case _                ⇒ rec(p.parent, p.name + ActorPath.separator + s)
    }
    rec(this, "")
  }

  override def equals(other: Any): Boolean = {
    @tailrec
    def rec(left: ActorPath, right: ActorPath): Boolean =
      if (left eq right) true
      else if (left.isInstanceOf[RootActorPath] || right.isInstanceOf[RootActorPath]) left == right
      else left.name == right.name && rec(left.parent, right.parent)

    other match {
      case p: ActorPath ⇒ rec(this, p)
      case _            ⇒ false
    }
  }

  override def hashCode: Int = {
    import scala.util.MurmurHash._

    @tailrec
    def rec(p: ActorPath, h: Int, c: Int, k: Int): Int = p match {
      case r: RootActorPath ⇒ extendHash(h, r.##, c, k)
      case _                ⇒ rec(p.parent, extendHash(h, stringHash(name), c, k), nextMagicA(c), nextMagicB(k))
    }

    finalizeHash(rec(this, startHash(42), startMagicA, startMagicB))
  }
}

