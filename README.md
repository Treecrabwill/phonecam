# PhoneCam: old Android phone → security camera

- On-device AI detects people and sends a push alert with a snapshot (free **ntfy** app)
- Live video you can watch in any browser, at home or away

## Install
On the old phone (Android 5.0+), open
**https://github.com/Treecrabwill/phonecam/releases/latest**,
download **PhoneCam.apk**, and open it. Allow "install unknown apps" when asked.

Every push to `main` rebuilds the app automatically (see the **Actions** tab).

## Use
1. Open PhoneCam and tap **Allow** for the camera (and microphone if you want audio).
2. On your own phone, install **ntfy**, subscribe to the topic shown in PhoneCam's settings, and tap **Test**.
3. Tap **Start as CAMERA**. It shows two viewer links:
   - 🌍 **From anywhere:** `https://treecrabwill.github.io/phonecam/#view=<your camera ID>`
   - 🏠 **Same Wi-Fi:** `http://<phone-ip>:8080/#view=<your camera ID>`
4. Keep it plugged in with the app on screen. Tap **Black screen** to save power.
   If the phone restarts, opening the app resumes watching automatically.

Your camera ID and ntfy topic are random and stored only on the phone, never in this repo.

## One-time setup (repo owner)
Settings → Pages → Source → **GitHub Actions**, so the viewer page gets published.
