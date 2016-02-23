/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
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
