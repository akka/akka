/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server;


/**
 * A directive is the basic building block for building routes by composing
 * any kind of request or response processing into the main route a request
 * flows through. It is a factory that creates a route when given a sequence of
 * route alternatives to be augmented with the function the directive
 * represents.
 *
 * The `path`-Directive, for example, filters incoming requests by checking if
 * the URI of the incoming request matches the pattern and only invokes its inner
 * routes for those requests.
 */
public interface Directive {
    /**
     * Creates the Route given a sequence of inner route alternatives.
     */
    Route route(Route first, Route... others);
}
