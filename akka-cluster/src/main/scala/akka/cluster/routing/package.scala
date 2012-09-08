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
   *
   * When creating and deploying routees:
   * [[[
   * import akka.cluster.routing.ClusterRouterProps
   * context.actorOf(Props[SomeActor].withClusterRouter(RoundRobinRouter(),
   *   totalInstances = 10, maxInstancesPerNode = 2), "myrouter")
   * ]]]
   *
   * When looking up routees:
   * [[[
   * import akka.cluster.routing.ClusterRouterProps
   * context.actorOf(Props[SomeActor].withClusterRouter(RoundRobinRouter(),
   *   totalInstances = 10, routeesPath = "/user/myservice"), "myrouter")
   * ]]]
   *
   * Corresponding for Java API is found in [[akka.cluster.routing.ClusterRouterPropsDecorator]].
   */
  implicit class ClusterRouterProps(val props: Props) extends AnyVal {

    def withClusterRouter(router: RouterConfig, totalInstances: Int, maxInstancesPerNode: Int): Props =
      withClusterRouter(router, ClusterRouterSettings(totalInstances, maxInstancesPerNode))

    def withClusterRouter(router: RouterConfig, totalInstances: Int, routeesPath: String): Props =
      withClusterRouter(router, ClusterRouterSettings(totalInstances, routeesPath = routeesPath))

    def withClusterRouter(router: RouterConfig, settings: ClusterRouterSettings): Props = {
      props.withRouter(router).withDeploy(
        Deploy(routerConfig = ClusterRouterConfig(router, settings)))
    }
  }

}
