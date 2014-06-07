/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.contrib.datareplication

object Flag {
  val empty = new Flag(false)
  def apply(): Flag = empty
  /**
   * Java API
   */
  def create(): Flag = empty

  def unapply(value: Any): Option[Boolean] = value match {
    case f: Flag ⇒ Some(f.value)
    case _       ⇒ None
  }
}

/**
 * Implements a boolean flag CRDT that is initialized to `false` and
 * can be switched to `true`. `true` wins over `false` in merge.
 */
case class Flag(enabled: Boolean) extends ReplicatedData with ReplicatedDataSerialization {

  type T = Flag

  def value: Boolean = enabled

  def switchOn: Flag =
    if (enabled) this
    else Flag(true)

  override def merge(that: Flag): Flag =
    if (that.enabled) that
    else this
}

