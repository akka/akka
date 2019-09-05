/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import java.io.Serializable

/**
 * Marker trait for test messages that will use Java serialization via
 * [[akka.testkit.TestJavaSerializer]]
 */
trait JavaSerializable extends Serializable
