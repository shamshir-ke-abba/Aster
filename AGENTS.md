# Aster Project Rules

## Android Screencap / Screenshot Protocol
- **NEVER** stream binary screenshot data directly using `adb exec-out screencap -p > file.png` without isolation. Device drivers or emulators (e.g., AMD GPU / Mesa drivers) frequently write warnings (such as `/vendor/etc/hwdata/amdgpu.ids: No such file or directory`) to stdout/stderr, prepending corrupt bytes to the PNG binary header. When inspected via `view_file`, this corrupt data triggers `INVALID_ARGUMENT (code 400)` API errors that corrupt conversation history.
- **ALWAYS** use the safe two-step capture and pull sequence:
  ```bash
  adb shell screencap -p /sdcard/screenshot.png
  adb pull /sdcard/screenshot.png <local_destination_path.png>
  adb shell rm /sdcard/screenshot.png
  ```
- **Validation**: After pulling any screenshot, always verify that the file is valid PNG image data (e.g. `file <path.png>`) before calling `view_file` or passing it to multimodal tools.
