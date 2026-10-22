# ROM source notes

The catalog should not guess direct download URLs. The official Android Developers GSI page provides Android 11, 12, 13, 14, 15, 16 and newer GSI release sections, but Google states these images are for app validation/development and licensing must be respected. Evolution X provides a project landing page and device downloads rather than a universal GSI file. The archived phhusson treble_experimentations releases provide historical, explicit arm64-ab image assets (including Android 12.1) but are not current and should be labeled legacy.

Implementation decision: keep third-party ROM entries as verified landing pages or known release assets only when a direct URL is stable; show the source URL in the UI. Never imply that every ROM works on every device. Filter by device ABI, Treble, API level, and available space. DSU installation must remain an explicit user action through Shizuku, with no exploit or hidden reboot.
