/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization

/**
 * Maker trait that can be added to types so that they'll be serialized using Akka Serialization
 * when embedded in objects serialized with Jackson
 *
 * This belongs in akka-actor so that other modules don't need to depend on akka-serialization-jackson
 * so users that don't use jackson don't get it on their classpath
 */
trait JacksonUseAkkaSerialization
