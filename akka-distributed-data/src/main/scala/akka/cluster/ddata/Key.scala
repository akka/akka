/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.cluster.ddata

object Key {
  /**
   * Extract the [[Key#id]].
   */
  def unapply(k: Key[_]): Option[String] = Some(k.id)

  private[akka] type KeyR = Key[ReplicatedData]

  type KeyId = String

}

/**
 * Key for the key-value data in [[Replicator]]. The type of the data value
 * is defined in the key. Keys are compared equal if the `id` strings are equal,
 * i.e. use unique identifiers.
 *
 * Specific classes are provided for the built in data types, e.g. [[ORSetKey]],
 * and you can create your own keys.
 */
abstract class Key[+T <: ReplicatedData](val id: Key.KeyId) extends Serializable {

  override final def equals(o: Any): Boolean = o match {
    case k: Key[_] ⇒ id == k.id
    case _         ⇒ false
  }

  override final def hashCode: Int = id.hashCode

  override def toString(): String = id
}

