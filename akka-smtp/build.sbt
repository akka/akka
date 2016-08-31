import akka._

AkkaBuild.defaultSettings
AkkaBuild.experimentalSettings
Formatting.formatSettings
OSGi.smtp
Dependencies.smtp

disablePlugins(MimaPlugin) // still experimental
enablePlugins(spray.boilerplate.BoilerplatePlugin)
scalacOptions in Compile += "-language:_"
