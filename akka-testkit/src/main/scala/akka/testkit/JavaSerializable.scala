/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit

import java.io.Serializable

/**
 * Marker trait for test messages that will use JavaSerializer.
 */
trait JavaSerializable extends Serializable
