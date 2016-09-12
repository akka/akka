import akka._

//OSGi.http

Dependencies.http
scalacOptions in Compile += "-language:_"

//disablePlugins(MimaPlugin) // still experimental
enablePlugins(spray.boilerplate.BoilerplatePlugin)
