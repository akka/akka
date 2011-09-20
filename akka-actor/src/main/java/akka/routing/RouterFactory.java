/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.routing;

/**
 * A Factory responsible for creating {@link Router} instances. It makes Java compatability possible for users that
 * want to provide their own router instance.
 */
public interface RouterFactory {

    /**
     * Creates a new Router instance.
     *
     * @return the newly created Router instance.
     */
    Router newRouter();
}
