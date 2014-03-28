/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

/**
 * A registry to keep track of singleton instances similar to what
 * java.lang.Enum provides.
 */
private[http] trait ObjectRegistry[K, V <: AnyRef] {
  @volatile private[this] var _registry = Map.empty[K, V]

  protected final def register(key: K, obj: V): obj.type = synchronized {
    require(!_registry.contains(key), s"ObjectRegistry for ${getClass.getSimpleName} already contains value for $key")
    _registry = _registry.updated(key, obj)
    obj
  }

  protected def registry: Map[K, V] = _registry

  def getForKey(key: K): Option[V] = registry.get(key)
}
