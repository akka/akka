/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.cluster

import language.implicitConversions
import akka.actor.Deploy
import akka.actor.Props
import akka.routing.RouterConfig

package object routing {

  /**
   * Sugar to define cluster aware router programatically.
   * Usage Scala API:
   * [[[
   * import akka.cluster.routing.ClusterRouterProps
   * context.actorOf(Props[SomeActor].withClusterRouter(RoundRobinRouter(),
   *   totalInstances = 10, maxInstancesPerNode = 2), "myrouter")
   * ]]]
   *
   * Corresponding for Java API is found in [[akka.cluster.routing.ClusterRouterPropsDecorator]].
   */
  implicit class ClusterRouterProps(val props: Props) extends AnyVal {

    /*
     * Without this helper it would look as ugly as:
     * val router = RoundRobinRouter(nrOfInstances = 10)
     * val actor = system.actorOf(Props[SomeActor].withRouter(router).withDeploy(
     *  Deploy(routerConfig = ClusterRouterConfig(router, totalInstances = router.nrOfInstances, maxInstancesPerNode = 2))),
     *  "myrouter")
     */

    def withClusterRouter(router: RouterConfig, totalInstances: Int, maxInstancesPerNode: Int): Props = {
      props.withRouter(router).withDeploy(
        Deploy(routerConfig = ClusterRouterConfig(router, totalInstances, maxInstancesPerNode)))
    }
  }

}
