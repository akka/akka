import akka._

AkkaBuild.defaultSettings
AkkaBuild.experimentalSettings
Formatting.formatSettings
OSGi.http
Dependencies.http

disablePlugins(MimaPlugin) // still experimental
enablePlugins(spray.boilerplate.BoilerplatePlugin)
scalacOptions in Compile += "-language:_"
