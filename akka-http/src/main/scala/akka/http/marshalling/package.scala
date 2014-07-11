/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http

package object marshalling {

}

package marshalling {
  trait Marshaller[-T]
  trait ToResponseMarshaller[-T]
  object ToResponseMarshaller
}
