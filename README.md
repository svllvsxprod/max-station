<div align="center">

# Max Station

**A Bluetooth speaker and Android TV setup for the first-generation Yandex Station Max**

[Русский](README.ru.md) · [Installation guide](docs/setup.md) · [APK releases](https://github.com/svllvsxprod/max-station/releases)

</div>

Max Station documents and ships the original components of a working conversion of **YNDX-0008 without Zigbee**, tested on Android 9 firmware **5000000921**. The conversion keeps the speaker, remote, front display and light ring, replaces the TV home screen, and runs a voice assistant directly on the device. The resulting local speaker and TV interface work without a Yandex account or subscription. The stock activation wizard, subscription-related runtime services and OTA updater are absent for Android user 0.

<p align="center">
  <img src="docs/images/assistant.png" width="48%" alt="Voice assistant on a real Max Station">
  <img src="docs/images/time-minutes.png" width="48%" alt="TV alarm time picker">
</p>

## What worked on the tested unit

- Bluetooth audio, physical and phone volume, stock Bluetooth indication and TV remote.
- Voice overlay opened by holding the remote’s assistant button, with a sound cue and BLE microphone. Voice runs directly against the OpenAI API. Groq is optional for Android dictation.
- Voice alarms and dismissal, media controls, personal Spotify Liked Songs playback with the television off, local preferences across sessions.
- Local firewall with default-deny app policy after Android starts. For a WAN VPN, the documented setup uses a router with its own tunnel.

The setup is performed in stages. It starts with a three-wire service cable and upstream model-specific provisioning. The release contains application APKs and source. A factory-to-final run on a second unit, an unlocked bootloader and pre-Android network isolation have yet to be verified.

| Guide | Scope |
| --- | --- |
| [Start to finish](docs/setup.md) | Hardware, firmware, debloat, apps, local voice setup and acceptance tests |
| [USB pinout](docs/usb.md) | Connector orientation and three-wire ADB cable |
| [Package inventory](docs/packages.md) | 22 removed, six kept, commands and restoration boundaries |
| [Network](docs/network.md) | Default deny, Android system traffic, router VPN, boot limits |
| [Voice commands](docs/commands.md) | Examples, verified behavior and client-specific gaps |
| [Agent handoff](docs/agent.md) | Instructions to reuse with an agent on another unit |
| [Build and signing](docs/build.md) | Build dependencies, checksums, public APKs and signing |

Project code is under the [PolyForm Noncommercial License](LICENSE). [Third-party notices](third-party/DEPENDENCIES.md) apply separately.
