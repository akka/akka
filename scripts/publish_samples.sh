#!/usr/bin/env bash
#
# Publishes released sample zip files to Lightbend Activator.
# The zip files must have been uploaded to 
# http://downloads.typesafe.com/akka/ before using this script.
# That is done by the release script.
#

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

# extract uuid property from json and show url of the status page
function logStatusUrl {
  local prop="uuid"
  local temp=`echo $json | sed 's/\\\\\//\//g' | sed 's/[{}]//g' | awk -v k="text" '{n=split($0,a,","); for (i=1; i<=n; i++) print a[i]}' | sed 's/\"\:\"/\|/g' | sed 's/[\,]/ /g' | sed 's/\"//g' | grep -w $prop`
  echolog "Check status of $name at: https://www.lightbend.com/activator/template/status/${temp##*|}"
}

# print usage info
function usage {
  cat <<EOM
Usage: ${script_name} [options] VERSION
  -h | --help               Print this usage message
  -u | --user USER          lightbend.com user name
  -p | --password PASSWORD  lightbend.com user password
EOM
}

# process options and set flags
while true; do
  case "$1" in
    -h | --help ) usage; exit 1 ;;
    -u | --user ) user=$2; shift 2 ;;
    -p | --password ) pwd=$2; shift 2 ;;
    * ) break ;;
  esac
done

if [ $# != "1" ]; then
  usage
  fail "A release version must be specified"
fi

if [ -z "$user" ]; then
  usage
  fail "user must be specified"
fi

if [ -z "$pwd" ]; then
  usage
  fail "password must be specified"
fi

declare -r version=$1

# check for a curl command
type -P curl &> /dev/null || fail "curl command not found"


names=`find akka-samples -name "activator.properties" -depth 2 | awk -F"/" '{printf "%s\n",$2}'`

for name in $names; do
  echolog "Publishing $name"
  json=$(curl --data-urlencode "url=http://downloads.lightbend.com/akka/$name-$version.zip" --user "$user:$pwd" --progress-bar https://www.lightbend.com/activator/template/publish)
  logStatusUrl
done



