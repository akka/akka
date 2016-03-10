import akka._

AkkaBuild.defaultSettings
AkkaBuild.experimentalSettings
Formatting.formatSettings
OSGi.http
Dependencies.http

enablePlugins(spray.boilerplate.BoilerplatePlugin)
disablePlugins(MimaPlugin)
scalacOptions in Compile += "-language:_"
