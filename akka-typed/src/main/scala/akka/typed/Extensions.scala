/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import akka.annotation.DoNotInherit

/**
 * Marker trait/interface for extensions. An extension can be registered in the ActorSystem and is guaranteed to only
 * have one instance per [[ActorSystem]] instance per [[ExtensionId]]. The extension internals must be thread safe.
 * For mutable state it should be preferred to use an `Actor` rather than extensions as first choice.
 *
 * @see [[ExtensionId]]
 */
trait Extension

/**
 * Identifier and factory for an extension. Is used to look up an extension from the `ActorSystem`, and possibly create
 * an instance if no instance was already registered.
 *
 * Scala API:
 *
 * {{{
 * object MyExt extends ExtensionId[Ext] {
 *
 *   override def createExtension(system: ActorSystem[_]): MyExt = new MyExt(system)
 *
 *   // Java API: retrieve the extension for the given system.
 *   override def get(system: ActorSystem[_]): MyExt = super.get(system)
 * }
 *
 * class MyExt(system: ActorSystem[_]) extends Extension {
 *   ...
 * }
 * }}}
 *
 * Java API:
 *
 * {{{
 *
 * public class MyExt extends AbstractExtensionId<MyExtImpl> {
 *   // single instance of the identifier
 *   private final static MyExt instance = new MyExt();
 *
 *   // protect against other instances than the singleton
 *   private MyExt() {}
 *
 *   // This static method singleton accessor is needed to be able to enable the extension through config when
 *   // implementing extensions in Java.
 *   public static MyExt getInstance() {
 *     return instance;
 *   }
 *
 *   public MyExtImpl createExtension(ExtendedActorSystem system) {
 *     return new MyExtImpl();
 *   }
 * }
 *
 * public class MyExtImpl implements Extension {
 *    ...
 * }
 * }}}
 *
 * @tparam T The concrete extension type
 */
abstract class ExtensionId[T <: Extension] {

  /**
   * Create the extension, will be invoked at most one time per actor system where the extension is registered.
   */
  def createExtension(system: ActorSystem[_]): T

  /**
   * Lookup or create an instance of the extension identified by this id.
   */
  final def apply(system: ActorSystem[_]): T = system.registerExtension(this)

  /**
   * Lookup or create an instance of the extension identified by this id.
   *
   * Override this with the concrete extension as return type to make the extension usable form Java.
   */
  def get(system: ActorSystem[_]): T = system.registerExtension(this)

  override final def hashCode: Int = System.identityHashCode(this)
  override final def equals(other: Any): Boolean = this eq other.asInstanceOf[AnyRef]
}

/**
 * API for registering and looking up extensions.
 *
 * Not intended to be extended by user code.
 */
@DoNotInherit
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

