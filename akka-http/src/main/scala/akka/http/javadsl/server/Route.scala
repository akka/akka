/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server

/**
 * A marker interface to denote an element that handles a request.
 *
 * This is an opaque interface that cannot be implemented manually.
 * Instead, see the predefined routes in [[Directives]] and use the [[Directives#handleWith]]
 * method to create custom routes.
 */
trait Route