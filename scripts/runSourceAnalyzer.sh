#!/bin/sh

scaVersion=$1
if [ -z "$scaVersion" ]; then
  scaVersion="22.1"
fi

alias echo='echo $(date +"%F %T")'
logfile="$scanFolder/sourceanalyzer.log"
projectName=$(basename -- "$(pwd)")
scanFolder="fortify-scans/$projectName"
nstFolder="$HOME/.fortify/sca${scaVersion}/build"

if [ ! -d "$nstFolder" ]; then
  echo "$nstFolder" not found
  exit 2
fi

if [ ! -d "$scanFolder" ]; then
  git clone git@github.com:lightbend/fortify-scans.git
  if [ ! -d "$scanFolder" ]; then
    mkdir "$scanFolder"
  fi
  rm "$logfile"
fi

echo Starting analysis of scans
for D in "$nstFolder"/*; do
  if [ -d "${D}" ]; then
    d="$(basename -- "$D")"
    echo Starting "$d" >> "$logfile"
    sourceanalyzer -b "$d" -f "$scanFolder/$d".fpr -scan
    echo Finished "$d" >> "$logfile"
    echo Finished "$d"
  fi
done
echo Scans complete

for F in "$scanFolder"/*.fpr; do
  f="$(basename -- "${F%.*}")"
  echo Generating report for "$f"
  BIRTReportGenerator -template "Developer Workbook" -source "$F" -format PDF -output "$scanFolder/$f.pdf"
done

echo Pushing scan results to repo
cd fortify-scans || exit
git add .
commitMsg="Scan performed on $(date +"%Y-%m-%d_%T")"
git commit -m "$commitMsg"
git push origin
cd ..
echo Push complete

echo Deleting NST and result files to clean up for next run
rm -rf "$nstFolder"
rm -rf fortify-scans
echo Cleanup complete

echo
echo Review scan results in the lightbend/fortify-scans repo under the "$projectName" folder.