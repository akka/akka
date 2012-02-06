/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.util.ReflectiveAccess

/**
 * The basic ActorSystem covers all that is needed for locally running actors,
 * using futures and so on. In addition, more features can hook into it and
 * thus become visible to actors et al by registering themselves as extensions.
 * This is accomplished by providing an extension—which is an object
 * implementing this trait—to `ActorSystem.registerExtension(...)` or by
 * specifying the corresponding option in the configuration passed to
 * ActorSystem, which will then instantiate (without arguments) each FQCN and
 * register the result.
 *
 * The extension itself can be created in any way desired and has full access
 * to the ActorSystem implementation.
 *
 * This trait is only a marker interface to signify an Akka Extension, see
 * [[akka.actor.ExtensionKey]] for a concise way of formulating extensions.
 */
trait Extension

/**
 * Identifies an Extension
 * Lookup of Extensions is done by object identity, so the Id must be the same wherever it's used,
 * otherwise you'll get the same extension loaded multiple times.
 */
trait ExtensionId[T <: Extension] {

  /**
   * Returns an instance of the extension identified by this ExtensionId instance.
   */
  def apply(system: ActorSystem): T = system.registerExtension(this)

  /**
   * Returns an instance of the extension identified by this ExtensionId instance.
   * Java API
   * For extensions written in Scala that are to be used used from Java also,
   * this method should be overridden to get correct return type.
   * {{{
   * override def get(system: ActorSystem): TheExtension = super.get(system)
   * }}}
   *
   */
  def get(system: ActorSystem): T = apply(system)

  /**
   * Is used by Akka to instantiate the Extension identified by this ExtensionId,
   * internal use only.
   */
  def createExtension(system: ExtendedActorSystem): T
}

/**
 * Java API for ExtensionId
 */
abstract class AbstractExtensionId[T <: Extension] extends ExtensionId[T]

/**
 * To be able to load an ExtensionId from the configuration,
 * a class that implements ExtensionIdProvider must be specified.
 * The lookup method should return the canonical reference to the extension.
 */
trait ExtensionIdProvider {
  /**
   * Returns the canonical ExtensionId for this Extension
   */
  def lookup(): ExtensionId[_ <: Extension]
}

/**
 * This is a one-stop-shop if all you want is an extension which is
 * constructed with the ActorSystemImpl as its only constructor argument:
 *
 * {{{
 * object MyExt extends ExtensionKey[Ext]
 *
 * class Ext(system: ActorSystemImpl) extends MyExt {
 *   ...
 * }
 * }}}
 *
 * Java API:
 *
 * {{{
 * public class MyExt extends Extension {
 *   static final ExtensionKey<MyExt> key = new ExtensionKey<MyExt>(MyExt.class);
 *
 *   public MyExt(ActorSystemImpl system) {
 *     ...
 *   }
 * }}}
 */
abstract class ExtensionKey[T <: Extension](implicit m: ClassManifest[T]) extends ExtensionId[T] with ExtensionIdProvider {
  def this(clazz: Class[T]) = this()(ClassManifest.fromClass(clazz))

  override def lookup(): ExtensionId[T] = this
  def createExtension(system: ExtendedActorSystem): T =
    ReflectiveAccess.createInstance[T](m.erasure, Array[Class[_]](classOf[ActorSystemImpl]), Array[AnyRef](system)) match {
      case Left(ex) ⇒ throw ex
      case Right(r) ⇒ r
    }
}
