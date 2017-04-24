import akka.{ AkkaBuild, Formatting, OSGi, Dependencies, Version }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.actor
Dependencies.actor
Version.versionSettings
unmanagedSourceDirectories in Compile += {
  val ver = scalaVersion.value.take(4)
  (scalaSource in Compile).value.getParentFile / s"scala-$ver"
}

enablePlugins(spray.boilerplate.BoilerplatePlugin)
