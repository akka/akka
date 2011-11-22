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
 * Scala example:
 *
 * {{{
 * class MyExtension extends Extension[MyExtension] {
 *   def key = MyExtension
 *   def init(system: ActorSystemImpl) {
 *     ... // initialize here
 *   }
 * }
 * object MyExtension extends ExtensionKey[MyExtension]
 * }}}
 *
 * Java example:
 *
 * {{{
 * static class MyExtension implements Extension<MyExtension> {
 *   public static ExtensionKey<MyExtension> key = new ExtensionKey<MyExtension>() {};
 *
 *   public ExtensionKey<TestExtension> key() {
 *    return key;
 *   }
 *   public void init(ActorSystemImpl system) {
 *     ... // initialize here
 *   }
 * }
 * }}}
 */
trait Extension[T <: AnyRef] {

  /**
   * This method is called by the ActorSystem upon registering this extension.
   * The key returned is used for looking up extensions, hence it must be a
   * suitable hash key and available to all clients of the extension. This is
   * best achieved by storing it in a static field (Java) or as/in an object
   * (Scala).
   */
  def key: ExtensionKey[T]

  /**
   * This method is called by the ActorSystem when the extension is registered
   * to trigger initialization of the extension.
   */
  def init(system: ActorSystemImpl)
}

/**
 * Marker trait identifying a registered [[akka.actor.Extension]].
 */
trait ExtensionKey[T <: AnyRef]
