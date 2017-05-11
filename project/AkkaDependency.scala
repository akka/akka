/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import Keys._

object AkkaDependency {
  // Needs to be a URI like git://github.com/akka/akka.git#master or file:///xyz/akka
  val akkaSourceDependencyUri = sys.props.getOrElse("akka.sources", "")
  val shouldUseSourceDependency = akkaSourceDependencyUri != ""
  val akkaRepository = {
    // as a little hacky side effect also disable aggregation of samples
    sys.props += "akka.build.aggregateSamples" -> "false"

    uri(akkaSourceDependencyUri)
  }

  implicit class RichProject(project: Project) {
    /** Adds either a source or a binary dependency, depending on whether the above settings are set */
    def addAkkaModuleDependency(module: String, config: String = ""): Project =
      if (shouldUseSourceDependency) {
        val moduleRef = ProjectRef(akkaRepository, module)
        val withConfig: ClasspathDependency =
          if (config == "") moduleRef
          else moduleRef % config

        project.dependsOn(withConfig)
      } else {
        project.settings(libraryDependencies += {
          val dep = "com.typesafe.akka" %% module % Dependencies.akkaVersion.value
          val withConfig =
            if (config == "") dep
            else dep % config
          withConfig
        })
      }
  }
}
