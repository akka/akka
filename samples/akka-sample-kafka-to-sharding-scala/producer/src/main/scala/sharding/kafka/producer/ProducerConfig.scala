/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */
package sharding.kafka.producer

import com.typesafe.config.Config

case object ProducerConfig {
  def apply(config: Config): ProducerConfig =
    new ProducerConfig(
      config.getString("bootstrap-servers"),
      config.getString("topic"))
}

final class ProducerConfig(
    val bootstrapServers: String,
    val topic: String)
