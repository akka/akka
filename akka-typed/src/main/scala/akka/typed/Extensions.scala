/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

/**
 * Marker trait for extensions
 *
 * @see [[ExtensionId]]
 */
trait Extension

/**
 * Identifier and factory for an extension.
 *
 * {{{
 *   class MyExtension(system: ActorSystem[_]) {
 *     ...
 *   }
 *
 *   object MyExtension extends ExtensionId[MyExtension] {
 *     override def create(system: ActorSystem[_]): MyExtension =
 *       new MyExtension(system)
 *   }
 * }}}
 *
 * @tparam T The concrete extension type
 */
abstract class ExtensionId[T <: Extension] {

  def createExtension(system: ActorSystem[_]): T

  final def apply(system: ActorSystem[_]): T = system.registerExtension(this)
  final def create(system: ActorSystem[_]): T = apply(system)

  override final def hashCode: Int = System.identityHashCode(this)
  override final def equals(other: Any): Boolean = this eq other.asInstanceOf[AnyRef]
}

trait Extensions {

  /**
   * Registers the provided extension and creates its payload, if this extension isn't already registered
   * This method has putIfAbsent-semantics, this method can potentially block, waiting for the initialization
   * of the payload, if is in the process of registration from another Thread of execution
   */
  def registerExtension[T <: Extension](ext: ExtensionId[T]): T
  /**
   * Returns the payload that is associated with the provided extension
   * throws an IllegalStateException if it is not registered.
   * This method can potentially block, waiting for the initialization
   * of the payload, if is in the process of registration from another Thread of execution
   */
  def extension[T <: Extension](ext: ExtensionId[T]): T

  /**
   * Returns whether the specified extension is already registered, this method can potentially block, waiting for the initialization
   * of the payload, if is in the process of registration from another Thread of execution
   */
  def hasExtension(ext: ExtensionId[_ <: Extension]): Boolean
}

