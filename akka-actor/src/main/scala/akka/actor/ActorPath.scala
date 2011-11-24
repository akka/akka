/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor
import scala.annotation.tailrec

object ActorPath {
  final val separator = "/"

  val pattern = """(/[0-9a-zA-Z\-\_\$\.]+)+""".r.pattern

  /**
   * Create an actor path from a string.
   */
  def apply(system: ActorSystem, path: String): ActorPath =
    apply(system, split(path))

  /**
   * Create an actor path from an iterable.
   */
  def apply(system: ActorSystem, path: Iterable[String]): ActorPath =
    path.foldLeft(system.asInstanceOf[ActorSystemImpl].provider.rootPath)(_ / _)

  /**
   * Split a string path into an iterable.
   */
  def split(path: String): Iterable[String] =
    if (path.startsWith(separator))
      path.substring(1).split(separator)
    else
      path.split(separator)

  /**
   * Join an iterable path into a string.
   */
  def join(path: Iterable[String]): String =
    path.mkString(separator, separator, "")

  /**
   * Is this string representation of a path valid?
   */
  def valid(path: String): Boolean =
    pattern.matcher(path).matches

  /**
   * Validate a path. Moved here from Address.validate.
   * Throws an IllegalArgumentException if the path is invalid.
   */
  def validate(path: String): Unit = {
    if (!valid(path))
      throw new IllegalArgumentException("Path [" + path + "] is not valid. Needs to follow this pattern: " + pattern)
  }
}

/**
 * Actor path is a unique path to an actor that shows the creation path
 * up through the actor tree to the root actor.
 */
trait ActorPath {
  /**
   * The Address under which this path can be reached; walks up the tree to
   * the RootActorPath.
   */
  def address: Address = root.address

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
   * Sequence of names for this path.
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
class RootActorPath(override val address: Address, val name: String = ActorPath.separator) extends ActorPath {

  def parent: ActorPath = this

  def root: RootActorPath = this

  def /(child: String): ActorPath = new ChildActorPath(this, child)

  def pathElements: Iterable[String] = Iterable.empty

  override val toString = address + ActorPath.separator
}

class ChildActorPath(val parent: ActorPath, val name: String) extends ActorPath {

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

  override def toString = {
    @tailrec
    def rec(p: ActorPath, s: String): String = p match {
      case r: RootActorPath ⇒ r + s
      case _ if s.isEmpty   ⇒ rec(p.parent, name)
      case _                ⇒ rec(p.parent, p.name + ActorPath.separator + s)
    }
    rec(this, "")
  }
}

