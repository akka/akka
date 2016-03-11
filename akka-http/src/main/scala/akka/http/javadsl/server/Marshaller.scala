/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

/**
 * A marker trait for a marshaller that converts a value of type `T` to an
 * HttpResponse.
 */
trait Marshaller[T]