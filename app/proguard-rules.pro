# Keep the JavaScript bridge methods called from player.html
-keepclassmembers class com.mutetube.app.PlayerActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
