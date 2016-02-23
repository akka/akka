/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.util

/**
 * INTERNAL API
 *
 * A unsynchronized registry to keep track of singleton instances similar to what
 * java.lang.Enum provides. `registry` should therefore only be used inside of singleton constructors.
 */
private[http] trait ObjectRegistry[K, V <: AnyRef] {
  private[this] var _registry = Map.empty[K, V]

  protected final def register(key: K, obj: V): obj.type = {
    require(!_registry.contains(key), s"ObjectRegistry for ${getClass.getSimpleName} already contains value for $key")
    _registry = _registry.updated(key, obj)
    obj
  }
  def getForKey(key: K): Option[V] = _registry.get(key)

  def getForKeyCaseInsensitive(key: String)(implicit conv: String <:< K): Option[V] =
    getForKey(conv(key.toRootLowerCase))
}
