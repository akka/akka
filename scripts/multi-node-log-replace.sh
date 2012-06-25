#!/usr/bin/env bash
#
# Utility to make log files from multi-node tests easier to analyze.
# Replaces jvm names and host:port with corresponding logical role name.
#
# Use with 0, 1 or 2 arguments.
#
# When using 0 arguments it reads from standard input
# and writes to standard output.
#
# With 1 argument it reads from the file specified in the first argument
# and writes to standard output.
#
# With 2 arguments it reads the file specified in the first argument
# and writes to the file specified in the second argument.
#
# You can also replace the contents of the clipboard instead of using files
# by supplying `clipboard` as argument
#


# check for an sbt command
type -P sbt &> /dev/null || fail "sbt command not found"

sbt "project akka-remote-tests" "test:run-main akka.remote.testkit.LogRoleReplace $1 $2"