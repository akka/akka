/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

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
 */
trait Extension[T <: AnyRef] {
  def apply(system: ActorSystem): T = system.registerExtension(this)
  def createExtension(system: ActorSystemImpl): T
}

/**
 * Java API for Extension
 */
abstract class AbstractExtension[T <: AnyRef] extends Extension[T]

/**
 * To be able to load an Extension from the configuration,
 * a class that implements ExtensionProvider must be specified.
 * The lookup method should return the canonical reference to the extension.
 */
trait ExtensionProvider {
  def lookup(): Extension[_ <: AnyRef]
}
