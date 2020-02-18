/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

import com.fasterxml.jackson.databind.ObjectMapper

class ScalaObjectMapper(mapper: ObjectMapper)
    extends ObjectMapper(mapper: ObjectMapper)
    with com.fasterxml.jackson.module.scala.ScalaObjectMapper
