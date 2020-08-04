/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

/**
 * Maker trait that can be added to types so that they'll be serialized using Akka Serialization
 * when embedded in objects serialized with Jackson
 */
trait JacksonUseAkkaSerialization
