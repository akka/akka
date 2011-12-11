/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

package object remote {
  type TransportsMap = Map[String, (String, Int) ⇒ Either[String, RemoteTransportAddress]]
}