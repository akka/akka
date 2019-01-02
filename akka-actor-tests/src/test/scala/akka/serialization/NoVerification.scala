/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package test.akka.serialization

import akka.actor.NoSerializationVerificationNeeded

/**
 *  This is currently used in NoSerializationVerificationNeeded test cases in SerializeSpec,
 *  as they needed a serializable class whose top package is not akka.
 */
class NoVerification extends NoSerializationVerificationNeeded with java.io.Serializable {
}
