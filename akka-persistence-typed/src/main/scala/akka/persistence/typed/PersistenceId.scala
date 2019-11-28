/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed

object PersistenceId {

  /**
   * Default separator character used for concatenating a `typeHint` with `entityId` to construct unique persistenceId.
   * This must be same as in Lagom's `scaladsl.PersistentEntity`, for compatibility. No separator is used
   * in Lagom's `javadsl.PersistentEntity` so for compatibility with that the `""` separator must be used instead.
   */
  val DefaultSeparator = "|"

  /**
   * Constructs a [[PersistenceId]] from the given `entityTypeHint` and `entityId` by
   * concatenating them with `|` separator.
   *
   * Cluster Sharding is often used together with `EventSourcedBehavior` for the entities.
   * The `PersistenceId` of the `EventSourcedBehavior` can typically be constructed with:
   * {{{
   * PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
   * }}}
   *
   * That format of the `PersistenceId` is not mandatory and only provided as a convenience of
   * a "standardized" format.
   *
   * Another separator can be defined by using the `apply` that takes a `separator` parameter.
   *
   * The `|` separator is also used in Lagom's `scaladsl.PersistentEntity` but no separator is used
   * in Lagom's `javadsl.PersistentEntity`. For compatibility with Lagom's `javadsl.PersistentEntity`
   * you should use `""` as the separator.
   *
   * @throws IllegalArgumentException if the `entityTypeHint` or `entityId` contains `|`
   */
  def apply(entityTypeHint: String, entityId: String): PersistenceId =
    apply(entityTypeHint, entityId, DefaultSeparator)

  /**
   * Constructs a [[PersistenceId]] from the given `entityTypeHint` and `entityId` by
   * concatenating them with the `separator`.
   *
   * Cluster Sharding is often used together with `EventSourcedBehavior` for the entities.
   * The `PersistenceId` of the `EventSourcedBehavior` can typically be constructed with:
   * {{{
   * PersistenceId(entityContext.entityTypeKey.name, entityContext.entityId)
   * }}}
   *
   * That format of the `PersistenceId` is not mandatory and only provided as a convenience of
   * a "standardized" format.
   *
   * The default separator `|` is used by the `apply` that doesn't take a `separator` parameter.
   *
   * The `|` separator is also used in Lagom's `scaladsl.PersistentEntity` but no separator is used
   * in Lagom's `javadsl.PersistentEntity`. For compatibility with Lagom's `javadsl.PersistentEntity`
   * you should use `""` as the separator.
   *
   * @throws IllegalArgumentException if the `entityTypeHint` or `entityId` contains `separator`
   */
  def apply(entityTypeHint: String, entityId: String, separator: String): PersistenceId = {
    if (separator.nonEmpty) {
      if (entityId.contains(separator))
        throw new IllegalArgumentException(s"entityId [$entityId] contains [$separator] which is a reserved character")

      if (entityTypeHint.contains(separator))
        throw new IllegalArgumentException(
          s"entityTypeHint [$entityTypeHint] contains [$separator] which is a reserved character")
    }

    new PersistenceId(entityTypeHint + separator + entityId)
  }

  /**
   * Constructs a [[PersistenceId]] from the given `entityTypeHint` and `entityId` by
   * concatenating them with `|` separator.
   *
   * Cluster Sharding is often used together with `EventSourcedBehavior` for the entities.
   * The `PersistenceId` of the `EventSourcedBehavior` can typically be constructed with:
   * {{{
   * PersistenceId.of(entityContext.getEntityTypeKey().name(), entityContext.getEntityId())
   * }}}
   *
   * That format of the `PersistenceId` is not mandatory and only provided as a convenience of
   * a "standardized" format.
   *
   * Another separator can be defined by using the `PersistenceId.of` that takes a `separator` parameter.
   *
   * The `|` separator is also used in Lagom's `scaladsl.PersistentEntity` but no separator is used
   * in Lagom's `javadsl.PersistentEntity`. For compatibility with Lagom's `javadsl.PersistentEntity`
   * you should use `""` as the separator.
   *
   * @throws IllegalArgumentException if the `entityTypeHint` or `entityId` contains `|`
   */
  def of(entityTypeHint: String, entityId: String): PersistenceId =
    apply(entityTypeHint, entityId)

  /**
   * Constructs a [[PersistenceId]] from the given `entityTypeHint` and `entityId` by
   * concatenating them with the `separator`.
   *
   * Cluster Sharding is often used together with `EventSourcedBehavior` for the entities.
   * The `PersistenceId` of the `EventSourcedBehavior` can typically be constructed with:
   * {{{
   * PersistenceId.of(entityContext.getEntityTypeKey().name(), entityContext.getEntityId())
   * }}}
   *
   * That format of the `PersistenceId` is not mandatory and only provided as a convenience of
   * a "standardized" format.
   *
   * The default separator `|` is used by the `apply` that doesn't take a `separator` parameter.
   *
   * The `|` separator is also used in Lagom's `scaladsl.PersistentEntity` but no separator is used
   * in Lagom's `javadsl.PersistentEntity`. For compatibility with Lagom's `javadsl.PersistentEntity`
   * you should use `""` as the separator.
   *
   * @throws IllegalArgumentException if the `entityTypeHint` or `entityId` contains `separator`
   */
  def of(entityTypeHint: String, entityId: String, separator: String): PersistenceId =
    apply(entityTypeHint, entityId, separator)

  /**
   * Constructs a [[PersistenceId]] with `id` as the full unique identifier.
   */
  def ofUniqueId(id: String): PersistenceId =
    new PersistenceId(id)

}

/**
 * Unique identifier in the backend data store (journal and snapshot store) of the
 * persistent actor.
 */
final class PersistenceId private (val id: String) {
  if (id eq null)
    throw new IllegalArgumentException("persistenceId must not be null")

  if (id.trim.isEmpty)
    throw new IllegalArgumentException("persistenceId must not be empty")

  override def toString: String = s"PersistenceId($id)"

  override def hashCode(): Int = id.hashCode

  override def equals(obj: Any): Boolean = obj match {
    case other: PersistenceId => id == other.id
    case _                    => false
  }
}
