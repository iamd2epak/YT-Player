# Keep the JavaScript bridge methods called from player.html
-keepclassmembers class com.iamd2epak.ytplayer.PlayerActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
