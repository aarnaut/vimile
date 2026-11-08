# Vimile

*Pronounced "VIM-ih-lee", like simile: a likeness of Vimium, for your phone.*

Vimium-style keyboard navigation for Android. Press `f`, and every tappable element
gets a letter label; type the letters to tap it. Built as an accessibility service,
mainly for developers who drive emulators and devices from their computer keyboard.

## Keys

| Key | Action |
|---|---|
| `f` | Show hints; type a hint's letters to tap that element |
| `Shift+F` | Show hints; the chosen element is long-pressed |
| `Backspace` | Undo the last hint letter (cancels hints if none typed) |
| `Esc` | Cancel hints / leave insert mode |
| `j` / `k` | Scroll down / up (hold to keep scrolling) |
| `h` / `l` | Scroll left / right (pagers, carousels) |
| `d` / `u` | Page down / up |
| `x` | Dismiss: close the bottom sheet, dialog, menu or shade on top, as a tap outside would |
| `Shift+H` | Back |
| `gh` | Home screen |
| `gr` | Recent apps |
| `gn` | Open notifications |
| `gq` | Open quick settings |
| `i` | Insert mode: every key goes to the app until `Esc` |

Hints cover every window on screen, so they also work in the notification shade,
quick settings, dialogs and bottom sheets (the app behind them gets none).

Vimile steps aside automatically while a text field is focused, and lets anything
with Ctrl/Alt/Cmd through so app and system shortcuts keep working.

### How `x` decides what to do

1. Notification shade / quick settings open → close them.
2. A bottom sheet's scrim (Material `touch_outside`, Compose scrim) → click it.
3. Anything offering the accessibility "dismiss" action (e.g. the share sheet) → dismiss.
4. The focused window is smaller than the screen (dialog, popup) → tap just outside it.
5. Otherwise → Back.

## ⚠️ Restriction: key presses must come from a (virtual) hardware keyboard

Android only shows accessibility services key presses that come from an input
device. Key presses **injected** by software are delivered straight to the app and
skip Vimile entirely. Vimile cannot work around this; it is how Android's input
pipeline works.

In the device's logs, injected keys look like this (`d=-1` = no input device):

```
InputDispatcher: Inject key (3402/2000): action=0, f=0x0, d=-1
```

| Input source | Works with Vimile? |
|---|---|
| Emulator window (standalone emulator) | ✅ The emulator presents your keyboard as a hardware keyboard |
| `scrcpy --keyboard=uhid` (or `-K`) | ✅ Creates a virtual HID keyboard on the device |
| Bluetooth / USB keyboard plugged into the device | ✅ |
| Android Studio **Running Devices** mirroring | ❌ Injects key events |
| `scrcpy` in its default keyboard mode | ❌ Injects key events |
| `adb shell input keyevent …` | ❌ Injected |
| `android input key …` (Android CLI) | ❌ Injected |
| Emulator console `event send` | ❌ Doesn't reach the device on current emulators |

Symptom of an unsupported source: arrows and Enter work in apps, but `f` does
nothing and `adb logcat -s Vimile` shows only `Vimile connected` and no `key …`
lines.

### Using a physical device

```bash
brew install scrcpy
scrcpy -s <device-serial> --keyboard=uhid
```

The device then lists an input device named `scrcpy`
(`adb shell dumpsys input | grep scrcpy`). The device treats it as a physical
keyboard, so its layout comes from the device's physical keyboard settings, and the
on-screen keyboard usually stays hidden while it's connected.

## Emulator quirks

- The Mac's `Esc` key arrives as `KEYCODE_BACK`, not `KEYCODE_ESCAPE`. Vimile treats
  both as Esc.
- Shift is released *before* the letter (`SHIFT↓ H↓ SHIFT↑ H↑`), so Vimile acts on
  key-down and remembers which keys it swallowed to also swallow their key-up.

## Enabling the service

1. Install the app (see Building).
2. **Android 13+ / Samsung:** apps not installed from a store have "restricted
   settings". If the Vimile toggle is greyed out, open Settings → Apps → Vimile →
   ⋮ → **Allow restricted settings** first.
3. Settings → Accessibility → (Installed apps →) Vimile → turn on.

On an emulator you can do it from the terminal:

```bash
adb shell settings put secure enabled_accessibility_services dev.vimile/.VimileService
adb shell settings put secure accessibility_enabled 1
```

## Building

Needs a JDK 17+ (set up here with SDKMAN: `sdk install java 21.0.13.0+9-tem`) and
the Android SDK (`local.properties` points at it).

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Debugging

```bash
adb logcat -s Vimile
```

Every key Vimile sees is logged with its modifier state and the current mode, and
each hint's target element is logged when hints are shown.

Because injected keys never reach Vimile, **debug builds** accept key presses by
broadcast and run them through the same handler as real keys (e.g. for screenshots).
Lowercase letters are plain keys, uppercase letters are Shift+key, `.` is Esc:

```bash
adb shell am broadcast -a dev.vimile.DEBUG --es keys f     # show hints
adb shell am broadcast -a dev.vimile.DEBUG --es keys sa    # then type hint letters
adb shell am broadcast -a dev.vimile.DEBUG --es keys gn    # open notifications
adb shell am broadcast -a dev.vimile.DEBUG --es keys x     # dismiss
adb shell am broadcast -a dev.vimile.DEBUG --es keys .     # Esc
```

Don't use `android layout` / UI Automator-based tools while testing: they start a
UiAutomation session, which can restart or suppress other accessibility services.

## Code

- `VimileService.kt`: key handling, modes, clicking
- `Navigator.kt`: scrolling, dismissing, taps and swipes
- `Hints.kt`: which elements get hints, label generation
- `HintOverlay.kt`: draws the hint badges in an accessibility overlay window
