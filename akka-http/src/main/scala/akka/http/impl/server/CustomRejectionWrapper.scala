/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.server

import akka.http.javadsl.server.CustomRejection
import akka.http.scaladsl.server.Rejection

/**
 * A wrapper that packs a Java custom rejection into a Scala Rejection.
 *
 * INTERNAL API
 */
private[http] case class CustomRejectionWrapper(customRejection: CustomRejection) extends Rejection
