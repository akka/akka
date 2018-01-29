/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

/** Marker trait for A combined Encoder and Decoder */
trait Coder extends Encoder with Decoder
