jQuery(document).ready(function ($) {

  function initOldVersionWarnings($) {
    $.get("//akka.io/versions.json", function (akkaVersionsData) {
      var site = extractCurrentPageInfo();
      if (site.v === 'snapshot') {
        showSnapshotWarning(site)
      } else {
        var matchingMinor =
          Object.keys(akkaVersionsData[site.p])
            .find(function(s) { return site.v.startsWith(s) })
        if (matchingMinor) {
          showVersionWarning(site, akkaVersionsData, matchingMinor)
        }
      }
    })
  }

  function getInstead(akkaVersionsData, project, instead) {
    if (Array.isArray(instead)) {
      var found = akkaVersionsData[instead[0]][instead[1]]
      var proj = instead[0]
    } else {
      var found = akkaVersionsData[project][instead]
      var proj = project
    }
    return {"latest": found.latest, "project": proj}
  }

  function targetUrl(samePage, site, instead) {
    var page = site.r
    if (samePage !== true) {
      // FIXME not valid anymore
      if (page.substring(0, 5) == 'scala') {
        page = 'scala.html'
      } else if (page.substring(0, 4) == 'java') {
        page = 'java.html'
      } else {
        page = 'index.html'
      }
    }
    var project = instead.project
    if (!project) {
      project = site.p
    }
    return site.b + project + '/' + instead.latest + '/' + page
  }

  function showWarning(site, visitedVersion, text) {
    // sneaking the style in here to make life simple
    $('head').append('<style>' +
      '.page-content .oldVersion.callout .ack-button {' +
      '  display: inline-block;' +
      '  background-color: rgb(255, 83, 75);' +
      'color: #fff;' +
      ' text-decoration: none;' +
      ' padding: 10px;' +
    '})')
    var $warningPopup = $('<div class="callout warning oldVersion">').append(text)
    var $close = $('<a href="#" class="ack-button">✓ Dismiss Warning for a Day</a>')
      .click(function () {
        ackVersionForADay(site.p, visitedVersion)
        $warningPopup.animate({ height: 0 }, 400, "swing", function() {
          $warningPopup.hide()
        })
      })

    $warningPopup
      .hide()
      .append($close)
      .prependTo("#docs")
      .show()
  }

  function showVersionWarning(site, akkaVersionsData, series) {
    var version = site.v,
      seriesInfo = akkaVersionsData[site.p][series]


    if (versionWasAcked(site.p, version)) {
      // hidden for a day
    } else if (seriesInfo.outdated) {
      var instead = getInstead(akkaVersionsData, site.p, seriesInfo.instead)
      var insteadSeries = targetUrl(false, site, instead)
      var insteadPage = targetUrl(true, site, instead)

      showWarning(
        site,
        version,
        '<h3 class="callout-title">Old Version</h3>' +
        '<p><span style="font-weight: bold">This version of Akka (' + site.p + ' / ' + version + ') is outdated and not supported! </span></p>' +
        '<p>Please upgrade to version <a href="' + insteadSeries + '">' + instead.latest + '</a> as soon as possible.</p>' +
        '<p id="samePageLink"></p>')

      $.ajax({
        url: insteadPage,
        type: 'HEAD',
        success: function () {
          $('#samePageLink').html('<a href="' + insteadPage + '">Click here to go to the same page on the ' + instead.latest + ' version of the docs.</a>');
        }
      });
    } else if (version == series) {
      // The series already points to the latest version in that series
    } else if (version != seriesInfo.latest) {
      showWarning(
        site,
        version,
        '<h3 class="callout-title">Outdated version</h3>' +
        '<p>You are browsing the docs for Akka ' + version + ', however the latest release in this series is: ' +
          '<a href="' + targetUrl(true, site, seriesInfo) + '">' + seriesInfo.latest + '</a>. <br/></p>');
    }
  }

  function showSnapshotWarning(site) {
    if (!versionWasAcked(site.p, 'snapshot')) {
      var instead = {'latest': 'current'};
      var insteadSeries = targetUrl(false, site, instead);
      var insteadPage = targetUrl(true, site, instead);

      showWarning(
        site,
        'snapshot',
        '<h3 class="callout-title">Snapshot docs</h3>' +
        '<p><span style="font-weight: bold">You are browsing the snapshot documentation, which most likely does not correspond to the artifacts you are using! </span></p>' +
        '<p>We recommend that you head over to <a href="' + insteadSeries + '">the latest stable version</a> instead.</p>' +
        '<p id="samePageLink"></p>')
      $.ajax({
        url: insteadPage,
        type: 'HEAD',
        success: function () {
          $('#samePageLink').html('<a href="' + insteadPage + '">Click here to go to the same page on the latest stable version of the docs.</a>');
        }
      })
    }
  }


  function extractCurrentPageInfo() {
    var path = window.location.pathname,
      base = '' + window.location

    // strip off leading /docs/
    path = path.substring(path.indexOf("akka"))
    base = base.substring(0, base.indexOf(path))
    var projectEnd = path.indexOf("/")
    var versionEnd = path.indexOf("/", projectEnd + 1)
    var project = path.substring(0, projectEnd)
    var version = path.substring(projectEnd + 1, versionEnd)
    var rest = path.substring(versionEnd + 1)
    return {"b": base, "p": project, "v": version, "r": rest}
  }


// --- ack outdated versions ---

  function ackVersionCookieName(project, version) {
    return "ack-" + project + "-" + version
  }

  function ackVersionForADay(project, version) {
    function setCookie(cname, cvalue, exdays) {
      var d = new Date()
      d.setTime(d.getTime() + (exdays * 24 * 60 * 60 * 1000))
      var expires = "expires=" + d.toUTCString()
      document.cookie = cname + "=" + cvalue + "; " + expires
    }

    setCookie(ackVersionCookieName(project, version), 'true', 1)
  }

  function versionWasAcked(project, version) {
    function getCookie(cname) {
      var name = cname + "="
      var ca = document.cookie.split(';')
      for (var i = 0; i < ca.length; i++) {
        var c = ca[i];
        while (c.charAt(0) == ' ') c = c.substring(1)
        if (c.indexOf(name) == 0) return c.substring(name.length, c.length)
      }
      return ""
    }

    return getCookie(ackVersionCookieName(project, version)) === 'true';
  }


  initOldVersionWarnings($)
})
