import akka.{ AkkaBuild, Dependencies, Formatting, OSGi }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.osgi
Dependencies.osgi

parallelExecution in Test := false
