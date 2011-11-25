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

/**
 * Market interface to signify an Akka Extension
 */
trait Extension

/**
 * Identifies an Extension
 * Lookup of Extensions is done by object identity, so the Id must be the same wherever it's used,
 * otherwise you'll get the same extension loaded multiple times.
 */
trait ExtensionId[T <: Extension] {
  def apply(system: ActorSystem): T = system.registerExtension(this)
  def createExtension(system: ActorSystemImpl): T
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
