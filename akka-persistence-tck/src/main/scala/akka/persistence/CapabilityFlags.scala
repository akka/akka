/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence

import scala.language.implicitConversions

sealed abstract class CapabilityFlag {
  private val capturedStack = (new Throwable().getStackTrace)
    .filter(_.getMethodName.startsWith("supports"))
    .find { el ⇒
      val clazz = Class.forName(el.getClassName)
      clazz.getDeclaredMethod(el.getMethodName).getReturnType == classOf[CapabilityFlag]
    } map { _.getMethodName } getOrElse "[unknown]"

  def name: String = capturedStack
  def value: Boolean
}
object CapabilityFlag {
  def on(): CapabilityFlag =
    new CapabilityFlag { override def value = true }
  def off(): CapabilityFlag =
    new CapabilityFlag { override def value = false }

  /** Java DSL */
  def create(`val`: Boolean): CapabilityFlag =
    new CapabilityFlag { override def value = `val` }

  // conversions

  implicit def mkFlag(v: Boolean): CapabilityFlag =
    new CapabilityFlag { override def value = v }
}

sealed trait CapabilityFlags

//#journal-flags
trait JournalCapabilityFlags extends CapabilityFlags {

  /**
   * When `true` enables tests which check if the Journal properly rejects
   * writes of objects which are not `java.lang.Serializable`.
   */
  protected def supportsRejectingNonSerializableObjects: CapabilityFlag

  /**
   * When `true` enables tests which check if the Journal properly serialize and
   * deserialize events.
   */
  protected def supportsSerialization: CapabilityFlag

}
//#journal-flags

//#snapshot-store-flags
trait SnapshotStoreCapabilityFlags extends CapabilityFlags {
  /**
   * When `true` enables tests which check if the snapshot store properly serialize and
   * deserialize snapshots.
   */
  protected def supportsSerialization: CapabilityFlag
}
//#snapshot-store-flags
