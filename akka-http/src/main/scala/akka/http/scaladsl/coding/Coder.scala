/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl.coding

/** Marker trait for A combined Encoder and Decoder */
trait Coder extends Encoder with Decoder
