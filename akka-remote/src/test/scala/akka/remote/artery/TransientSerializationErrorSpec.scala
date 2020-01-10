/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import akka.remote.AbstractTransientSerializationErrorSpec

class TransientSerializationErrorSpec extends AbstractTransientSerializationErrorSpec(ArterySpecSupport.defaultConfig)
