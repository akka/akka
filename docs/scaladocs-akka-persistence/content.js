var cfg_showInherited=true;
var toggleInherited=function(){cfg_showInherited=!cfg_showInherited;
$.cookie("showInherited",cfg_showInherited);
updateInherited()
};
var updateInherited=function(){$("input.filter_inherited_cb").each(function(){this.checked=cfg_showInherited
});
if(cfg_showInherited){$("tr.isInherited").show()
}else{$("tr.isInherited").hide()
}};
$(document).ready(function(){parent.document.title=document.title;
cfg_showInherited=$.cookie("showInherited");
cfg_showInherited=(cfg_showInherited==true||cfg_showInherited=="true");
updateInherited();
$("div.apiCommentsDetails").hide()
});
var selectPackage=function(B){if(parent.navFrame){parent.navFrame.selectPackage(B)
}};
jQuery.cookie=function(O,T,Q){if(typeof T!="undefined"){Q=Q||{};
if(T===null){T="";
Q.expires=-1
}var X="";
if(Q.expires&&(typeof Q.expires=="number"||Q.expires.toUTCString)){var W;
if(typeof Q.expires=="number"){W=new Date();
W.setTime(W.getTime()+(Q.expires*24*60*60*1000))
}else{W=Q.expires
}X="; expires="+W.toUTCString()
}var R=Q.path?"; path="+(Q.path):"";
var V=Q.domain?"; domain="+(Q.domain):"";
var P=Q.secure?"; secure":"";
document.cookie=[O,"=",encodeURIComponent(T),X,R,V,P].join("")
}else{var M=null;
if(document.cookie&&document.cookie!=""){var S=document.cookie.split(";");
for(var U=0;
U<S.length;
U++){var N=jQuery.trim(S[U]);
if(N.substring(0,O.length+1)==(O+"=")){M=decodeURIComponent(N.substring(O.length+1));
break
}}}return M
}};
