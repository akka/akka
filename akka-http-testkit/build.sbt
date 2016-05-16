import akka._

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.httpTestkit
Dependencies.httpTestkit

scalacOptions in Compile  += "-language:postfixOps"

disablePlugins(MimaPlugin) // testkit, no bin compat guaranteed
