/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.server

/**
 * A marker trait for a marshaller that converts a value of type [[T]] to an
 * HttpResponse.
 */
trait Marshaller[T]