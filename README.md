
<h1 align="center">
  <img alt="Icon" width="75" src="https://github.com/thepbone/RootlessJamesDSP/blob/master/img/icons/web/icon-192.png?raw=true">
  <br>
  RootlessJamesDSP - SKDSP
  <br>
</h1>
<h4 align="center">System-wide JamesDSP implementation for non-rooted Android devices - with new speed shifting, pitchshifter, compressor, effects reordering, floating recorders, bpm detection, and keyboard input for pitchshifters.</h4>
<h4 align="left">also by me: <a href="https://play.google.com/store/apps/details?id=net.skeyboard.filemgr>explorer 95 windowing file manager [android] w/ dj mixing / raster image pixel editor /  video editor / text editor </a></h4>

<h5><a href="https://play.google.com/store/apps/details?id=net.skeyboard.midigitaru>guitarchord / midichord - midi-out over usbc otg interfaces / midi-mpe / pitchbending / omnichord / guitar fretting synth for android </a></h5>

soooo i'll tell you a little bit about this: The speed shifting feature is legit! You can double tap the BPM floating overlay to set a target BPM that tracks that play subsequently will be speed shifted to (pitch preserved).
You have to use shizuku to activate RootlessJamesDSP the first time, and if you uninstall and reinstall you'll have to do that again. I'm pretty sure the wireless debugging shizuku activation method isn't gonna work (didn't for me due to some kinda ssh library?), so you'll have to run a command something like the following while you're connected to your computer via usb tethering.
1) Under android settings - mobile hotspot and tethering, "USB tethering" has to be toggled On.
2) Settings -> About Phone -> Software information -> Tap Build Number a Bunch -> Now you're a Developer
3) Settings -> Developer Options -> USB debugging -> On
4) Connect Phone
5) Install Shizuku
6) Open Shizuku
7) Start by connecting to a computer -> View command
8) I'm not sure if this command is going to work... ask Perplexity for help. You're essentially going to run that libshizuku.so script which is what I did but my Shizuku told me to do "adb shell sh /storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh" which didn't quite work for me. I think the command might have been oriented towards Linux users or assumed something. Might seem silly but just ask Perplexity for help, I don't think I can start Shizuku for you, I'm past that step already haha-
9) Well, this is what I ran: it's one line.
adb -s R5CWB2R1GNX shell /data/app/~~DE5Pxnc2SGIdC-VFQcRvqg==/moe.shizuku.privileged.api-SQPXAcKndLSmYLumnGz53A==/lib/arm64/libshizuku.so

So these are the new features:
1) Effect order - you can now choose which order the effects get processed in!
2) Speed Shift - this is key!  listen to music slowed down cause it's better that way! sometimes you just wanna listen to some nice tunes slowed down without having a local file cued up. well, now you can. you weren't before, were you? haha! i gotchu! but seriously consider buying Explorer 95 and guitarchord in the future if you like this project...... please.... my checking account has under $200 in it (verified true fact as of time of writing - *other voice aside* mmm yeah it's true - bunbun's checking account is legit under two hundred!)
-speed shift will show a floating icon that you can drag up and down upon in order to shift the speed. Long press and then drag any of the floating icons to move them. You can configure the swipe step with the sum of a 0.01x scale and an 0.001x scale slider which allow 0 levels. I like mine set at 0.01x.
3) Bpm Display - if you double tap on this you can set a target BPM. It also shows the adjusted BPM and a slider on the effects block lets you change its size. The speed shifter will hold a backlog and after like 20 minutes you'd better just toggle it on and off cause we can't hold an infinitely long backlog of uncompressed waves (playback continues at normal speed but the DSP plays it back slower) so it'll skip forwards 30 seconds with a 0.1s fade and continue from there each time the backlog runs out. Sometimes the BPM might not target correctly due to the detected BPM being incorrect so you can just manually adjust it via the speed slider.
Recorder A & B - I put two recorders in so you can record wet / dry copies of the same mix. They sync (start recording at the same time). You can rearrange them in the effects but usually Recorder A is at the end of the signal chain and Recorder B is at the beginning (pre-effect). You might need to nudge in post if you're legit mixing these two together.
4) Pitch Shifter - this shifts the pitch of the audio without changing the tempo. There are two, Pitch shifter, and Pitch Shifter HQ.
Piano Keyboard - you can control either pitch shifter with this, it brings up a four octave keyboard of two rows of two octaves which will be self explanatory as the semitones are marked on all six of the C keys. There's a portamento slider to specify your pitchshift interpolation time. You should definitely use Pitch Shifter HQ if it works well on your phone, works fine on my budget 2025 year tester phone. The "x" button in the upper right actually minimizes the keyboard then you can tap on the minimized music note icon to...... bring it back!
5) Compressor - believe it or not this is new haha! yeah you can adjust threshold, ratio, attack, release, knee width, makeup gain, yeah whatever, it's just a compressor, i'm not charging you anything for this, no upsell, it's on me lol. Yeah so if you didn't know, compressors compress the audio above the threshold by the ratio. A higher ratio means more compression. The makeup gain is like, the gain after the compressor. If you're bumping the bass and it's clipping, maybe set the threshhold like, idk, -10 dB, ratio like 8:0? Just know that if the bass is causing compression then you're gonna be compressing the highs and mids in their volume level as well. Well, you know, it's a compressor. Iunno just ignore this description if you already know.

um yeah here's the rest of the readme.md you can read this if you want I didn't change any of the stuff text wise that looks like I didn't write it but I did remove some hotlinked images cause I don't believe in that (well it's real i just don't think it's very cash money to make someone's server send you multiple tens of kB expending their currents to see a little picture that you didn't need lol)

## Limitations
* Apps blocking internal audio capture remain unprocessed (e.g., Spotify, Google Chrome)
* Cannot coexist with (some) other audio effect apps (e.g., Wavelet and other apps that make use of the `DynamicsProcessing` Android API)
* Increased audio latency 


Apps confirmed working:
* YouTube
* YouTube Music
* Amazon Music
* Deezer
* Poweramp
* Substreamer
* Twitch
* Spotify ReVanced **(Patch required)**
* Apple Music
* Vinyl Music Player
* ...

Unsupported apps include:
* Spotify (patch for Spotify exists)
* Google Chrome
* SoundCloud
* ...

Tested on:
* Samsung Galaxy S20+ (Android 12; OneUI 4.0)
* Stock AOSP emulator (Android 10-13)
* Google Pixel 6 Pro (Android 13)
* Google Pixel 6a


Changes regarding Android 15 and up
* Due to google adding a privacy focused feature regarding screen sharing. 
* Notifications (and probably other things) get hidden by the system.
* To fix this enabling "Disable screen share protetion" in developer options is necessary

## Spotify support patch
> **Note** This patch is universal and may also work with other apps than Spotify.

You can only use Spotify with this application if you patch the Spotify app.
The setup is very easy:

1. Download and install the [ReVanced manager APK](https://github.com/revanced/revanced-manager/releases) 
2. Install the unpatched Spotify app
3. Open ReVanced Manager, select Spotify and enable the `Remove screen capture restriction` patch.
4. Start the patching process and install the patched APK once it is done.
5. You can now use Spotify with RootlessJamesDSP.

### Patching other unsupported apps

The `Remove screen capture restriction` patch is universal and can also be used with custom APKs other than Spotify.
The patch cannot remove capture restrictions for apps that use the native AAudio C++ API for playback. 

1. Download and install the [ReVanced manager APK](https://github.com/revanced/revanced-manager/releases)
2. Open Revanced Manager, go to the settings screen and enable 'Show universal patches'
3. Go to the patches tab and tap on 'Select an app' and press the 'Storage' action button in the bottom-right corner.
4. Select your APK using the file picker.
5. Enable the `Remove screen capture restriction` patch.
6. Start the patching process and install the patched APK once it is done. Make sure to uninstall the unpatched app if it is installed, otherwise you will run into a signature conflict during installtion.

> **Warning** If the patched app crashes on startup (or refuses to work properly), it is likely that the app uses signature checks or other protections against tampering. In that case, additional patches that disable these anti-tampering checks would need to be created by hand.

## Differences to other rootless FX apps

Regular rootless audio effect apps on the Play Store all essentially work the same way:
Android has several default audio effects built into its operating system that these apps can use without any special permissions. Here's a list of those: https://developer.android.com/reference/android/media/audiofx/AudioEffect.

Being restricted to these default built-in audio effects is problematic if you want to implement any advanced custom effects such as Viper or JDSP, because Android does not allow apps to access & modify the audio stream directly.

To work around this problem, RootlessJamesDSP uses a bunch of tricks to gain full access to the audio stream of other apps. This is done via Android's internal audio capture.
This allows RootlessJamesDSP to apply its custom audio effects directly without relying on Android's built-in effects.

Unfortunately, these tricks are not 100% reliable and introduce some limitations.
Apps such as Spotify block internal audio capture (they don't want people to record their songs), and because of that, RootlessJamesDSP cannot directly access the audio stream of that app.
This is the reason why a special patch is required to disable this DRM restriction inside Spotify's app. Patches for other apps with these DRM restrictions do not exist, but are possible to do.

## Translations

Yeah I'll translate it... just show up to Github Issues here tellin' me some sacre bleu i cannot comprehende dis Kompressor ist das ein Auto??? Was ist dis Pitch Shiften??? I need die interface to be in mein language! But seriously viel gluck englisch ist schwer lmao but realistically if you could give me a pull request for the strings.xml with some well written text to explain things for the people in your country that would be helpful (not for me, for them, i don't want to do anything! i'm forced to help! i have to maintain this project lol!)
## Downloads

This app is available for free on Google Play: [https://play.google.com/store/apps/details?id=net.skeyboard.dsp](https://play.google.com/store/apps/details?id=net.skeyboard.dsp)



## Using Root

This app focuses on a rootless implementation, but it can be made to work with the magisk module too. [See here for details](BUILD_ROOT.md).

All the limitations mentioned above are **not relevant** for the magisk/root version. 

## Credits

* bunbun-midi - speedshifting, pitchshifters, floating keyboard for pitchshifter (two algos), compressor, effects reordering, floating recorders
* JamesDSP - [James Fung (@james34602)](https://github.com/james34602)
* Theming system & backup system based on Tachiyomi

### Translators


<!-- CROWDIN-CONTRIBUTORS-START -->
<table>
  <tr>
  <td align="center" valign="top">
      <a href="https://crowdin.com/profile/ThePBone"><img alt="logo" style="width: 64px" src="https://crowdin-static.cf-downloads.crowdin.com/avatar/15683553/medium/d13428d1e0922bc2069500aef57d1459.png" />
        <br />
        <sub><b>Tim Schneeberger (ThePBone)</b></sub></a>
      <br />
      <sub><b>22396 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/netrunner-exe"><sub><b>Oleksandr Tkachenko (netrunner-exe)</b></sub></a>
      <br />
      <sub><b>13732 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/hanifz99"><sub><b>Hanifz99 (hanifz99)</b></sub></a>
      <br />
      <sub><b>4192 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/rex07"><sub><b>Rex_sa (rex07)</b></sub></a>
      <br />
      <sub><b>3543 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/FrameXX"><sub><b>FrameXX</b></sub></a>
      <br />
      <sub><b>3518 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/eevan78"><sub><b>Ivan Pesic (eevan78)</b></sub></a>
      <br />
      <sub><b>3471 words</b></sub>
    </td>
  </tr>
  <tr>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/Add000"><sub><b>Add000</b></sub></a>
      <br />
      <sub><b>3469 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/FlavioPonte"><sub><b>FlavioPonte</b></sub></a>
      <br />
      <sub><b>3455 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/Gokwu"><sub><b>Choi Jun Hyeong (Gokwu)</b></sub></a>
      <br />
      <sub><b>3438 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/narpatosian"><sub><b>narpatosian</b></sub></a>
      <br />
      <sub><b>3431 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/AeroShark333"><sub><b>Abiram Kanagaratnam (AeroShark333)</b></sub></a>
      <br />
      <sub><b>3373 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/fankesyooni"><sub><b>fankesyooni</b></sub></a>
      <br />
      <sub><b>3316 words</b></sub>
    </td>
  </tr>
  <tr>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/vjburic1"><sub><b>Vjekoslav Buric (vjburic1)</b></sub></a>
      <br />
      <sub><b>3237 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/beruanglaut"><sub><b>Beruanglaut (beruanglaut)</b></sub></a>
      <br />
      <sub><b>3168 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/fred199542"><sub><b>Federico D. (fred199542)</b></sub></a>
      <br />
      <sub><b>2903 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/ismaeloi1"><sub><b>Ismaël GUERET (ismaeloi1)</b></sub></a>
      <br />
      <sub><b>2844 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/MajorCanel"><sub><b>HasanDgn37 (MajorCanel)</b></sub></a>
      <br />
      <sub><b>2679 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/marcin.petrusiewicz"><sub><b>Marcin Petrusiewicz (marcin.petrusiewicz)</b></sub></a>
      <br />
      <sub><b>2360 words</b></sub>
    </td>
  </tr>
  <tr>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/liziq"><sub><b>zhiq liu (liziq)</b></sub></a>
      <br />
      <sub><b>1950 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/timli103117"><sub><b>Tim Li (timli103117)</b></sub></a>
      <br />
      <sub><b>1886 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/TecitoDeMenta"><sub><b>Alondra Márquez (TecitoDeMenta)</b></sub></a>
      <br />
      <sub><b>1847 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/phannhanh"><sub><b>Phan Nhanh (phannhanh)</b></sub></a>
      <br />
      <sub><b>1842 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/MES-INARI"><sub><b>MES-mitutti (MES-INARI)</b></sub></a>
      <br />
      <sub><b>1750 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/jontix"><sub><b>jontix</b></sub></a>
      <br />
      <sub><b>1731 words</b></sub>
    </td>
  </tr>
  <tr>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/LePom_"><sub><b>Miko Nurmi (LePom_)</b></sub></a>
      <br />
      <sub><b>1464 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/SkyAfterRain_tw"><sub><b>SkyAfterRain_tw</b></sub></a>
      <br />
      <sub><b>1419 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/dang15082006"><sub><b>Đăng Nguyễn (dang15082006)</b></sub></a>
      <br />
      <sub><b>1307 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/AXEN.dev"><sub><b>Alessandro Belfiore (AXEN.dev)</b></sub></a>
      <br />
      <sub><b>1228 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/TheGary"><sub><b>Gary Bonilla (TheGary)</b></sub></a>
      <br />
      <sub><b>1030 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/kyunairi"><sub><b>kyunairi</b></sub></a>
      <br />
      <sub><b>888 words</b></sub>
    </td>
  </tr>
  <tr>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/LuckyMehra776"><sub><b>Lucky Mehra (LuckyMehra776)</b></sub></a>
      <br />
      <sub><b>819 words</b></sub>
    </td>
    <td align="center" valign="top">
      <a href="https://crowdin.com/profile/roccovantechno"><sub><b>Gyuri Gergely (roccovantechno)</b></sub></a>
      <br />
      <sub><b>714 words</b></sub>
    </td>
  </tr>
</table><a href="https://crowdin.com/project/rootlessjamesdsp" target="_blank">Translate in Crowdin 🚀</a>
<!-- CROWDIN-CONTRIBUTORS-END -->
