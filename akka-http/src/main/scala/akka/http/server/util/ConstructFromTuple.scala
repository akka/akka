/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.server.util

/**
 * Constructor for instances of type ``R`` which can be created from a tuple of type ``T``.
 */
trait ConstructFromTuple[T, R] extends (T ⇒ R)

object ConstructFromTuple extends ConstructFromTupleInstances
