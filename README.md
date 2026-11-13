This is the new version of bDSU! I released an earlier build, but it had bugs and missing features—most of which are now fixed!

**bDSU** is an Android app designed to help you easily set up DSU (Dynamic System Updates) :D  
With bDSU, you can seamlessly test custom ROMs or your own builds. It also supports allocated persistent storage! Feel free to download and test the custom ROMs available right in the menu, or just explore on your own :]

> 💡 **Note:** Downloads include automatic **hash verification** to guarantee file integrity!

### What's new in 1.3.6
* **Fresh DSU slot per install** — bDSU no longer reuses the shared OEM `dsu` slot. A failed OEM install can leave an unreadable `/metadata/gsi/dsu/dsu/lp_metadata` behind, after which every later attempt fails with `Invalid partition metadata header table size` or `Could not create userdata image`. Each bDSU install now gets its own `bdsu<hex>` slot and therefore a clean metadata directory.
* **Safe default userdata** — a temporary install now asks the DSU service for 2 GiB of userdata explicitly. Some Samsung builds otherwise substitute a 24 GiB default, which can make partition creation fail on a nearly full disk. A user-selected 3 GiB+ value still takes priority.
* **Live fast-mode detail** — during xz→gzip packaging, gzip copying, decompression and native gzip compression, the progress line now shows MB done, MB total, current MB/s, MB left and an ETA. Phases with an estimated total use a `~` marker.
* **Partial-file cleanup** — a final download failure or each retry now removes the failed DownloadManager request and its partial file. SAF exports are written as a clearly marked `*.part` document, renamed only after the copy finishes, and stale parts are cleaned on the next launch.
* **bDSU-tagged file names** — exported ROMs are named like `bDSU-PixelOS-..._bdsu_d159c20669572276.img.xz`. The short tag identifies the exact catalog build.
* **Name-based SHA-256 verification** — a local file containing a `bDSU` marker is matched to the catalog by its tag or by a unique family/version/date name, then checked against the full catalog SHA-256 and size before install. Unmatched or ambiguous bDSU names are refused; remove the marker to install an untrusted custom image.
* **DSU launch fallback** — if the system DSU service rejects the public Download URI, bDSU retries the documented launch from `/data/local/tmp`.
* **Verification progress** — SHA-256/MD5 hashing is cancellation-aware and reports percentage progress.

### What's new in 1.3.5
* **Choose where custom ROMs are saved** — the custom/AOSP downloader asks for a folder with the system picker and saves the finished ROM there, outside `Android/data`. The app installs directly from that folder; SHA-256 is verified before the move.
* **19 SHA-256 verified catalog builds** — added PixelOS 16.2, VoltageOS 5.11 (EXT4) and crDroid 12.12 (Android 16).
* **English DSU progress notifications** — every verification, decompression, staging and launch step is mirrored to a single ongoing notification, so you can leave the app while a large image is prepared.
* **Adjustable 4 GB image limit** — Settings now has an **Allow images larger than 4 GB** switch. It is off by default, keeping the safe limit; when enabled, images up to 64 GB are accepted (your device's DSU implementation may still refuse them).
* **Cancel from the app or the notification** — both the in-app progress card and the ongoing notification now have an English **Cancel** action for downloads and DSU preparation.
* **Stability & performance work** — cached system-property reads, a 30-minute shell watchdog, crash-guarded download monitoring and install pipeline, throttled progress updates, and fewer process spawns.
* **Real progress instead of a frozen 50%** — the preparation gauge now reads the exact uncompressed size from the XZ/gzip metadata, reports real bytes and speed, and warns when a download has received no data for 10 seconds.
* **One gauge everywhere** — the in-app bar and the ongoing notification use the same percentage and the same text; the duplicate DownloadManager notification is hidden.
* **Faster DSU packaging** — the xz→gzip path now uses deflate level 1 (BEST_SPEED) with 1 MB buffers, checks free space first, and cleans up partial files on failure.
* **New launcher icon and signed release build** — `app_icon.png` was replaced and the release APK is signed with the project's release keystore.
* **Repository cleanup** — removed unused sample resources, the stale checksum helper script and the generated Gradle daemon JVM file.

See [`MODIFICATIONS.txt`](MODIFICATIONS.txt) for the complete, GPL-3.0-style change notice.

### Credits & Thanks
* **Shizuku**: Huge thanks to the Shizuku project! bDSU relies on Shizuku to function :3
* **unfayd**: A big thank you to unfayd for helping with code and testing (and being the best friend ever :d).

---

### Notes & License
bDSU is a hobby project. Full transparency: yes, I used AI to help build this, but I put a ton of effort into testing DSU setups, downloads, and features directly on my own device.

If you like this project, giving it a star ⭐ or sharing it would mean a lot! You are totally free to fork, modify, or customize the app (as long as it's not for malicious intent). If you do release modified versions or fixes, please credit us and link back to this repo :p

### Disclaimer
* Installing DSU requires an **unlocked bootloader**.
* Even though DSU is safer than standard flashing, please proceed with caution. **I assume no responsibility for any potential issues or damages.**

Wishing you all the best and good luck installing DSUs! :dddddd

### New Update
* **New download options added.**
* **Notifications added.**
* **Cancel buttons added.**
* **More detail during installation added.**
* **And now you choose the file path!** :p
* **A few more solid optimizations and stability improvements** so users run into fewer errors. :d
