import akka.{ AkkaBuild, Dependencies, Formatting, OSGi }

AkkaBuild.defaultSettings
AkkaBuild.dontPublishSettings
Formatting.formatSettings
OSGi.osgi
Dependencies.osgi

parallelExecution in Test := false
