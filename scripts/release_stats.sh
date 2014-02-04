#!/usr/bin/env bash
#
# Generates statistics for release notes
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

# print usage info
function usage {
  echo "Usage: ${script_name} v2.1.0 v2.2.0 path_to_assembla_export.csv"
}

declare -r tag1=$1
declare -r tag2=$2
declare -r tickets_csv=$3

if [ -z "$tickets_csv" ]; then
  usage
  exit 1
fi

declare -r tag_range="$tag1..$tag2"
declare authors=$($script_dir/authors.pl $tag_range)
declare author_count=$(echo "$authors" | wc -l | grep -o '[1-9].*')
declare diff_short=$(git diff --shortstat $tag_range | grep -o '[1-9].*')
declare tickets=$(tail -n +2 $tickets_csv | grep Fixed | cut -d ',' -f 1,2 | sed 's/","/  /g' | tr -d '"' | sort -n)
declare ticket_count=$(echo "$tickets" | wc -l | grep -o '[1-9].*')

echo "$tag1 compared to Akka $tag2":

echo "* $ticket_count tickets closed"

echo "* $diff_short"

echo "* X pages of docs vs Y in $tag1"

echo "* … and a total of $author_count committers!"

echo ""
echo "Fixed Tickets:"
echo "$tickets"

echo ""
echo "Credits:"
echo "commits added removed"
echo "$authors"


