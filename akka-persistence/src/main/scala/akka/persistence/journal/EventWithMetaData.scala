/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.journal

object EventWithMetaData {

  def apply(event: Any, metaData: Any): EventWithMetaData = new EventWithMetaData(event, metaData)

  /**
   * If meta data could not be deserialized it will not fail the replay/query.
   * The "invalid" meta data is represented with this `UnknownMetaData` and
   * it and the event will be wrapped in `EventWithMetaData`.
   *
   * The reason for not failing the replay/query is that meta data should be
   * optional, e.g. the tool that wrote the meta data has been removed. This
   * is typically because the serializer for the meta data has been removed
   * from the class path (or configuration).
   */
  final class UnknownMetaData(val serializerId: Int, val manifest: String)
}

/**
 * If the event is wrapped in this class the `metaData` will
 * be serialized and stored separately from the event payload. This can be used by event
 * adapters or other tools to store additional meta data without altering
 * the actual domain event.
 *
 * Check the documentation of the persistence plugin in use to use if it supports
 * EventWithMetaData.
 */
final class EventWithMetaData(val event: Any, val metaData: Any)
