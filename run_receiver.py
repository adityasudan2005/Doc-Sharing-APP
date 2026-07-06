import os
import sys
import subprocess
import shutil

def run_adb_reverse():
    """Attempts to configure ADB reverse port forwarding for USB mode."""
    print("----------------------------------------------------------------")
    print("[USB SETUP] Configuring USB reverse port forwarding...")
    
    # Check if adb is in PATH
    adb_path = shutil.which("adb")
    if not adb_path:
        print("[USB WARNING] 'adb' command not found in your system PATH.")
        print("              USB (Wired) mode won't work unless ADB is installed")
        print("              and you have enabled USB debugging on your phone.")
        print("              (Wi-Fi mode will still work fine!)")
        print("----------------------------------------------------------------\n")
        return

    try:
        # Run ADB reverse tcp:8000 tcp:8000
        result = subprocess.run(
            ["adb", "reverse", "tcp:8000", "tcp:8000"],
            capture_output=True,
            text=True
        )
        if result.returncode == 0:
            print("[USB SUCCESS] Forwarded phone port 8000 to laptop port 8000 via USB.")
            print("              You can now plug in your phone with USB debugging enabled!")
        else:
            print("[USB INFO] Could not configure ADB port forwarding.")
            print(f"           Reason: {result.stderr.strip()}")
            print("           Make sure your phone is connected and USB debugging is enabled.")
    except Exception as e:
        print(f"[USB ERROR] Failed to run adb command: {e}")
    print("----------------------------------------------------------------\n")

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
