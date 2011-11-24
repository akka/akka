/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor

import akka.remote.RemoteAddress

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
   * The RemoteAddress for this path.
   */
  def remoteAddress: RemoteAddress

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
   * String representation of this path. Different from toString for root path.
   */
  def string: String

  /**
   * Sequence of names for this path.
   */
  def path: Iterable[String]

  /**
   * Is this the root path?
   */
  def isRoot: Boolean
}

class RootActorPath(val remoteAddress: RemoteAddress) extends ActorPath {

  def name: String = "/"

  def parent: ActorPath = this

  def /(child: String): ActorPath = new ChildActorPath(remoteAddress, this, child)

  def string: String = ""

  def path: Iterable[String] = Iterable.empty

  def isRoot: Boolean = true

  override def toString = ActorPath.separator
}

class ChildActorPath(val remoteAddress: RemoteAddress, val parent: ActorPath, val name: String) extends ActorPath {

  def /(child: String): ActorPath = new ChildActorPath(remoteAddress, this, child)

  def string: String = parent.string + ActorPath.separator + name

  def path: Iterable[String] = parent.path ++ Iterable(name)

  def isRoot: Boolean = false

  override def toString = string
}

