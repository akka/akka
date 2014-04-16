#!/usr/bin/env bash

# defaults
declare -r default_java_home="/usr/local/share/java/jdk6"
declare -r default_java8_home="/usr/local/share/java/jdk8"

# get the source location for this script; handles symlinks
function get_script_path {
  local source="${BASH_SOURCE[0]}"
  while [ -h "${source}" ] ; do
    source="$(readlink "${source}")";
  done
  echo ${source}
}

# path, name, and dir for this script
declare -r script_path=$(get_script_path)
declare -r script_name=$(basename "${script_path}")
declare -r script_dir="$(cd -P "$(dirname "${script_path}")" && pwd)"

# print usage info
function usage {
  cat <<EOM
Usage: ${script_name} [options] VERSION
  -h | --help        Print this usage message
  --java_home PATH   Set the path to the "standard" java version
  --java8_home PATH  Set the path to the java 8 version

This script assumes that the mvn command is in your path.
EOM
}

# echo a log message
function echolog {
  echo "[${script_name}] $@"
}

# echo an error message
function echoerr {
  echo "[${script_name}] $@" 1>&2
}

# fail the script with an error message
function fail {
  echoerr "$@"
  exit 1
}

# try to run a command or otherwise fail with a message
function try {
  "${@:1:$#-1}" || fail "${@:$#}"
}

# try to run a command or otherwise fail
function check {
  type -P "$@" &> /dev/null || fail "command not found: $@"
}

# run mvn clean test using the specified java home in the specified directory
function mvncleantest {
  tmp="$script_dir/../../$2"
  try cd  "$tmp" "can't step into project directory: $tmp"
  export JAVA_HOME="$1"
  try mvn clean test "mvn execution in $2 failed"
}

# initialize variables with defaults and override from environment
declare java_home="$default_java_home"
if [ $AKKA_BUILD_JAVA_HOME ]; then
  java_home="$AKKA_BUILD_JAVA_HOME"
fi

declare java8_home="$default_java8_home"
if [ $AKKA_BUILD_JAVA8_HOME ]; then
  java8_home="$AKKA_BUILD_JAVA8_HOME"
fi

# process options and set flags
while true; do
  case "$1" in
    -h | --help ) usage; exit 1 ;;
    --java_home ) java_home=$2; shift 2 ;;
    --java8_home ) java8_home=$2; shift 2 ;;
    * ) break ;;
  esac
done

declare -r java_path="$java_home/bin/java"
declare -r java8_path="$java8_home/bin/java"

# check that java paths work
check "$java_path"
check "$java8_path"

# check for a mvn command
check mvn

# now do some work
mvncleantest "$java8_home" "akka-samples/akka-docs-java-lambda"

mvncleantest "$java8_home" "akka-samples/akka-sample-fsm-java-lambda"

mvncleantest "$java8_home" "akka-samples/akka-sample-persistence-java-lambda"

mvncleantest "$java8_home" "akka-samples/akka-sample-supervision-java-lambda"

sample_dir=akka-samples/akka-sample-main-java-lambda
tmp="$script_dir/../../$sample_dir"
try cd  "$tmp" "can't step into project directory: $tmp"
export JAVA_HOME="$java8_home"
try mvn clean compile exec:java -Dexec.mainClass="akka.Main" -Dexec.args="sample.hello.HelloWorld" "mvn execution in $sample_dir failed"
try mvn exec:java -Dexec.mainClass="sample.hello.Main2" "mvn execution in $sample_dir failed"
