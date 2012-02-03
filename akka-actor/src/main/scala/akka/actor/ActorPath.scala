/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor
import scala.annotation.tailrec
import java.net.MalformedURLException

object ActorPath {
  def split(s: String): List[String] = {
    @tailrec
    def rec(pos: Int, acc: List[String]): List[String] = {
      val from = s.lastIndexOf('/', pos - 1)
      val sub = s.substring(from + 1, pos)
      val l = sub :: acc
      if (from == -1) l else rec(from, l)
    }
    rec(s.length, Nil)
  }

  /**
   * Parse string as actor path; throws java.net.MalformedURLException if unable to do so.
   */
  def fromString(s: String): ActorPath = s match {
    case ActorPathExtractor(addr, elems) ⇒ RootActorPath(addr) / elems
    case _                               ⇒ throw new MalformedURLException("cannot parse as ActorPath: " + s)
  }

  val ElementRegex = """[-\w:@&=+,.!~*'_;][-\w:@&=+,.!~*'$_;]*""".r
}

/**
 * Actor path is a unique path to an actor that shows the creation path
 * up through the actor tree to the root actor.
 *
 * ActorPath defines a natural ordering (so that ActorRefs can be put into
 * collections with this requirement); this ordering is intended to be as fast
 * as possible, which owing to the bottom-up recursive nature of ActorPath
 * is sorted by path elements FROM RIGHT TO LEFT, where RootActorPath >
 * ChildActorPath in case the number of elements is different.
 */
sealed trait ActorPath extends Comparable[ActorPath] with Serializable {
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
   * ''Java API'': Create a new child actor path.
   */
  def child(child: String): ActorPath = /(child)

  /**
   * Recursively create a descendant’s path by appending all child names.
   */
  def /(child: Iterable[String]): ActorPath = (this /: child)((path, elem) ⇒ if (elem.isEmpty) path else path / elem)

  /**
   * ''Java API'': Recursively create a descendant’s path by appending all child names.
   */
  def descendant(names: java.lang.Iterable[String]): ActorPath = {
    import scala.collection.JavaConverters._
    /(names.asScala)
  }

  /**
   * Sequence of names for this path from root to this. Performance implication: has to allocate a list.
   */
  def elements: Iterable[String]

  /**
   * ''Java API'': Sequence of names for this path from root to this. Performance implication: has to allocate a list.
   */
  def getElements: java.lang.Iterable[String] = {
    import scala.collection.JavaConverters._
    elements.asJava
  }

  /**
   * Walk up the tree to obtain and return the RootActorPath.
   */
  def root: RootActorPath

  /**
   * Generate String representation, replacing the Address in the RootActor
   * Path with the given one unless this path’s address includes host and port
   * information.
   */
  def toStringWithAddress(address: Address): String
}

/**
 * Root of the hierarchy of ActorPaths. There is exactly root per ActorSystem
 * and node (for remote-enabled or clustered systems).
 */
final case class RootActorPath(address: Address, name: String = "/") extends ActorPath {

  def parent: ActorPath = this

  def root: RootActorPath = this

  def /(child: String): ActorPath = new ChildActorPath(this, child)

  val elements: Iterable[String] = List("")

  override val toString = address + name

  def toStringWithAddress(addr: Address): String =
    if (address.host.isDefined) address + name
    else addr + name

  def compareTo(other: ActorPath) = other match {
    case r: RootActorPath  ⇒ toString compareTo r.toString
    case c: ChildActorPath ⇒ 1
  }
}

final class ChildActorPath(val parent: ActorPath, val name: String) extends ActorPath {
  if (name.indexOf('/') != -1) throw new IllegalArgumentException("/ is a path separator and is not legal in ActorPath names: [%s]" format name)

  def address: Address = root.address

  def /(child: String): ActorPath = new ChildActorPath(this, child)

  def elements: Iterable[String] = {
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
    def rec(p: ActorPath, s: StringBuilder): StringBuilder = p match {
      case r: RootActorPath ⇒ s.insert(0, r.toString)
      case _                ⇒ rec(p.parent, s.insert(0, '/').insert(0, p.name))
    }
    rec(parent, new StringBuilder(32).append(name)).toString
  }

  override def toStringWithAddress(addr: Address) = {
    @tailrec
    def rec(p: ActorPath, s: StringBuilder): StringBuilder = p match {
      case r: RootActorPath ⇒ s.insert(0, r.toStringWithAddress(addr))
      case _                ⇒ rec(p.parent, s.insert(0, '/').insert(0, p.name))
    }
    rec(parent, new StringBuilder(32).append(name)).toString
  }

  override def equals(other: Any): Boolean = {
    @tailrec
    def rec(left: ActorPath, right: ActorPath): Boolean =
      if (left eq right) true
      else if (left.isInstanceOf[RootActorPath]) left equals right
      else if (right.isInstanceOf[RootActorPath]) right equals left
      else left.name == right.name && rec(left.parent, right.parent)

    other match {
      case p: ActorPath ⇒ rec(this, p)
      case _            ⇒ false
    }
  }

  // TODO RK investigate Phil’s hash from scala.collection.mutable.HashTable.improve
  override def hashCode: Int = {
    import scala.util.MurmurHash._

    @tailrec
    def rec(p: ActorPath, h: Int, c: Int, k: Int): Int = p match {
      case r: RootActorPath ⇒ extendHash(h, r.##, c, k)
      case _                ⇒ rec(p.parent, extendHash(h, stringHash(name), c, k), nextMagicA(c), nextMagicB(k))
    }

    finalizeHash(rec(this, startHash(42), startMagicA, startMagicB))
  }

  def compareTo(other: ActorPath) = {
    @tailrec
    def rec(left: ActorPath, right: ActorPath): Int =
      if (left eq right) 0
      else if (left.isInstanceOf[RootActorPath]) left compareTo right
      else if (right.isInstanceOf[RootActorPath]) -(right compareTo left)
      else {
        val x = left.name compareTo right.name
        if (x == 0) rec(left.parent, right.parent)
        else x
      }

    rec(this, other)
  }
}

