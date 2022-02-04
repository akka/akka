/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import akka.remote.serialization.AbstractSerializationTransportInformationSpec

class SerializationTransportInformationSpec
    extends AbstractSerializationTransportInformationSpec(ArterySpecSupport.defaultConfig)
