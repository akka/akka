#!/bin/bash

# Find and replace across all source files.
#
# Example usage:
# 
# sh project/scripts/find-replace.sh 1.1-SNAPSHOT 1.1-RC1
#
# This script will be called as part of the sbt release script.

FIND=$1
REPLACE=$2

if [ -z "$FIND" ]; then
    echo "Usage: find-replace.sh FIND REPLACE"
    exit 1
fi

echo
echo "Find and replace: $FIND --> $REPLACE"


# Exclude directories from search

excludedirs=".git dist deploy embedded-repo lib_managed project/boot project/scripts src_managed target"

echo "Excluding directories: $excludedirs"

excludeopts="\("
op="-path"
for dir in $excludedirs; do
  excludeopts="${excludeopts} ${op} '*/${dir}/*'"
  op="-or -path"
done
excludeopts="${excludeopts} \) -prune -o"


# Replace in files

search="find . ${excludeopts} -type f -print0 | xargs -0 grep -Il \"${FIND}\""

echo $search
echo

files=$(eval "$search")

simplediff="diff --old-line-format='- %l
' --new-line-format='+ %l
' --changed-group-format='%<%>' --unchanged-group-format=''"

for file in $files; do
    echo
    echo $file
    # escape / for sed
    sedfind=$(echo $FIND | sed 's/\//\\\//g')
    sedreplace=$(echo $REPLACE | sed 's/\//\\\//g')
    sed -i '.sed' "s/${sedfind}/${sedreplace}/g" $file
    eval "$simplediff $file.sed $file"
    rm -f $file.sed
done

echo


# Replace in file names

search="find . ${excludeopts} -type f -name \"*${FIND}*\" -print"

echo $search
echo

files=$(eval "$search")

for file in $files; do
    dir=$(dirname $file)
    name=$(basename $file)
    newname=$(echo $name | sed "s/${FIND}/${REPLACE}/g")
    echo "$file --> $newname"
    mv $file $dir/$newname
done

echo
