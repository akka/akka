/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server;

/**
 * Helper class to steer around SI-9013.
 *
 * It's currently impossible to implement a trait containing @varargs methods
 * if the trait is written in Scala. Therefore, derive from this class and
 * implement the method without varargs.
 * FIXME: remove once SI-9013 is fixed.
 *
 * See https://issues.scala-lang.org/browse/SI-9013
 */
public abstract class AbstractDirective implements Directive {
    @Override
    public Route route(Route first, Route... others) {
        return createRoute(first, others);
    }

    protected abstract Route createRoute(Route first, Route[] others);
}
