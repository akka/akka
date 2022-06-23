#!/bin/sh
alias echo='echo $(date +"%F %T")'
now=$(date +"%s")
projectName=$(basename -- "$(pwd)")
scanFolder="fortify-scans/$projectName"
logfile="$scanFolder/sourceanalyzer.log"

if [ ! -d fortify-scans ]; then
  git clone git@github.com:lightbend/fortify-scans.git
fi

echo Starting analysis of scans
for D in ~/.fortify/sca21.1/build/*; do
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
commitMsg="Scan performed on $(date +"%Y-%m-%d_%T")"
git commit -m "$commitMsg"
git push origin
echo Push complete

echo Deleting NST and result files to clean up for next run
rm -rf ~/.fortify/sca21.1/build
rm -rf fortify-scans
echo Cleanup complete

echo
echo Review scan results in the lightbend/fortify-scans repo under the "$projectName" folder.