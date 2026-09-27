# Signing in with cookies

**Not recommended for use.** Cookies give full access to the Google account and YouTube may flag accounts used this way.

YouTube increasingly shows a "confirm you're not a bot" wall to signed-out requests, and
likes, your home feed and personalised Shorts need an account. yt-dlp only supports signing
in with cookies, and Wear OS has no file picker, so for now the cookies go on with adb:

1. In a private browser window, sign in to youtube.com, export its cookies as `cookies.txt`
   (Netscape format, tab-separated, for example with the "Get cookies.txt LOCALLY" add-on),
   then close the window so the browser doesn't rotate them.
2. Copy the file into the app's private storage:

   ```
   adb push cookies.txt /data/local/tmp/cookies.txt
   adb shell run-as ca.wolfietech.dev.android.ytwear cp /data/local/tmp/cookies.txt files/cookies.txt
   adb shell rm /data/local/tmp/cookies.txt
   ```

   `run-as` only works on debug builds; for a release build, build and install a debug APK
   (see Building in the README) instead.

Cookies give full access to the Google account, so a spare account is safer.
