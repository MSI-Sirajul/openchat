<div align="center">
  <h1>💬 OpenChat Android</h1>
  <p><strong>Ultra-lightweight, resilient, end-to-end communication platform featuring native WebRTC voice calls, real-time messaging, direct notification actions, and sticky background keep-alive.</strong></p>

  <p>
    <a href="#download-arm64"><img src="https://img.shields.io/badge/APK-arm64--v8a-4F46E5?style=for-the-badge&logo=android&logoColor=white" alt="ARM64 APK" /></a>
    <a href="#download-armv7"><img src="https://img.shields.io/badge/APK-armeabi--v7a-06B6D4?style=for-the-badge&logo=android&logoColor=white" alt="ARMv7 APK" /></a>
    <a href="#downloads"><img src="https://img.shields.io/badge/Google%20Play-Coming%20Soon-34D399?style=for-the-badge&logo=google-play&logoColor=white" alt="Google Play" /></a>
  </p>

  <p>
    <img src="https://img.shields.io/badge/Kotlin-2.0.21-purple?style=flat-square&logo=kotlin" alt="Kotlin" />
    <img src="https://img.shields.io/badge/Jetpack%20Compose-M3-green?style=flat-square&logo=jetpackcompose" alt="Compose" />
    <img src="https://img.shields.io/badge/WebRTC-Native%20Audio-blue?style=flat-square&logo=webrtc" alt="WebRTC" />
    <img src="https://img.shields.io/badge/Min%20SDK-26%20(Android%208.0)-orange?style=flat-square" alt="Min SDK" />
    <img src="https://img.shields.io/badge/R8-Minified%20%26%20Shrunk-red?style=flat-square" alt="R8" />
    <img src="https://img.shields.io/badge/Signing-V1%20%2B%20V2%20%2B%20V3%20%2B%20V4-success?style=flat-square" alt="Signing" />
  </p>
</div>

<hr />

<h2>📱 Overview & Key Features</h2>

<ul>
  <li><strong>Native WebRTC Voice Calling:</strong> Peer-to-peer audio calls with STUN/TURN trickle ICE negotiation, acoustic echo cancellation (AEC), and noise suppression (NS).</li>
  <li><strong>Adaptive Call State Flow:</strong> Responsive state indicator transitioning smoothly from <code>Calling</code> &rarr; <code>Ringing</code> &rarr; <code>Duration Timer (00:01+)</code> upon remote pickup.</li>
  <li><strong>Background Keep-Alive & Sticky Services:</strong> <code>OpenChatKeepAliveService</code> runs with <code>START_STICKY</code> to maintain active WebRTC signaling and presence even during Android low-memory recovery.</li>
  <li><strong>Interactive Push Notifications:</strong>
    <ul>
      <li><strong>Direct Reply:</strong> Reply to messages straight from the notification shade without launching the app.</li>
      <li><strong>1-Tap Call Handling:</strong> Accept or Decline calls directly from high-priority incoming call notifications with an ongoing in-call timer banner.</li>
    </ul>
  </li>
  <li><strong>Global Network State Management:</strong> System-wide <code>ConnectivityManager.NetworkCallback</code> broadcasting connection status with an animated "No Internet Connection" banner displayed automatically across all screens when offline.</li>
  <li><strong>Battery Optimization Exemption:</strong> In-app dialog and Settings toggle requesting exemption from Doze mode (<code>REQUEST_IGNORE_BATTERY_OPTIMIZATIONS</code>) to guarantee 24/7 background VoIP call wakeups.</li>
  <li><strong>Lightweight App Architecture:</strong> R8 minification, resource shrinking, ViewBinding optimization, and strict ABI filtering (targeting <code>armeabi-v7a</code> and <code>arm64-v8a</code> only) to minimize APK size.</li>
</ul>

<hr />

<h2>🔐 Permissions & Justifications</h2>

<table width="100%">
  <thead>
    <tr>
      <th align="left">Permission</th>
      <th align="left">Classification</th>
      <th align="left">Functional Justification</th>
    </tr>
  </thead>
  <tbody>
    <tr>
      <td><code>android.permission.RECORD_AUDIO</code></td>
      <td>Runtime</td>
      <td>Audio capture for WebRTC VoIP calls and voice notes.</td>
    </tr>
    <tr>
      <td><code>android.permission.POST_NOTIFICATIONS</code></td>
      <td>Runtime (API 33+)</td>
      <td>Displaying incoming call banners and message notifications with Direct Reply.</td>
    </tr>
    <tr>
      <td><code>android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS</code></td>
      <td>Special Intent</td>
      <td>Preventing OS Doze kills to guarantee real-time incoming call signaling.</td>
    </tr>
    <tr>
      <td><code>android.permission.FOREGROUND_SERVICE</code></td>
      <td>Normal</td>
      <td>Keeping WebRTC media pipelines and connection monitors active.</td>
    </tr>
    <tr>
      <td><code>android.permission.FOREGROUND_SERVICE_MICROPHONE</code></td>
      <td>Normal (API 34+)</td>
      <td>Android 14+ typed foreground service for call microphone recording.</td>
    </tr>
    <tr>
      <td><code>android.permission.FOREGROUND_SERVICE_PHONE_CALL</code></td>
      <td>Normal (API 34+)</td>
      <td>Android 14+ typed foreground service for ongoing VoIP call management.</td>
    </tr>
    <tr>
      <td><code>android.permission.ACCESS_NETWORK_STATE</code></td>
      <td>Normal</td>
      <td>Evaluating network availability and broadcasting offline banners.</td>
    </tr>
    <tr>
      <td><code>android.permission.INTERNET</code></td>
      <td>Normal</td>
      <td>Signaling, WebRTC audio streaming, Nhost GraphQL, and FCM communication.</td>
    </tr>
    <tr>
      <td><code>android.permission.RECEIVE_BOOT_COMPLETED</code></td>
      <td>Normal</td>
      <td>Restarting background notification and call services after device reboot.</td>
    </tr>
    <tr>
      <td><code>android.permission.VIBRATE</code> &amp; <code>WAKE_LOCK</code></td>
      <td>Normal</td>
      <td>Incoming call vibration and keeping CPU awake during active voice calls.</td>
    </tr>
  </tbody>
</table>

<hr />

<h2>📖 Usability Guide</h2>

<ol>
  <li><strong>Sign In or Sign Up:</strong> Create an account using email/password. Credentials are encrypted in local secure storage.</li>
  <li><strong>Permission Authorization:</strong> On first launch after authentication, grant microphone and notification permissions.</li>
  <li><strong>Battery Optimization Exemption:</strong> When prompted with the Battery Optimization Dialog, tap <em>"Grant Exemption"</em> to ensure background call reception. You can also recheck this anytime in <em>Settings &rarr; Background Calls &amp; Keep-Alive</em>.</li>
  <li><strong>Placing a Voice Call:</strong> Navigate to any chat or contact profile and tap the phone icon. Observe the indicator:
    <ul>
      <li><code>Calling</code>: Establishing local WebRTC session.</li>
      <li><code>Ringing</code>: Recipient device is ringing.</li>
      <li><code>00:00</code>: Recipient answered; live duration counter is running.</li>
    </ul>
  </li>
  <li><strong>Notifications & Quick Reply:</strong> Answer incoming messages directly in the notification shade using the <em>"Reply"</em> action. Incoming calls can be accepted or declined with 1 tap.</li>
  <li><strong>Offline Handling:</strong> If network connection is lost, an animated <em>"No Internet Connection"</em> banner appears at the top of the screen until connection is restored.</li>
</ol>

<hr />

<h2>⚡ Build & Optimization Specifications</h2>

<ul>
  <li><strong>Target ABIs:</strong> <code>arm64-v8a</code>, <code>armeabi-v7a</code> (all x86/x86_64 emulator binaries excluded from production builds to minimize APK download footprint).</li>
  <li><strong>Minification & Shrinking:</strong> <code>isMinifyEnabled = true</code> and <code>isShrinkResources = true</code> using R8 optimization rules in <code>proguard-rules.pro</code>.</li>
  <li><strong>Signing Schemes:</strong> Signed with APK Signature Schemes <strong>V1, V2, V3, and V4</strong> for maximum security and compatibility.</li>
  <li><strong>ViewBinding:</strong> Enabled alongside Jetpack Compose for high-speed layout rendering.</li>
</ul>

<hr />

<div align="center">
  <p>&copy; 2026 OpenChat Team. Open-source, production-ready communication suite for Android.</p>
</div>
