/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.coding

/** Marker trait for A combined Encoder and Decoder */
trait Coder extends Encoder with Decoder
