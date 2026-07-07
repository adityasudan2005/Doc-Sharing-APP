import os
import sys
import subprocess
import shutil
import time
import threading

def run_adb_reverse():
    """Attempts to configure ADB reverse port forwarding in a background loop."""
    # Check if adb is in PATH
    adb_path = shutil.which("adb")
    
    # On Windows, try to find ADB in the default Android SDK location
    if not adb_path and sys.platform.startswith("win"):
        local_app_data = os.environ.get("LOCALAPPDATA", "")
        if local_app_data:
            default_adb = os.path.join(local_app_data, "Android", "Sdk", "platform-tools", "adb.exe")
            if os.path.exists(default_adb):
                adb_path = default_adb
                print(f"[USB SETUP] Found ADB in Android Sdk path: {adb_path}")

    if not adb_path:
        print("----------------------------------------------------------------")
        print("[USB WARNING] 'adb' command not found in your system PATH or Android Sdk folder.")
        print("              USB (Wired) mode won't work unless ADB is installed")
        print("              and you have enabled USB debugging on your phone.")
        print("              (Wi-Fi mode will still work fine!)")
        print("----------------------------------------------------------------\n")
        return

    # Start background thread to keep forwarding active
    def adb_loop():
        # Keep track of active forwarding state to avoid spamming successful logs
        last_state_success = None
        while True:
            try:
                result = subprocess.run(
                    [adb_path, "reverse", "tcp:8000", "tcp:8000"],
                    capture_output=True,
                    text=True
                )
                if result.returncode == 0:
                    if not last_state_success:
                        print("\n[USB SUCCESS] ADB reverse port forwarding active! (Phone plugged in)")
                        last_state_success = True
                else:
                    if last_state_success or last_state_success is None:
                        # Print only once when connection drops
                        print("\n[USB INFO] Waiting for USB device... (Phone unplugged or debugging disabled)")
                        last_state_success = False
            except Exception as e:
                print(f"\n[USB ERROR] Failed in background ADB task: {e}")
                break
            time.sleep(3)

    print("----------------------------------------------------------------")
    print("[USB SETUP] Starting background USB device monitor...")
    print("            (Will auto-connect whenever you plug in your phone)")
    print("----------------------------------------------------------------\n")
    
    t = threading.Thread(target=adb_loop, daemon=True)
    t.start()

def start_server():
    """Starts the FastAPI server."""
    print("[SERVER START] Launching FastAPI receiver on host 0.0.0.0:8000...")
    
    # Ensure current directory is on python path
    current_dir = os.path.dirname(os.path.abspath(__file__))
    sys.path.insert(0, current_dir)
    
    try:
        import uvicorn
        from receiver.config import settings
        
        uvicorn.run("receiver.main:app", host="0.0.0.0", port=settings.port, reload=True)
    except ImportError:
        print("[ERROR] uvicorn or fastapi is not installed.")
        print("        Please run: pip install -r receiver/requirements.txt")
        sys.exit(1)

if __name__ == "__main__":
    run_adb_reverse()
    start_server()
