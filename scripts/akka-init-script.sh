#! /bin/bash

#Original /etc/init.d/skeleton modified for http://mydebian.blogdns.org 

# PATH should only include /usr/* if it runs after the mountnfs.sh
script
PATH=/sbin:/usr/sbin:/bin:/usr/bin
DESC="my cool akka app"
NAME="cool"
DAEMON=/usr/bin/java
export AKKA_HOME=/var/.../servers/akka
AKKA_JAR=$AKKA_HOME/akka.jar
LOGBACK=$AKKA_HOME/config/logback.xml
JVMFLAGS="-Xms512M -Xmx3072M -XX:+UseConcMarkSweepGC -
Dlogback.configurationFile="$LOGBACK
DAEMON_ARGS=$JVMFLAGS" -jar "$AKKA_JAR
PIDFILE=/var/run/$NAME.pid
SCRIPTNAME=/etc/init.d/$NAME
#the user that will run the script
USER=cool-user
VERBOSE=1

# NO NEED TO MODIFY THE LINES BELOW

# Load the VERBOSE setting and other rcS variables
. /lib/init/vars.sh

# Define LSB log_* functions.
# Depend on lsb-base (>= 3.0-6) to ensure that this file is present.
. /lib/lsb/init-functions

#
# Function that starts the daemon/service
#
do_start()
{
 start-stop-daemon --start --quiet -b -m -p $PIDFILE --exec $DAEMON --
$DAEMON_ARGS \
 || return 2
}

#
# Function that stops the daemon/service
#
do_stop()
{
 start-stop-daemon --stop --quiet --oknodo --pidfile $PIDFILE
 RETVAL="$?"
 rm -f $PIDFILE
 return "$RETVAL"
}

case "$1" in
 start)
 [ "$VERBOSE" != no ] && log_daemon_msg "Starting $DESC" "$NAME"
 do_start
 case "$?" in
 0|1) [ "$VERBOSE" != no ] && log_end_msg 0 ;;
 2) [ "$VERBOSE" != no ] && log_end_msg 1 ;;
 esac
 ;;
 stop)
 [ "$VERBOSE" != no ] && log_daemon_msg "Stopping $DESC" "$NAME"
 do_stop
 case "$?" in
 0|1) [ "$VERBOSE" != no ] && log_end_msg 0 ;;
 2) [ "$VERBOSE" != no ] && log_end_msg 1 ;;
 esac
 ;;
 restart)
 #
 # If the "reload" option is implemented then remove the
 # 'force-reload' alias
 #
 log_daemon_msg "Restarting $DESC" "$NAME"
 do_stop
 case "$?" in
  0|1)
 do_start
 case "$?" in
  0) log_end_msg 0 ;;
  1) log_end_msg 1 ;; # Old process is still running
  *) log_end_msg 1 ;; # Failed to start
 esac
 ;;
  *)
   # Failed to stop
 log_end_msg 1
 ;;
 esac
 ;;
 *)
 echo "Usage: $SCRIPTNAME {start|stop|restart}" >&2
 exit 3
 ;;
esac