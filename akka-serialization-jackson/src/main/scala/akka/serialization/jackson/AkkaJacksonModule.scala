/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

/**
 * Complete module with support for all custom serializers.
 */
class AkkaJacksonModule extends JacksonModule with ActorRefModule with AddressModule with FiniteDurationModule {
  override def getModuleName = "AkkaJacksonModule"
}

object AkkaJacksonModule extends AkkaJacksonModule

class AkkaTypedJacksonModule extends JacksonModule with TypedActorRefModule {
  override def getModuleName = "AkkaTypedJacksonModule"
}

object AkkaTypedJacksonModule extends AkkaJacksonModule
